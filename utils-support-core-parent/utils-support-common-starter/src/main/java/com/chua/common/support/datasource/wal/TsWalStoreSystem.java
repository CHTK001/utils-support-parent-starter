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
        if (closed) throw new IllegalStateException("closed");
        int idx = Math.abs(key.hashCode()) % config.shardCount();
        long lsn = walLogs[idx].append((byte) 0x02, payload == null ? new byte[0] : payload);
        totalRecords.incrementAndGet();
        return lsn;
    }

    @Override
    public Optional<byte[]> get(String key) throws IOException { return Optional.empty(); }
    @Override
    public boolean contains(String key) { return false; }
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
            try { log.purgeCheckpointed(1); } catch (Exception e) {}
        }
    }
    @Override
    public int size() { return (int) totalRecords.get(); }
    @Override
    public List<WalSegmentInfo> listSegments() throws IOException {
        List<WalSegmentInfo> all = new ArrayList<>();
        for (SegmentWalLog log : walLogs) all.addAll(log.listSegments());
        return all;
    }
    @Override
    public void close() throws IOException {
        closed = true;
        for (SegmentWalLog log : walLogs) { try { log.close(); } catch (IOException ignored) {} }
    }

    // ==================== TS 专用 ====================

    public long append(String measure, long ts, double value) throws IOException {
        byte[] kb = measure.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[4 + kb.length + 8 + 8];
        ByteBuffer bb = ByteBuffer.wrap(payload);
        bb.putInt(kb.length); bb.put(kb); bb.putLong(ts); bb.putDouble(value);
        return append(measure, payload);
    }

    public long appendWithTtl(String measure, long ts, double value, int ttlSec) throws IOException {
        byte[] kb = measure.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[4 + kb.length + 8 + 8 + 4];
        ByteBuffer bb = ByteBuffer.wrap(payload);
        bb.putInt(kb.length); bb.put(kb); bb.putLong(ts); bb.putDouble(value); bb.putInt(ttlSec);
        return append(measure, payload);
    }

    public List<TsPoint> queryRange(String measure, long fromTs, long toTs, int offset, int limit) throws IOException {
        List<TsPoint> result = new ArrayList<>();
        int idx = Math.abs(measure.hashCode()) % config.shardCount();
        for (WalSegmentInfo seg : walLogs[idx].listSegments()) {
            walLogs[idx].replay(seg.firstLsn(), seg.lastLsn() + 1, (lsn, op, payload) -> {
                if ((op & 0x80) != 0) return true;
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

    public Optional<TsPoint> latest(String measure) throws IOException {
        List<TsPoint> pts = queryRange(measure, Long.MIN_VALUE, Long.MAX_VALUE, 0, 1);
        return pts.isEmpty() ? Optional.empty() : Optional.of(pts.getFirst());
    }

    private TsPoint decodeTs(byte[] payload) {
        if (payload == null || payload.length < 20) return null;
        int pos = 0;
        int keyLen = ByteBuffer.wrap(payload, pos, 4).getInt(); pos += 4;
        if (keyLen <= 0 || pos + keyLen > payload.length) return null;
        String measure = new String(payload, pos, keyLen, StandardCharsets.UTF_8);
        pos += keyLen;
        if (pos + 16 > payload.length) return null;
        long ts = ByteBuffer.wrap(payload, pos, 8).getLong(); pos += 8;
        double value = ByteBuffer.wrap(payload, pos, 8).getDouble(); pos += 8;
        Integer ttlSec = pos + 4 <= payload.length ? ByteBuffer.wrap(payload, pos, 4).getInt() : null;
        return new TsPoint(measure, ts, value, ttlSec);
    }

    @Override
    public void appendBatch(List<WalStoreSystem.WalAppendItem<String>> items) throws IOException {
        for (WalStoreSystem.WalAppendItem<String> item : items) {
            append(item.key(), item.payload());
        }
    }

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

    public TsAggregate aggregate(String measure, long fromTs, long toTs, AggregateFn fn) throws IOException {
        List<TsPoint> pts = queryRange(measure, fromTs, toTs, 0, Integer.MAX_VALUE);
        if (pts.isEmpty()) return TsAggregate.empty(measure, fromTs, toTs);
        double sum = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (TsPoint p : pts) {
            sum += p.value();
            if (p.value() < min) min = p.value();
            if (p.value() > max) max = p.value();
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

    public static TsWalStoreSystem create(Path baseDir) throws IOException {
        return new TsWalStoreSystem(new WalStoreEnvDetector().detect(baseDir));
    }
}
