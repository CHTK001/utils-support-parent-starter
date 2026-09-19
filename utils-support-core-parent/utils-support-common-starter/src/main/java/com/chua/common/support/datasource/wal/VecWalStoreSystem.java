package com.chua.common.support.datasource.wal;

import com.chua.common.support.wal.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 向量存储引擎（基础版）。
 */
public class VecWalStoreSystem implements WalStoreSystem<String> {

    private final WalStoreConfig config;
    private final int dimension;
    private final SegmentWalLog[] walLogs;
    private final AtomicLong totalRecords = new AtomicLong(0);
    private volatile boolean closed = false;

    /**
     * 构造方法，创建 VecWalStoreSystem 实例。
     *
     * @param config 配置，不允许为 null
     * @param dimension 方法入参 dimension
     * @throws IOException 当执行过程不满足前置条件时
     */
    public VecWalStoreSystem(WalStoreConfig config, int dimension) throws IOException {
        this.config = config;
        this.dimension = dimension;
        this.walLogs = new SegmentWalLog[config.shardCount()];
        Path walDir = config.baseDir().resolve("_wal");
        Files.createDirectories(walDir);
        for (int i = 0; i < config.shardCount(); i++) {
            WalConfig c = WalConfig.builder().walDir(walDir).namespace(config.namespace()+"-"+i)
                    .impl(WalConfig.WalImpl.SEGMENT).syncOnWrite(false)
                    .fsyncBatchSize(config.flushBatchSize()).fsyncBatchIntervalMs(config.flushIntervalMs())
                    .maxSegmentBytes(config.segmentBytes()).maxRecordsPerSegment(10_000_000).build();
            walLogs[i] = (SegmentWalLog) WalFactory.open(c);
        }
    }

    @Override public String type() { return "vec"; }
    @Override public Path baseDir() { return config.baseDir(); }
    @Override public int shardCount() { return config.shardCount(); }
    @Override public StoreType storeType() { return StoreType.VEC; }

    @Override
    public long append(String key, byte[] payload) throws IOException {
        if (closed) {
            throw new IllegalStateException("closed");
        }
        int idx = Math.abs(key.hashCode()) % config.shardCount();
        long lsn = walLogs[idx].append((byte) 0x03, payload == null ? new byte[0] : payload);
        totalRecords.incrementAndGet();
        return lsn;
    }

    @Override
    public Optional<byte[]> get(String key) throws IOException { return Optional.empty(); }
    @Override public boolean contains(String key) { return false; }

    /**
     * 范围查询：按向量 id 升序返回 [from, to) 内的所有记录。
     *
     * @param from 下界（含）
     * @param to 上界（不含）
     * @return 条目列表
     * @throws IOException 当回放过程失败时
     */
    @Override
    public List<Map.Entry<String, byte[]>> range(String from, String to) throws IOException {
        return range(from, to, 0, Integer.MAX_VALUE);
    }

    /**
     * 带分页的范围查询：回放全部分段，从载荷中解出向量 id 作为 key。
     *
     * <p>载荷无法解码为向量记录时抛异常，不返回残缺列表。</p>
     *
     * @param from 下界（含）
     * @param to 上界（不含）
     * @param offset 偏移量
     * @param limit 上限
     * @return 条目列表
     * @throws IOException 当回放过程失败时
     */
    @Override
    public List<Map.Entry<String, byte[]>> range(String from, String to, int offset, int limit) throws IOException {
        List<Map.Entry<String, byte[]>> entries = new ArrayList<>();
        for (int i = 0; i < walLogs.length; i++) {
            collectKeyRange(i, from, to, entries);
        }
        entries.sort(Map.Entry.comparingByKey());
        int begin = Math.min(Math.max(offset, 0), entries.size());
        int end = limit <= 0 || (long) begin + limit > entries.size() ? entries.size() : begin + limit;
        return new ArrayList<>(entries.subList(begin, end));
    }

