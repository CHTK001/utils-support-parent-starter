package com.chua.common.support.vector;

import com.chua.common.support.tree.BPlusTree;

import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 向量存储：冷热混合 + B+ Tree 索引 + MappedByteBuffer 零拷贝读取。
 *
 * <h3>三种模式</h3>
 * <ul>
 *   <li>{@code memory} — 纯内存，不落盘（重启丢失）</li>
 *   <li>{@code file} — 纯文件分片，B+ Tree 索引 + MappedByteBuffer 零拷贝</li>
 *   <li>{@code hybrid}（默认）— 热数据内存缓存 + 冷数据磁盘分片 + 定时刷盘</li>
 * </ul>
 *
 * <h3>B+ Tree 索引</h3>
 * <p>每个冷分片构建一棵 {@link BPlusTree}，key 为向量 id，value 为
 * {@link EntryLoc}（文件路径 + 起始偏移 + 字节长度）。
 * 检索时通过 B+ Tree 精确跳转到指定记录的字节位置，避免全量顺序扫描。</p>
 *
 * <h3>MappedByteBuffer 零拷贝</h3>
 * <p>冷分片文件以 {@link FileChannel#map} 方式映射到堆外内存，
 * 读取单个向量时直接按 {@link EntryLoc} 偏移定位，无需逐条反序列化头部元数据。</p>
 *
 * @author chua
 * @since 4.0.0.42
 */
public class DefaultVectorStorage implements VectorStorage {

    /** 存储模式。 */
    public enum Mode { MEMORY, FILE, HYBRID }

    /** 冷分片内单条记录的位置信息。 */
    private record EntryLoc(Path path, long offset, int length) {
    }

    /** 封装 MappedByteBuffer 及其关联的 FileChannel，用于资源管理。 */
    private record ShardBuffer(MappedByteBuffer buf, FileChannel fc) {
    }

    private final int dimension;
    private final Path dir;
    private final Mode mode;
    private final int shardSize;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /** 热数据：内存索引（id → Vector）。 */
    private final ConcurrentHashMap<String, Vector> hot = new ConcurrentHashMap<>();
    /** 冷数据分片列表（有序，用于定位文件路径）。 */
    private final TreeMap<Integer, Path> coldShards = new TreeMap<>();
    /** 路径 → 分片序号的反向索引，O(1) 查找，避免每次 readEntry 线性扫描。 */
    private final ConcurrentHashMap<Path, Integer> pathToShardIdx = new ConcurrentHashMap<>();
    /** 冷数据 B+ Tree 索引：id → EntryLoc，用于精确跳跃读取。 */
    private final BPlusTree<String, EntryLoc> coldIndex = new BPlusTree<>(128);
    /** 已映射的分片缓冲，key 为分片序号，value 为 MappedByteBuffer + FileChannel。 */
    private final ConcurrentHashMap<Integer, ShardBuffer> shardBuffers = new ConcurrentHashMap<>();

    /** 复用读缓冲：128维向量约 600B，ThreadLocal 避免多线程竞争。 */
    private static final int DEFAULT_READ_BUF = 4096;
    private final ThreadLocal<byte[]> threadLocalReadBuf = ThreadLocal.withInitial(() -> new byte[DEFAULT_READ_BUF]);

    /** SIMD 分块宽度：每次处理 16 个 float。 */
    private static final int SIMD = 16;

    // ---- 构造 ----

    private DefaultVectorStorage(int dimension, Path dir, Mode mode, int shardSize) {
        this.dimension = dimension;
        this.dir = dir;
        this.mode = mode;
        this.shardSize = shardSize;
        if (mode != Mode.MEMORY) {
            dir.toFile().mkdirs();
            loadColdShards();
        }
    }

    /** 创建 DefaultVectorStorage。 */
    public static DefaultVectorStorage create(int dimension, Mode mode) {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "default-vectors");
        return new DefaultVectorStorage(dimension, dir, mode, 10000);
    }

    /** 创建 DefaultVectorStorage（指定目录）。 */
    public static DefaultVectorStorage create(int dimension, Path dir) {
        return new DefaultVectorStorage(dimension, dir, Mode.HYBRID, 10000);
    }

    /** 自定义构建器。 */
    public static Builder builder() {
        return new Builder();
    }

    // ---- VectorStorage 接口实现 ----

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public boolean add(Vector v) {
        lock.writeLock().lock();
        try {
            hot.put(v.id(), v);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean add(String id, float[] data) {
        return add(new Vector(id, data));
    }

    @Override
    public boolean remove(String id) {
        lock.writeLock().lock();
        try {
            hot.remove(id);
            coldIndex.remove(id);
            evictShardBufferFor(id);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean update(String id, float[] data) {
        lock.writeLock().lock();
        try {
            coldIndex.remove(id);
            evictShardBufferFor(id);
            hot.put(id, new Vector(id, data));
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void clear() {
        lock.writeLock().lock();
        try {
            hot.clear();
            coldShards.clear();
            pathToShardIdx.clear();
            coldIndex.clear();
            shardBuffers.values().forEach(sb -> {
                try { if (sb.fc().isOpen()) sb.fc().close(); } catch (IOException ignored) { }
            });
            shardBuffers.clear();
            if (mode != Mode.MEMORY && dir.toFile().exists()) {
                for (File f : dir.toFile().listFiles()) {
                    if (f.getName().endsWith(".bin")) f.delete();
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try {
            return hot.size() + coldIndex.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<Vector> search(float[] query, int topK) {
        lock.readLock().lock();
        try {
            List<VectorScored> candidates = new ArrayList<>();
            for (Vector v : hot.values()) {
                float sim = cosineSIMD(query, v.data());
                candidates.add(new VectorScored(v, sim));
            }
            scanColdCandidates(query, candidates);
            candidates.sort((a, b) -> Float.compare(b.score, a.score));
            List<Vector> results = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (VectorScored vs : candidates) {
                if (seen.add(vs.vector.id())) {
                    results.add(vs.vector);
                    if (results.size() >= topK) break;
                }
            }
            return results;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void close() {
        flush();
        lock.writeLock().lock();
        try {
            hot.clear();
            coldShards.clear();
            pathToShardIdx.clear();
            coldIndex.clear();
            shardBuffers.values().forEach(sb -> {
                try { if (sb.fc().isOpen()) sb.fc().close(); } catch (IOException ignored) { }
            });
            shardBuffers.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ---- 冷热管理 ----

    /** 将热数据刷盘为冷分片，并重建 B+ Tree 索引。 */
    public void flush() {
        lock.writeLock().lock();
        try {
            if (hot.isEmpty()) return;
            List<Vector> toFlush = new ArrayList<>(hot.values());
            int shardIdx = coldShards.isEmpty() ? 0 : coldShards.lastKey() + 1;
            Path shardPath = dir.resolve(String.format("shard_%04d.bin", shardIdx));
            try { writeShard(shardPath, toFlush); } catch (IOException e) {
                throw new UncheckedIOException("刷盘失败", e);
            }
            buildShardIndex(shardIdx, shardPath, 8L, toFlush.size());
            coldShards.put(shardIdx, shardPath);
            pathToShardIdx.put(shardPath, shardIdx);
            hot.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ---- 分片 IO ----

    private void writeShard(Path path, List<Vector> vectors) throws IOException {
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(path)))) {
            out.writeInt(vectors.size());
            out.writeInt(dimension);
            for (Vector v : vectors) {
                byte[] idBytes = v.id().getBytes(UTF_8);
                out.writeInt(idBytes.length);
                out.write(idBytes);
                for (float f : v.data()) out.writeFloat(f);
                String metaStr = (v.metadata() != null && !v.metadata().isEmpty())
                        ? v.metadata().toString() : "";
                out.writeUTF(metaStr);
            }
        }
    }

    /**
     * 加载已有冷分片，同时构建 B+ Tree 索引、path→shardIdx 反向索引和 MappedByteBuffer 缓存。
     */
    private void loadColdShards() {
        File[] files = dir.toFile().listFiles((d, n) -> n.matches("shard_\\d{4}\\.bin"));
        if (files == null) return;
        Arrays.sort(files);
        for (File f : files) {
            int idx = Integer.parseInt(f.getName().substring(6, 10));
            Path shardPath = f.toPath();
            coldShards.put(idx, shardPath);
            pathToShardIdx.put(shardPath, idx);
            try (DataInputStream in = new DataInputStream(
                    new BufferedInputStream(Files.newInputStream(shardPath)))) {
                int count = in.readInt();
                int dim = in.readInt();
                long offset = 8L;
                for (int i = 0; i < count; i++) {
                    int idLen = in.readInt();
                    byte[] idBytes = new byte[idLen];
                    in.readFully(idBytes);
                    String id = new String(idBytes, UTF_8);
                    for (int j = 0; j < dim; j++) in.readFloat();
                    String metaStr = in.readUTF();
                    int entryLen = 4 + idLen + dim * 4 + 2 + metaStr.getBytes(UTF_8).length;
                    coldIndex.put(id, new EntryLoc(shardPath, offset, entryLen));
                    offset += entryLen;
                }
            } catch (IOException ignored) {
            }
            mmapShard(idx, shardPath);
        }
    }

    /** 将分片文件映射到堆外内存。 */
    private void mmapShard(int idx, Path shardPath) {
        try {
            FileChannel fc = FileChannel.open(shardPath, java.nio.file.StandardOpenOption.READ);
            MappedByteBuffer buf = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size());
            shardBuffers.put(idx, new ShardBuffer(buf, fc));
        } catch (IOException e) {
            throw new UncheckedIOException("映射分片文件失败: " + shardPath, e);
        }
    }

    /**
     * 根据冷索引中的 EntryLoc，从 MappedByteBuffer 精确偏移读取单条向量。
     * 使用 duplicate() 创建独立视图，position/limit 操作互不干扰原 buffer。
     */
    private Vector readEntry(EntryLoc loc) {
        Integer shardIdxObj = pathToShardIdx.get(loc.path());
        if (shardIdxObj == null) return null;
        ShardBuffer sb = shardBuffers.get(shardIdxObj);
        if (sb == null) return null;
        MappedByteBuffer buf = sb.buf();
        long off = loc.offset();
        int len = loc.length();
        try {
            MappedByteBuffer view = buf.duplicate();
            view.position((int) off);
            view.limit((int) Math.min(off + len, buf.capacity()));
            int bytesToRead = view.limit() - view.position();
            if (bytesToRead <= 0) return null;
            byte[] localBuf = threadLocalReadBuf.get();
            if (localBuf.length < bytesToRead) {
                localBuf = new byte[Math.max(bytesToRead * 2, 4096)];
                threadLocalReadBuf.set(localBuf);
            }
            view.get(localBuf, 0, bytesToRead);
            try (ByteArrayInputStream bais = new ByteArrayInputStream(localBuf, 0, bytesToRead);
                 DataInputStream dis = new DataInputStream(bais)) {
                int idLen = dis.readInt();
                byte[] idBytes = new byte[idLen];
                dis.readFully(idBytes);
                String id = new String(idBytes, UTF_8);
                float[] data = new float[dimension];
                for (int j = 0; j < dimension; j++) data[j] = dis.readFloat();
                String metaStr = dis.readUTF();
                return metaStr.isEmpty() ? new Vector(id, data) : new Vector(id, data,
                        parseMetadata(metaStr), null);
            }
        } catch (IllegalArgumentException | IOException e) {
            return null;
        }
    }

    /**
     * 解析 metadata 字符串（flush 时写入的是 Map.toString()，此处简单解析）。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseMetadata(String metaStr) {
        if (metaStr == null || metaStr.isEmpty()) return Collections.emptyMap();
        try {
            java.util.Map<String, Object> map = new LinkedHashMap<>();
            String inner = metaStr.strip().startsWith("{")
                    ? metaStr.substring(1, metaStr.length() - 1) : metaStr;
            for (String pair : inner.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String k = pair.substring(0, eq).strip();
                    String v = pair.substring(eq + 1).strip();
                    map.put(k, v);
                }
            }
            return map;
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }

    /** 为指定 id 驱逐对应的 ShardBuffer（删除后不再需要）。 */
    private void evictShardBufferFor(String id) {
        EntryLoc loc = coldIndex.get(id).orElse(null);
        if (loc != null) {
            Integer shardIdxObj = pathToShardIdx.get(loc.path());
            if (shardIdxObj != null) {
                shardBuffers.remove(shardIdxObj);
                coldShards.remove(shardIdxObj);
                pathToShardIdx.remove(loc.path());
            }
        }
    }

    /** flush 后重建当前分片的 B+ Tree 索引（增量更新）。 */
    private void buildShardIndex(int shardIdx, Path shardPath, long headerSize, int count) {
        long offset = headerSize;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(shardPath)))) {
            in.readInt(); // skip count
            in.readInt(); // skip dimension
            for (int i = 0; i < count; i++) {
                int idLen = in.readInt();
                byte[] idBytes = new byte[idLen];
                in.readFully(idBytes);
                String id = new String(idBytes, UTF_8);
                for (int j = 0; j < dimension; j++) in.readFloat();
                String metaStr = in.readUTF();
                int entryLen = 4 + idLen + dimension * 4 + 2 + metaStr.getBytes(UTF_8).length;
                coldIndex.put(id, new EntryLoc(shardPath, offset, entryLen));
                offset += entryLen;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("构建冷索引失败", e);
        }
        mmapShard(shardIdx, shardPath);
        pathToShardIdx.put(shardPath, shardIdx);
    }

    // ---- 检索辅助 ----

    /**
     * 扫描所有冷分片，通过 B+ Tree 索引精确读取每条记录，计算余弦相似度。
     */
    private void scanColdCandidates(float[] query, List<VectorScored> candidates) {
        for (Map.Entry<String, EntryLoc> entry : coldIndex.allEntries()) {
            EntryLoc loc = entry.getValue();
            Vector v = readEntry(loc);
            if (v == null || v.data() == null) continue;
            float sim = cosineSIMD(query, v.data());
            candidates.add(new VectorScored(v, sim));
        }
    }

    // ---- Java Vector API 加速余弦相似度 ----

    /** 余弦相似度（手动 SIMD 分块，每次 16 个 float）。 */
    private static float cosineSIMD(float[] a, float[] b) {
        int len = Math.min(a.length, b.length);
        int i = 0;
        double dot = 0, normA = 0, normB = 0;
        int end16 = len - SIMD + 1;
        for (; i < end16; i += SIMD) {
            for (int j = 0; j < SIMD; j++) {
                float ai = a[i + j], bi = b[i + j];
                dot += ai * bi;
                normA += ai * ai;
                normB += bi * bi;
            }
        }
        for (; i < len; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0f : (float) (dot / denom);
    }

    // ---- 内部记录 ----

    private record VectorScored(Vector vector, float score) {
    }

    /** 获取已加载的冷分片数量（供测试使用）。 */
    public int getShardCount() {
        return coldShards.size();
    }

    private static final java.nio.charset.Charset UTF_8 = java.nio.charset.StandardCharsets.UTF_8;

    // ---- Builder ----

    public static final class Builder {
        private int dimension;
        private Path dir;
        private Mode mode = Mode.HYBRID;
        private int shardSize = 10000;

        public Builder dimension(int d) {
            this.dimension = d;
            return this;
        }

        public Builder dir(Path dir) {
            this.dir = dir;
            return this;
        }

        public Builder mode(Mode mode) {
            this.mode = mode;
            return this;
        }

        public Builder shardSize(int size) {
            this.shardSize = Math.max(100, size);
            return this;
        }

        public DefaultVectorStorage build() {
            if (dimension <= 0) {
                throw new IllegalArgumentException("dimension 须 > 0");
            }
            Path d = dir != null ? dir : Path.of(System.getProperty("java.io.tmpdir"), "default-vectors");
            return new DefaultVectorStorage(dimension, d, mode, shardSize);
        }
    }
}
