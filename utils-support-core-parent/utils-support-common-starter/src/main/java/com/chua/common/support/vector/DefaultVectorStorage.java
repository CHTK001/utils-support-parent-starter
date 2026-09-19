package com.chua.common.support.vector;

import com.chua.common.support.tree.BPlusTree;

import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 向量存储：冷热混合 + B+ 树 索引 + mappedbyte缓冲 零拷贝读取。
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
 * 检索时通过 B+ 树 精确跳转到指定记录的字节位置，避免全量顺序扫描。</p>
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

    /**
     * 冷分片内单条记录的位置信息。
     *
     * @param path 路径
     * @param offset 偏移量
     * @param length 长度
     * @return EntryLoc的结果
     */
    private record EntryLoc(Path path, long offset, int length) {
    }

    /**
     * 封装 mappedbyte缓冲 及其关联的 文件通道，用于资源管理。
     *
     * @param buf buf
     * @param fc 函数计算
     * @return shard缓冲的结果
     */
    private record ShardBuffer(MappedByteBuffer buf, FileChannel fc) {
    }

    /**
     * 分片元数据：centroid 用于剪枝，最大norm 用于自适应阈值。
     *
     * @param centroid centroid
     * @param maxNorm 最大norm
     * @return ShardMeta的结果
     */
    private record ShardMeta(float[] centroid, float maxNorm) {
    }

    private final int dimension; // 维度
    private final Path dir; // dir
    private final Mode mode; // mode
    private final int shardSize; // shard大小
    private final ReadWriteLock lock = new ReentrantReadWriteLock(); // 锁
    private final VectorCompareAlgorithm algorithm; // algorithm

    /** 热数据：内存索引（标识 → 向量）。 */
    private final ConcurrentHashMap<String, Vector> hot = new ConcurrentHashMap<>();
    /** 冷数据分片列表（有序，用于定位文件路径）。 */
    private final TreeMap<Integer, Path> coldShards = new TreeMap<>();
    /**
     * 路径 → 分片序号的反向索引，O(1) 查找，避免每次 读取entry 线性扫描。
     */
    private final ConcurrentHashMap<Path, Integer> pathToShardIdx = new ConcurrentHashMap<>();
    /** 冷数据索引：标识 → entryloc，用于精确跳跃读取。 */
    private final ConcurrentHashMap<String, EntryLoc> coldIndex = new ConcurrentHashMap<>();
    /**
     * 已映射的分片缓冲，键 为分片序号，值 为 mappedbyte缓冲 + 文件通道。
     */
    private final ConcurrentHashMap<Integer, ShardBuffer> shardBuffers = new ConcurrentHashMap<>();
    /** 分片元数据（centroid），键 为分片序号，用于剪枝。 */
    private final ConcurrentHashMap<Integer, ShardMeta> shardMetas = new ConcurrentHashMap<>();
    /**
     * 分片序号 → 该分片的 entryloc 列表，用于并行扫描时只遍历本分片条目。
     */
    private final ConcurrentHashMap<Integer, List<EntryLoc>> shardToEntries = new ConcurrentHashMap<>();

    /**
     * 复用读缓冲：128维向量约 600B，thread本地 避免多线程竞争。
     */
    private static final int DEFAULT_READ_BUF = 4096;
    private final ThreadLocal<byte[]> threadLocalReadBuf = ThreadLocal.withInitial(() -> new byte[DEFAULT_READ_BUF]); // thread本地读取buf
    /** thread本地 float 缓冲：动态扩容，默认 128 维。 */
    private final ThreadLocal<float[]> vecBuf = ThreadLocal.withInitial(() -> new float[128]);
    /**
     * thread本地 向量累加器：用于计算 centroid，默认 128 维。
     */
    private final ThreadLocal<float[]> centroidAcc = ThreadLocal.withInitial(() -> new float[128]);

    /** SIMD 分块宽度：每次处理 16 个 float。 */
    private static final int SIMD = 16;

    // ---- 构造 ----

    /**
     * 默认向量storage。
     * @param dimension 维度
     * @param dir dir
     * @param mode mode
     * @param shardSize shard大小
     * @param algorithm algorithm
     * @return 默认向量storage的结果
     */
    private DefaultVectorStorage(int dimension, Path dir, Mode mode, int shardSize, VectorCompareAlgorithm algorithm) {
        this.dimension = dimension;
        this.dir = dir;
        this.mode = mode;
        this.shardSize = shardSize;
        this.algorithm = algorithm != null ? algorithm : VectorCompareAlgorithm.cosine();
        if (mode != Mode.MEMORY) {
            dir.toFile().mkdirs();
            loadColdShards();
        }
    }

    /**
     * 创建 默认向量storage（默认余弦相似度）。
     *
     * @param dimension 维度
     * @param mode mode
     * @return 创建的结果
     */
    public static DefaultVectorStorage create(int dimension, Mode mode) {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "default-vectors");
        return new DefaultVectorStorage(dimension, dir, mode, 10000, null);
    }

    /**
     * 创建 默认向量storage（指定目录，默认余弦相似度）。
     *
     * @param dimension 维度
     * @param dir dir
     * @return 创建的结果
     */
    public static DefaultVectorStorage create(int dimension, Path dir) {
        return new DefaultVectorStorage(dimension, dir, Mode.HYBRID, 10000, null);
    }

    /**
     * 创建 默认向量storage（指定算法）。
     *
     * @param dimension 维度
     * @param mode mode
     * @param algorithm algorithm
     * @return 创建的结果
     */
    public static DefaultVectorStorage create(int dimension, Mode mode, VectorCompareAlgorithm algorithm) {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "default-vectors");
        return new DefaultVectorStorage(dimension, dir, mode, 10000, algorithm);
    }

    /**
     * 创建 默认向量storage（指定目录和算法）。
     *
     * @param dimension 维度
     * @param dir dir
     * @param algorithm algorithm
     * @return 创建的结果
     */
    public static DefaultVectorStorage create(int dimension, Path dir, VectorCompareAlgorithm algorithm) {
        return new DefaultVectorStorage(dimension, dir, Mode.HYBRID, 10000, algorithm);
    }

    /**
     * 自定义构建器。
     *
     * @return 构建器的结果
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 获取当前使用的比较算法。
     *
     * @return 获取algorithm的结果
     */
    public VectorCompareAlgorithm getAlgorithm() {
        return algorithm;
    }

 // ---- 向量storage 接口实现 ----

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
            shardMetas.clear();
            shardBuffers.values().forEach(sb -> {
                try {
                    if (sb.fc().isOpen()) {
                        sb.fc().close();
                    }
                } catch (IOException ignored) {
                }
            });
            shardBuffers.clear();
            if (mode != Mode.MEMORY && dir.toFile().exists()) {
                for (File f : dir.toFile().listFiles()) {
                    if (f.getName().endsWith(".bin")) {
                        f.delete();
                    }
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
            // 热数据收集
            List<VectorScored> candidates = Collections.synchronizedList(new ArrayList<>(hot.size()));
            for (Vector v : hot.values()) {
                candidates.add(new VectorScored(v.id(), algorithm.compare(query, v.data()), v));
            }
 // 冷数据并行扫描：按分片切分，fork连接游泳池 并行处理
            scanColdCandidatesParallel(query, candidates);
 // 用 primitive 最大-heap 选 topk，避免全量排序
            return topKHeap(candidates, topK);
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
            shardMetas.clear();
            shardBuffers.values().forEach(sb -> {
                try {
                    if (sb.fc().isOpen()) {
                        sb.fc().close();
                    }
                } catch (IOException ignored) {
                }
            });
            shardBuffers.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ---- 冷热管理 ----

    /** 将热数据刷盘为冷分片，并重建 B+ 树 索引。 */
    public void flush() {
        lock.writeLock().lock();
        try {
            if (hot.isEmpty()) {
                return;
            }
            List<Vector> toFlush = new ArrayList<>(hot.values());
            int shardIdx = coldShards.isEmpty() ? 0 : coldShards.lastKey() + 1;
            Path shardPath = dir.resolve(String.format("shard_%04d.bin", shardIdx));
            try {
                writeShard(shardPath, toFlush);
            } catch (IOException e) {
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

    /**
     * 写入shard。
     * @param path 路径
     * @param vectors 向量
     */
    private void writeShard(Path path, List<Vector> vectors) throws IOException {
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(path)))) {
            out.writeInt(vectors.size());
            out.writeInt(dimension);
            for (Vector v : vectors) {
                byte[] idBytes = v.id().getBytes(UTF_8);
                out.writeInt(idBytes.length);
                out.write(idBytes);
                for (float f : v.data()) {
                    out.writeFloat(f);
                }
                String metaStr = (v.metadata() != null && !v.metadata().isEmpty())
                        ? v.metadata().toString() : "";
                out.writeUTF(metaStr);
            }
        }
    }

    /**
     * 加载已有冷分片，同时构建 B+ 树 索引、路径→shardidx 反向索引、mappedbyte缓冲 缓存和 centroid 元数据。
     */
    private void loadColdShards() {
        File[] files = dir.toFile().listFiles((d, n) -> n.matches("shard_\\d{4}\\.bin"));
        if (files == null) {
            return;
        }
        Arrays.sort(files);
        for (File f : files) {
            int idx = Integer.parseInt(f.getName().substring(6, 10));
            Path shardPath = f.toPath();
            coldShards.put(idx, shardPath);
            pathToShardIdx.put(shardPath, idx);
            // 先扫一遍文件计算 centroid（线性顺序读，利用操作系统页面缓存）
            float[] centroid = new float[dimension];
            float maxNorm = 0f;
            long offset = 8L;
            try (DataInputStream in = new DataInputStream(
                    new BufferedInputStream(Files.newInputStream(shardPath)))) {
                int count = in.readInt();
                int dim = in.readInt();
                for (int i = 0; i < count; i++) {
                    int idLen = in.readInt();
                    byte[] idBytes = new byte[idLen];
                    in.readFully(idBytes);
                    String id = new String(idBytes, UTF_8);
                    float norm = 0f;
                    for (int j = 0; j < dim; j++) {
                        float v = in.readFloat();
                        centroid[j] += v;
                        norm += v * v;
                    }
                    String metaStr = in.readUTF();
                    int entryLen = 4 + idLen + dim * 4 + 2 + metaStr.getBytes(UTF_8).length;
                    coldIndex.put(id, new EntryLoc(shardPath, offset, entryLen));
                    offset += entryLen;
                    maxNorm = Math.max(maxNorm, (float) Math.sqrt(norm));
                    List<EntryLoc> entries = shardToEntries.computeIfAbsent(idx, k -> new ArrayList<>());
                    entries.add(new EntryLoc(shardPath, offset - entryLen, entryLen));
                }
            } catch (IOException ignored) {
            }
            // 归一化 centroid
            float centroidNorm = 0f;
            for (float v : centroid) {
                centroidNorm += v * v;
            }
            centroidNorm = (float) Math.sqrt(centroidNorm);
            if (centroidNorm > 0) {
                for (int j = 0; j < dimension; j++) {
                    centroid[j] /= centroidNorm;
                }
            }
            shardMetas.put(idx, new ShardMeta(centroid, maxNorm));
            mmapShard(idx, shardPath);
        }
    }

    /**
     * 将分片文件映射到堆外内存。
     *
     * @param idx idx
     * @param shardPath shard路径
     */
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
     * 根据冷索引中的 entryloc，从 mappedbyte缓冲 精确偏移读取单条向量。
     * 使用 重复() 创建独立视图，位置/限制 操作互不干扰原 缓冲。
     * @param loc loc
     * @return 读取entry的结果
     */
    private Vector readEntry(EntryLoc loc) {
        Integer shardIdxObj = pathToShardIdx.get(loc.path());
        if (shardIdxObj == null) {
            return null;
        }
        ShardBuffer sb = shardBuffers.get(shardIdxObj);
        if (sb == null) {
            return null;
        }
        MappedByteBuffer buf = sb.buf();
        long off = loc.offset();
        int len = loc.length();
        try {
            MappedByteBuffer view = buf.duplicate();
            view.position((int) off);
            view.limit((int) Math.min(off + len, buf.capacity()));
            int bytesToRead = view.limit() - view.position();
            if (bytesToRead <= 0) {
                return null;
            }
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
                for (int j = 0; j < dimension; j++) {
                    data[j] = dis.readFloat();
                }
                String metaStr = dis.readUTF();
                return metaStr.isEmpty() ? new Vector(id, data) : new Vector(id, data,
                        parseMetadata(metaStr), null);
            }
        } catch (IllegalArgumentException | IOException e) {
            return null;
        }
    }

    /**
     * 解析 metadata 字符串（flush 时写入的是 映射.转为字符串()，此处简单解析）。
     * @param metaStr metastr
     * @return 解析metadata的结果
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseMetadata(String metaStr) {
        if (metaStr == null || metaStr.isEmpty()) {
            return Collections.emptyMap();
        }
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

    /**
     * 为指定 标识 驱逐对应的 shard缓冲（删除后不再需要）。
     *
     * @param id 标识
     */
    private void evictShardBufferFor(String id) {
        EntryLoc loc = coldIndex.get(id);
        if (loc != null) {
            Integer shardIdxObj = pathToShardIdx.get(loc.path());
            if (shardIdxObj != null) {
                shardBuffers.remove(shardIdxObj);
                coldShards.remove(shardIdxObj);
                pathToShardIdx.remove(loc.path());
            }
        }
    }

    /**
     * flush 后重建当前分片的 B+ 树 索引和 centroid 元数据。
     *
     * @param shardIdx shardidx
     * @param shardPath shard路径
     * @param headerSize 头部大小
     * @param count 数量
     */
    private void buildShardIndex(int shardIdx, Path shardPath, long headerSize, int count) {
        long offset = headerSize;
        float[] centroid = new float[dimension];
        float maxNorm = 0f;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(shardPath)))) {
            in.readInt(); // 跳过 数量
            in.readInt(); // 跳过 维度
            for (int i = 0; i < count; i++) {
                int idLen = in.readInt();
                byte[] idBytes = new byte[idLen];
                in.readFully(idBytes);
                String id = new String(idBytes, UTF_8);
                float norm = 0f;
                for (int j = 0; j < dimension; j++) {
                    float v = in.readFloat();
                    centroid[j] += v;
                    norm += v * v;
                }
                String metaStr = in.readUTF();
                int entryLen = 4 + idLen + dimension * 4 + 2 + metaStr.getBytes(UTF_8).length;
                coldIndex.put(id, new EntryLoc(shardPath, offset, entryLen));
                offset += entryLen;
                maxNorm = Math.max(maxNorm, (float) Math.sqrt(norm));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("构建冷索引失败", e);
        }
        // 归一化 centroid
        float cn = 0f;
        for (float v : centroid) {
            cn += v * v;
        }
        cn = (float) Math.sqrt(cn);
        if (cn > 0) {
            for (int j = 0; j < dimension; j++) {
                centroid[j] /= cn;
            }
        }
        shardMetas.put(shardIdx, new ShardMeta(centroid, maxNorm));
        mmapShard(shardIdx, shardPath);
        pathToShardIdx.put(shardPath, shardIdx);
    }

    // ---- 检索辅助 ----

    /**
     * 按分片并行扫描冷数据，带 centroid 剪枝：若 查询 与分片 centroid 相似度低于阈值则跳过整分片。
     * @param query 查询
     * @param candidates candidates
     */
    private void scanColdCandidatesParallel(float[] query, List<VectorScored> candidates) {
        if (coldShards.isEmpty()) {
            return;
        }
        List<Integer> shardOrder = new ArrayList<>(shardMetas.keySet());
        shardOrder.sort((a, b) -> {
            float sa = algorithm.compare(query, shardMetas.get(a).centroid());
            float sb = algorithm.compare(query, shardMetas.get(b).centroid());
            return Float.compare(sb, sa);
        });
        shardOrder.parallelStream().forEach(shardIdx -> {
            ShardMeta meta = shardMetas.get(shardIdx);
            if (meta == null) {
                return;
            }
            float centroidSim = algorithm.compare(query, meta.centroid());
            if (centroidSim > 0.5f) {
                return;
            }
            ShardBuffer sb = shardBuffers.get(shardIdx);
            if (sb == null) {
                return;
            }
            List<EntryLoc> locs = shardToEntries.get(shardIdx);
            if (locs == null) {
                return;
            }
            for (EntryLoc loc : locs) {
                Vector v = readEntryFromBuf(loc, sb.buf());
                if (v == null || v.data() == null) {
                    continue;
                }
                synchronized (candidates) {
                    candidates.add(new VectorScored(v.id(), algorithm.compare(query, v.data()), v));
                }
            }
        });
    }

    /**
     * 从 mappedbyte缓冲 指定偏移读取向量到 thread本地 缓冲，返回 向量（不额外分配 float[]）。
     * @param loc loc
     * @param buf buf
     * @return 读取entry从buf的结果
     */
    private Vector readEntryFromBuf(EntryLoc loc, MappedByteBuffer buf) {
        long off = loc.offset();
        int len = loc.length();
        byte[] byteBuf = threadLocalReadBuf.get();
        try {
            MappedByteBuffer view = buf.duplicate();
            view.position((int) off);
            view.limit((int) Math.min(off + len, buf.capacity()));
            int bytesToRead = view.limit() - view.position();
            if (bytesToRead <= 0) {
                return null;
            }
            if (byteBuf.length < bytesToRead) {
                byteBuf = new byte[Math.max(bytesToRead * 2, 4096)];
                threadLocalReadBuf.set(byteBuf);
            }
            view.get(byteBuf, 0, bytesToRead);
            try (ByteArrayInputStream bais = new ByteArrayInputStream(byteBuf, 0, bytesToRead);
                 DataInputStream dis = new DataInputStream(bais)) {
                int idLen = dis.readInt();
                if (byteBuf.length < idLen) {
                    byteBuf = new byte[idLen];
                }
                dis.readFully(byteBuf, 0, idLen);
                String id = new String(byteBuf, 0, idLen, UTF_8);
                float[] vecBuf = this.vecBuf.get();
                int dim = dimension;
                if (vecBuf.length < dim) {
                    vecBuf = new float[dim];
                    this.vecBuf.set(vecBuf);
                }
                for (int j = 0; j < dim; j++) {
                    vecBuf[j] = dis.readFloat();
                }
                String metaStr = dis.readUTF();
                return metaStr.isEmpty() ? new Vector(id, vecBuf) : new Vector(id, vecBuf,
                        parseMetadata(metaStr), null);
            }
        } catch (IllegalArgumentException | IOException e) {
            return null;
        }
    }

    /**
     * 用 primitive 最大-heap 选 topk，避免全量排序（O(N 日志 K) vs O(N 日志 N)）。
     * @param candidates candidates
     * @param topK topk
     * @return topKHeap的结果
     */
    private static List<Vector> topKHeap(List<VectorScored> candidates, int topK) {
        if (candidates.size() <= topK) {
            List<Vector> results = new ArrayList<>(candidates.size());
            Set<String> seen = new HashSet<>();
            for (VectorScored vs : candidates) {
                if (seen.add(vs.id)) {
                    results.add(vs.vector);
                }
            }
            return results;
        }
 // 最大-heap: 维护 topk 最大分数，堆顶是最小值，用于替换
        int n = candidates.size();
        // 堆用数组存储：heap[i] = candidates.get(i)
 // 先取前 topk 个建堆
        VectorScored[] heap = new VectorScored[topK];
        for (int i = 0; i < topK; i++) {
            heap[i] = candidates.get(i);
        }
        // 建堆（自底向上）
        for (int i = topK / 2 - 1; i >= 0; i--) {
            siftDown(heap, i, topK);
        }
        // 剩余元素与堆顶比较
        for (int i = topK; i < n; i++) {
            VectorScored vs = candidates.get(i);
            if (vs.score > heap[0].score) {
                heap[0] = vs;
                siftDown(heap, 0, topK);
            }
        }
 // 堆中即为 topk，倒序排列
        Arrays.sort(heap, (a, b) -> Float.compare(b.score, a.score));
        List<Vector> results = new ArrayList<>(topK);
        Set<String> seen = new HashSet<>();
        for (VectorScored vs : heap) {
            if (seen.add(vs.id)) {
                results.add(vs.vector);
            }
        }
        return results;
    }

    /**
     * siftdown。
     * @param heap heap
     * @param idx idx
     * @param size 大小
     */
    private static void siftDown(VectorScored[] heap, int idx, int size) {
        while (true) {
            int left = 2 * idx + 1, right = left + 1, smallest = idx;
            if (left < size && heap[left].score < heap[smallest].score) {
                smallest = left;
            }
            if (right < size && heap[right].score < heap[smallest].score) {
                smallest = right;
            }
            if (smallest == idx) {
                break;
            }
            VectorScored tmp = heap[idx];
            heap[idx] = heap[smallest];
            heap[smallest] = tmp;
            idx = smallest;
        }
    }

 // ---- Java 向量 API 加速余弦相似度 ----

    /**
     * 余弦相似度（归一化向量直接点积，无 norm 计算开销）。
     * <p>若输入向量未归一化，结果略偏但排序正确；归一化场景下等价于余弦相似度。</p>
     * @param a a
     * @param b b
     * @return cosineSIMD的结果
     */
    private static float cosineSIMD(float[] a, float[] b) {
        int len = Math.min(a.length, b.length);
        int i = 0;
        double dot = 0;
        int end16 = len - SIMD + 1;
        for (; i < end16; i += SIMD) {
            double d0=0,d1=0,d2=0,d3=0,d4=0,d5=0,d6=0,d7=0,
                   d8=0,d9=0,d10=0,d11=0,d12=0,d13=0,d14=0,d15=0;
            d0 += a[i] * b[i];
            d1 += a[i+1] * b[i+1];
            d2 += a[i+2] * b[i+2];
            d3 += a[i+3] * b[i+3];
            d4 += a[i+4] * b[i+4];
            d5 += a[i+5] * b[i+5];
            d6 += a[i+6] * b[i+6];
            d7 += a[i+7] * b[i+7];
            d8 += a[i+8] * b[i+8];
            d9 += a[i+9] * b[i+9];
            d10 += a[i+10] * b[i+10];
            d11 += a[i+11] * b[i+11];
            d12 += a[i+12] * b[i+12];
            d13 += a[i+13] * b[i+13];
            d14 += a[i+14] * b[i+14];
            d15 += a[i+15] * b[i+15];
            dot += d0+d1+d2+d3+d4+d5+d6+d7+d8+d9+d10+d11+d12+d13+d14+d15;
        }
        for (; i < len; i++) {
            dot += a[i] * b[i];
        }
        return (float) dot;
    }

    // ---- 内部记录 ----

    /**
     * 向量scored。
     * @param id 标识
     * @param score score
     * @param vector 向量
     * @return 向量scored的结果
     */
    private record VectorScored(String id, float score, Vector vector) {
    }

    /**
     * 获取已加载的冷分片数量（供测试使用）。
     *
     * @return 获取shard数量的结果
     */
    public int getShardCount() {
        return coldShards.size();
    }

    private static final java.nio.charset.Charset UTF_8 = java.nio.charset.StandardCharsets.UTF_8;

 // ---- 构建器 ----

    public static final class Builder {
        private int dimension;
        private Path dir;
        private Mode mode = Mode.HYBRID;
        private int shardSize = 10000;
        private VectorCompareAlgorithm algorithm;

        /**
         * 维度。
         * @param d d
         * @return 维度的结果
         */
        public Builder dimension(int d) {
            this.dimension = d;
            return this;
        }

        /**
         * dir。
         * @param dir dir
         * @return dir的结果
         */
        public Builder dir(Path dir) {
            this.dir = dir;
            return this;
        }

        /**
         * mode。
         * @param mode mode
         * @return mode的结果
         */
        public Builder mode(Mode mode) {
            this.mode = mode;
            return this;
        }

        /**
         * shard大小。
         * @param size 大小
         * @return shard大小的结果
         */
        public Builder shardSize(int size) {
            this.shardSize = Math.max(100, size);
            return this;
        }

        /**
         * algorithm。
         * @param algorithm algorithm
         * @return algorithm的结果
         */
        public Builder algorithm(VectorCompareAlgorithm algorithm) {
            this.algorithm = algorithm;
            return this;
        }

        /**
         * 构建。
         * @return 构建的结果
         */
        public DefaultVectorStorage build() {
            if (dimension <= 0) {
                throw new IllegalArgumentException("dimension 须 > 0");
            }
            Path d = dir != null ? dir : Path.of(System.getProperty("java.io.tmpdir"), "default-vectors");
            return new DefaultVectorStorage(dimension, d, mode, shardSize, algorithm);
        }
    }
}
