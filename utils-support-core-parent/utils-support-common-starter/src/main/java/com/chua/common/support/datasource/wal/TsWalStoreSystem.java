package com.chua.common.support.datasource.wal;

import com.chua.common.support.wal.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TS 时序存储引擎。
 */
public class TsWalStoreSystem implements WalStoreSystem<String> {

    private final WalStoreConfig config;
    private final SegmentWalLog[] walLogs;
    private final AtomicLong totalRecords = new AtomicLong(0);
    private volatile boolean closed = false;

    /**
     * 构造方法，创建 TsWalStoreSystem 实例。
     *
     * @param config 配置，不允许为 null
     * @throws IOException 当执行过程不满足前置条件时
     */
    public TsWalStoreSystem(WalStoreConfig config) throws IOException {
        this.config = config;
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

    @Override public String type() { return "ts"; }
    @Override public Path baseDir() { return config.baseDir(); }
    @Override public int shardCount() { return config.shardCount(); }
    @Override public StoreType storeType() { return StoreType.TS; }

    @Override
    public long append(String key, byte[] payload) throws IOException {
        if (closed) {
            throw new IllegalStateException("closed");
        }
        int idx = Math.abs(key.hashCode()) % config.shardCount();
        long lsn = walLogs[idx].append((byte) 0x02, payload == null ? new byte[0] : payload);
        totalRecords.incrementAndGet();
        return lsn;
    }

    @Override
    public Optional<byte[]> get(String key) throws IOException { return Optional.empty(); }
    @Override
    public boolean contains(String key) { return false; }

    /**
     * 范围查询：按 key（即 measure）升序返回 [from, to) 内的所有记录。
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
     * 带分页的范围查询：回放全部分段，从载荷中解出 measure 作为 key。
     *
     * <p>载荷无法解码为时序点时抛异常，不返回残缺列表。</p>
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
        int end = limit <= 0 || (long) begin + limit > entries.size()
                ? entries.size() : begin + limit;
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
                    TsPoint point = decodeTs(payload);
                    if (point == null) {
                        throw new WalException("WAL 记录无法解码为时序点: 分段=" + seg.path() + ", lsn=" + lsn);
                    }
                    String measure = point.measure();
                    if ((from == null || measure.compareTo(from) >= 0)
                            && (to == null || measure.compareTo(to) < 0)) {
                        entries.add(Map.entry(measure, payload));
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
        throw new UnsupportedOperationException("TS 时序存储为追加式，段内数据不可改写，墓碑也不携带 measure 因而无法屏蔽已有时序点，"
                + "不支持按键删除；按 measure 与时间窗查询请使用 TsWalStoreSystem.queryRange，"
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

    // ==================== TS 专用 ====================

    /**
     * 追加。
     *
     * @param measure 方法入参 measure
     * @param ts 方法入参 ts
     * @param value 值，不允许为 null
     * @return 结果数值
     * @throws IOException 当执行过程不满足前置条件时
     */
    public long append(String measure, long ts, double value) throws IOException {
        byte[] kb = measure.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[4 + kb.length + 8 + 8];
        ByteBuffer bb = ByteBuffer.wrap(payload);
        bb.putInt(kb.length);
        bb.put(kb);
        bb.putLong(ts);
        bb.putDouble(value);
        return append(measure, payload);
    }

    /**
     * 追加WithTtl。
     *
     * @param measure 方法入参 measure
     * @param ts 方法入参 ts
     * @param value 值，不允许为 null
     * @param ttlSec 方法入参 ttlSec
     * @return 结果数值
     * @throws IOException 当执行过程不满足前置条件时
     */
    public long appendWithTtl(String measure, long ts, double value, int ttlSec) throws IOException {
        byte[] kb = measure.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[4 + kb.length + 8 + 8 + 4];
        ByteBuffer bb = ByteBuffer.wrap(payload);
        bb.putInt(kb.length);
        bb.put(kb);
        bb.putLong(ts);
        bb.putDouble(value);
        bb.putInt(ttlSec);
        return append(measure, payload);
    }

    /**
     * 查询Range。
     *
     * @param measure 方法入参 measure
     * @param fromTs 来自Ts，不允许为 null
     * @param toTs 转为Ts，不允许为 null
     * @param offset 偏移量，不允许为 null
     * @param limit 上限，不允许为 null
     * @return 结果列表，无数据时为空列表
     * @throws IOException 当执行过程不满足前置条件时
     */
    public List<TsPoint> queryRange(String measure, long fromTs, long toTs, int offset, int limit) throws IOException {
        List<TsPoint> result = new ArrayList<>();
        int idx = Math.abs(measure.hashCode()) % config.shardCount();
        for (WalSegmentInfo seg : walLogs[idx].listSegments()) {
            walLogs[idx].replay(seg.firstLsn(), seg.lastLsn() + 1, (lsn, op, payload) -> {
                if ((op & 0x80) != 0) {
                    return true;
                }
                TsPoint p = decodeTs(payload);
                if (p != null && p.measure().equals(measure) && p.ts() >= fromTs && p.ts() < toTs)
                    result.add(p);
                return true;
            });
        }
        result.sort(Comparator.comparingLong(TsPoint::ts));
        int from = Math.min(offset, result.size());
        int to = Math.min(offset + limit, result.size());
        return result.subList(from, to);
    }

    /**
     * latest。
     *
     * @param measure 方法入参 measure
     * @return 可选结果，不存在时为 Optional.empty()
     * @throws IOException 当执行过程不满足前置条件时
     */
    public Optional<TsPoint> latest(String measure) throws IOException {
        List<TsPoint> pts = queryRange(measure, Long.MIN_VALUE, Long.MAX_VALUE, 0, 1);
        return pts.isEmpty() ? Optional.empty() : Optional.of(pts.getFirst());
    }

    /**
     * 解码Ts。
     *
     * @param payload 方法入参 payload
     * @return TsPoint 对象
     */
    private TsPoint decodeTs(byte[] payload) {
        if (payload == null || payload.length < 20) {
            return null;
        }
        int pos = 0;
        int keyLen = ByteBuffer.wrap(payload, pos, 4).getInt();
        pos += 4;
        if (keyLen <= 0 || pos + keyLen > payload.length) {
            return null;
        }
        String measure = new String(payload, pos, keyLen, StandardCharsets.UTF_8);
        pos += keyLen;
        if (pos + 16 > payload.length) {
            return null;
        }
        long ts = ByteBuffer.wrap(payload, pos, 8).getLong();
        pos += 8;
        double value = ByteBuffer.wrap(payload, pos, 8).getDouble();
        pos += 8;
        Integer ttlSec = pos + 4 <= payload.length ? ByteBuffer.wrap(payload, pos, 4).getInt() : null;
        return new TsPoint(measure, ts, value, ttlSec);
    }

    @Override
    public void appendBatch(List<WalStoreSystem.WalAppendItem<String>> items) throws IOException {
        for (WalStoreSystem.WalAppendItem<String> item : items) {
            append(item.key(), item.payload());
        }
    }

    /**
     * TsPoint。
     *
     * @param measure 方法入参 measure
     * @param ts 方法入参 ts
     * @param value 值，不允许为 null
     * @param ttlSec 方法入参 ttlSec
     * @return 结果值
     */
    public record TsPoint(String measure, long ts, double value, Integer ttlSec) {
        public long expireAt() { return ttlSec == null ? Long.MAX_VALUE : ts + (long) ttlSec * 1000L; }
    }

    public enum AggregateFn { AVG, MIN, MAX, COUNT, SUM; }

    public record TsAggregate(String measure, long fromTs, long toTs,
                              double avg, double min, double max, long count, double sum) {
        public static TsAggregate empty(String m, long f, long t) {
            return new TsAggregate(m, f, t, Double.NaN, Double.NaN, Double.NaN, 0, 0.0);
        }
    }

    /**
     * aggregate。
     *
     * @param measure 方法入参 measure
     * @param fromTs 来自Ts，不允许为 null
     * @param toTs 转为Ts，不允许为 null
     * @param fn 函数，不允许为 null
     * @return TsAggregate 对象
     * @throws IOException 当执行过程不满足前置条件时
     */
    public TsAggregate aggregate(String measure, long fromTs, long toTs, AggregateFn fn) throws IOException {
        List<TsPoint> pts = queryRange(measure, fromTs, toTs, 0, Integer.MAX_VALUE);
        if (pts.isEmpty()) {
            return TsAggregate.empty(measure, fromTs, toTs);
        }
        double sum = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (TsPoint p : pts) {
            sum += p.value();
            if (p.value() < min) {
                min = p.value();
            }
            if (p.value() > max) {
                max = p.value();
            }
        }
        long n = pts.size();
        return switch (fn) {
            case AVG  -> new TsAggregate(measure, fromTs, toTs, sum / n, min, max, n, sum);
            case MIN  -> new TsAggregate(measure, fromTs, toTs, Double.NaN, min, Double.NaN, n, sum);
            case MAX  -> new TsAggregate(measure, fromTs, toTs, Double.NaN, Double.NaN, max, n, sum);
            case COUNT-> new TsAggregate(measure, fromTs, toTs, Double.NaN, Double.NaN, Double.NaN, n, 0);
            case SUM  -> new TsAggregate(measure, fromTs, toTs, Double.NaN, min, max, n, sum);
        };
    }

    /**
     * 创建。
     *
     * @param baseDir base目录，不允许为 null
     * @return TsWalStoreSystem 对象
     * @throws IOException 当执行过程不满足前置条件时
     */
    public static TsWalStoreSystem create(Path baseDir) throws IOException {
        return new TsWalStoreSystem(new WalStoreEnvDetector().detect(baseDir));
    }
}