    /**
     * 收集单个分片中 key 落在 [from, to) 内的记录。
     *
     * @param shardIdx 分片索引
     * @param from 下界（含）
     * @param to 上界（不含）
     * @param entries 收集结果，不允许为 null
     * @throws IOException 当回放过程失败时
     */
    private void collectKeyRange(int shardIdx, String from, String to,
                                 List<Map.Entry<String, byte[]>> entries) throws IOException {
        SegmentWalLog log = walLogs[shardIdx];
        for (WalSegmentInfo seg : log.listSegments()) {
            try {
                log.replay(seg.firstLsn(), seg.lastLsn() + 1, (lsn, op, payload) -> {
                    if ((op & AbstractWalFileSystem.OP_TOMBSTONE) != 0) {
                        return true;
                    }
                    VecWalFileSystem.VecRecord record = VecWalFileSystem.decode(payload).orElse(null);
                    if (record == null) {
                        throw new WalException("WAL 记录无法解码为向量: 分段=" + seg.path() + ", lsn=" + lsn);
                    }
                    String id = record.id();
                    if ((from == null || id.compareTo(from) >= 0) && (to == null || id.compareTo(to) < 0)) {
                        entries.add(Map.entry(id, payload));
                    }
                    return true;
                });
            } catch (IOException | RuntimeException e) {
                throw new WalException("WAL 范围查询失败: 分片=" + config.namespace() + "-" + shardIdx
                        + ", 分段=" + seg.path(), e);
            }
        }
    }

    /**
     * 逻辑删除。
     *
     * @param key 键，不允许为 null
     * @return 不返回
     * @throws IOException 当执行过程不满足前置条件时
     */
    @Override
    public boolean delete(String key) throws IOException {
        throw new UnsupportedOperationException("VEC 向量存储为追加式且无 id 索引，墓碑不携带 id 因而无法屏蔽已有向量记录，"
                + "不支持按键删除；按相似度检索请使用 VecWalStoreSystem.search，"
                + "需要按键删除与范围查请使用 KvWalStoreSystem");
    }

    /**
     * 重建索引：全量回放 WAL，重算有效记录数。
     *
     * <p>任一分段回放失败都会包装为 {@link WalException} 抛出，避免把残缺计数当作重建成功。</p>
     *
     * @throws IOException 当回放过程失败时
     */
    @Override
    public void rebuildIndex() throws IOException {
        long alive = 0;
        for (int i = 0; i < walLogs.length; i++) {
            alive += countAliveRecords(i);
        }
        totalRecords.set(alive);
    }

    /**
     * 统计单个分片内未被墓碑覆盖的记录数。
     *
     * @param shardIdx 分片索引
     * @return 有效记录数
     * @throws IOException 当回放过程失败时
     */
    private long countAliveRecords(int shardIdx) throws IOException {
        SegmentWalLog log = walLogs[shardIdx];
        long[] counter = new long[1];
        for (WalSegmentInfo seg : log.listSegments()) {
            try {
                log.replay(seg.firstLsn(), seg.lastLsn() + 1, (lsn, op, payload) -> {
                    if ((op & AbstractWalFileSystem.OP_TOMBSTONE) == 0) {
                        counter[0]++;
                    }
                    return true;
                });
            } catch (IOException | RuntimeException e) {
                throw new WalException("WAL 索引重建失败: 分片=" + config.namespace() + "-" + shardIdx
                        + ", 分段=" + seg.path(), e);
            }
        }
        return counter[0];
    }

