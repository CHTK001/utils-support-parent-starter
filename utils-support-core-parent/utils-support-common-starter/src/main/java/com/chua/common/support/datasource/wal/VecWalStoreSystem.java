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
    @Override
    public List<Map.Entry<String, byte[]>> range(String from, String to) throws IOException { return Collections.emptyList(); }
    @Override
    public List<Map.Entry<String, byte[]>> range(String from, String to, int offset, int limit) throws IOException { return Collections.emptyList(); }
    @Override
    public boolean delete(String key) throws IOException { return false; }
    @Override
    public void rebuildIndex() throws IOException {}
    @Override
    public void compact() throws IOException {
        for (SegmentWalLog log : walLogs) {
            try {
                log.purgeCheckpointed(1);
            } catch (Exception e) {
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
        for (SegmentWalLog log : walLogs) { try { log.close(); } catch (IOException ignored) {} }
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