    /**
     * 压缩：清理已 checkpoint 覆盖的历史分段。
     *
     * @throws IOException 当清理过程失败时
     */
    @Override
    public void compact() throws IOException {
        for (int i = 0; i < walLogs.length; i++) {
            try {
                walLogs[i].purgeCheckpointed(1);
            } catch (RuntimeException e) {
                throw new WalException("WAL 压缩清理失败: 分片=" + config.namespace() + "-" + i, e);
            }
        }
    }
    @Override
    public int size() { return (int) totalRecords.get(); }
    @Override
    public List<WalSegmentInfo> listSegments() throws IOException {
        List<WalSegmentInfo> all = new ArrayList<>();
        for (SegmentWalLog log : walLogs) {
            all.addAll(log.listSegments());
        }
        return all;
    }
    @Override
    public void close() throws IOException {
        closed = true;
        IOException first = null;
        for (SegmentWalLog walLog : walLogs) {
            try {
                walLog.close();
            } catch (IOException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    // ==================== VEC 专用 ====================

    /**
     * 添加。
     *
     * @param id ID，不允许为 null
     * @param data 数据，不允许为 null
     * @return 结果数值
     * @throws IOException 当执行过程不满足前置条件时
     */
    public long add(String id, float[] data) throws IOException {
        if (data == null || data.length != dimension)
            throw new IllegalArgumentException("维度不匹配: 期望 " + dimension + ", 实际 " + data.length);
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[4 + idBytes.length + 4 + dimension * 4];
        ByteBuffer bb = ByteBuffer.wrap(payload);
        bb.putInt(idBytes.length);
        bb.put(idBytes);
        bb.putInt(dimension);
        bb.asFloatBuffer().put(data);
        return append(id, payload);
    }

    /**
     * 获取Vector。
     *
     * @param id ID，不允许为 null
     * @return 可选结果，不存在时为 Optional.empty()
     * @throws IOException 当执行过程不满足前置条件时
     */
    public Optional<float[]> getVector(String id) throws IOException {
        for (SegmentWalLog log : walLogs) {
            for (WalSegmentInfo seg : log.listSegments()) {
                final boolean[] found = {false};
                final float[][] result = {null};
                log.replay(seg.firstLsn(), seg.lastLsn() + 1, (lsn, op, payload) -> {
                    if ((op & 0x80) != 0) {
                        return true;
                    }
                    VecWalFileSystem.VecRecord rec = VecWalFileSystem.decode(payload).orElse(null);
                    if (rec != null && rec.id().equals(id)) {
                        result[0] = new float[]{rec.data()[0]}; // placeholder
                        result[0] = rec.data();
                        found[0] = true;
                        return false;
                    }
                    return true;
                });
                if (found[0]) {
                    return Optional.of(result[0]);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 搜索。
     *
     * @param query 查询，不允许为 null
     * @param topK 顶部K，不允许为 null
     * @return 结果列表，无数据时为空列表
     * @throws IOException 当执行过程不满足前置条件时
     */
    public List<VectorScored> search(float[] query, int topK) throws IOException {
        // 简化：暴力扫描所有段
        List<VectorScored> candidates = new ArrayList<>();
        for (SegmentWalLog log : walLogs) {
            for (WalSegmentInfo seg : log.listSegments()) {
                log.replay(seg.firstLsn(), seg.lastLsn() + 1, (lsn, op, payload) -> {
                    if ((op & 0x80) != 0) {
                        return true;
                    }
                    VecWalFileSystem.VecRecord rec = VecWalFileSystem.decode(payload).orElse(null);
                    if (rec != null) {
                        float sim = cosineSim(query, rec.data());
                        candidates.add(new VectorScored(rec.id(), sim, rec.data()));
                    }
                    return true;
                });
            }
        }
        if (candidates.size() <= topK) {
            return candidates;
        }
        PriorityQueue<VectorScored> heap = new PriorityQueue<>(topK, (a, b) -> Float.compare(a.score(), b.score()));
        for (VectorScored vs : candidates) {
            if (heap.size() < topK) {
                heap.add(vs);
            }
            else if (vs.score() > heap.peek().score()) { heap.poll(); heap.add(vs); }
        }
        List<VectorScored> r = new ArrayList<>(heap);
        r.sort((a, b) -> Float.compare(b.score(), a.score()));
        return r;
    }

    /**
     * cosineSim。
     *
     * @param a 方法入参 a
     * @param b 方法入参 b
     * @return 结果数值
     */
    private static float cosineSim(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i]*b[i]; na += a[i]*a[i]; nb += b[i]*b[i]; }
        double d = Math.sqrt(na)*Math.sqrt(nb);
        return d == 0 ? 0f : (float)(dot/d);
    }

    @Override
    public void appendBatch(List<WalStoreSystem.WalAppendItem<String>> items) throws IOException {
        for (WalStoreSystem.WalAppendItem<String> item : items) {
            append(item.key(), item.payload());
        }
    }

    public record VectorScored(String id, float score, float[] data) {}

    /**
     * 创建。
     *
     * @param baseDir base目录，不允许为 null
     * @param dimension 方法入参 dimension
     * @return VecWalStoreSystem 对象
     * @throws IOException 当执行过程不满足前置条件时
     */
    public static VecWalStoreSystem create(Path baseDir, int dimension) throws IOException {
        return new VecWalStoreSystem(new WalStoreEnvDetector().detect(baseDir), dimension);
    }
}
