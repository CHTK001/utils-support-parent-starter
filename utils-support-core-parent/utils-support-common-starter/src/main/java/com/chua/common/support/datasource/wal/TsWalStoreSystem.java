package com.chua.common.support.datasource.wal;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.wal.*;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 时序存储引擎，亿级数据分片架构。
 */
@Slf4j
@Spi("wal-ts-store")
public class TsWalStoreSystem extends AbstractWalStoreSystem<String> {

    public TsWalStoreSystem(WalStoreConfig config) throws IOException {
        super(config);
    }

    @Override
    protected byte opType() { return 0x02; }

    @Override
    protected String decodeKey(byte[] payload) {
        if (payload == null || payload.length < 4) return null;
        int keyLen = ByteBuffer.wrap(payload).getInt();
        if (keyLen <= 0 || keyLen > payload.length - 4) return null;
        return new String(payload, 4, keyLen, StandardCharsets.UTF_8);
    }

    @Override
    protected Object decodeValue(String measure, byte[] payload) {
        if (payload == null || payload.length < 20) return null;
        ByteBuffer bb = ByteBuffer.wrap(payload);
        bb.getInt(); bb.getInt(); // skip keyLen + key
        long ts = bb.getLong();
        double value = bb.getDouble();
        Integer ttlSec = bb.remaining() >= 4 ? bb.getInt() : null;
        return new TsPoint(measure, ts, value, ttlSec);
    }

    @Override
    public int shardCount() { return config.shardCount(); }

    // ==================== TS 专用写入 ====================

    public long append(String measure, long ts, double value) throws IOException {
        return append(measure, encodePayload(measure, ts, value, 0));
    }

    public long appendWithTtl(String measure, long ts, double value, int ttlSec) throws IOException {
        return append(measure, encodePayload(measure, ts, value, ttlSec));
    }

    public void appendBatch(List<TsBatchItem> items) throws IOException {
        for (TsBatchItem item : items) {
            append(item.measure(), item.ts(), item.value(), item.ttlSec());
        }
    }

    // ==================== TS 专用查询 ====================

    public List<TsPoint> queryRange(String measure, long fromTs, long toTs) throws IOException {
        return queryRange(measure, fromTs, toTs, 0, Integer.MAX_VALUE);
    }

    public List<TsPoint> queryRange(String measure, long fromTs, long toTs, int offset, int limit)
            throws IOException {
        List<TsPoint> allPoints = new ArrayList<>();
        for (WalSegmentInfo seg : listSegmentsForMeasure(measure)) {
            final List<TsPoint> local = allPoints;
            walLogOfSeg(seg).replay(seg.firstLsn(), seg.lastLsn() + 1, (lsn, op, payload) -> {
                if (isTombstone(op)) return true;
                String km = decodeKey(payload);
                if (!measure.equals(km)) return true;
                TsPoint p = decodeTsPayload(payload);
                if (p != null && p.ts() >= fromTs && p.ts() < toTs) local.add(p);
                return true;
            });
        }
        allPoints.sort(Comparator.comparingLong(TsPoint::ts));
        int fromIdx = Math.min(offset, allPoints.size());
        int toIdx = Math.min(offset + limit, allPoints.size());
        return allPoints.subList(fromIdx, toIdx);
    }

    public TsAggregate aggregate(String measure, long fromTs, long toTs, AggregateFn fn) throws IOException {
        List<TsPoint> points = queryRange(measure, fromTs, toTs);
        return fn.apply(points);
    }

    public Optional<TsPoint> latest(String measure) throws IOException {
        List<TsPoint> latest = queryRange(measure, Long.MIN_VALUE, Long.MAX_VALUE, 0, 1);
        return latest.isEmpty() ? Optional.empty() : Optional.of(latest.get(0));
    }

    public List<String> listMeasures() throws IOException {
        Set<String> measures = new HashSet<>();
        for (int i = 0; i < config.shardCount(); i++) {
            for (WalSegmentInfo seg : walLogs[i].listSegments()) {
                walLogs[i].replay(seg.firstLsn(), seg.lastLsn() + 1, (lsn, op, payload) -> {
                    if (!isTombstone(op)) {
                        String k = decodeKey(payload);
                        if (k != null) measures.add(k);
                    }
                    return true;
                });
            }
        }
        return new ArrayList<>(measures);
    }

    // ==================== 后台 TTL ====================

    public void startTtlScanner(long intervalSeconds) {
        compactScheduler.scheduleAtFixedRate(() -> {
            try { scanAndMarkExpired(); } catch (Exception e) {
                log.warn("[ts-store] TTL scan failed: {}", e.getMessage());
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private void scanAndMarkExpired() throws IOException {
        long now = System.currentTimeMillis();
        int expired = 0;
        for (int i = 0; i < config.shardCount(); i++) {
            for (WalSegmentInfo seg : walLogs[i].listSegments()) {
                final int si = i;
                walLogs[i].replay(seg.firstLsn(), seg.lastLsn() + 1, (lsn, op, payload) -> {
                    if (isTombstone(op)) return true;
                    TsPoint p = decodeTsPayload(payload);
                    if (p != null && p.ttlSec() != null && p.expireAt() <= now) {
                        walLogs[si].append((byte) (opType() | AbstractWalFileSystem.OP_TOMBSTONE), new byte[0]);
                        expired++;
                    }
                    return true;
                });
            }
        }
        if (expired > 0) { log.info("[ts-store] TTL scan: {} expired", expired); rebuildIndex(); }
    }

    // ==================== 内部工具 ====================

    private byte[] encodePayload(String measure, long ts, double value, int ttlSec) {
        byte[] kb = measure.getBytes(StandardCharsets.UTF_8);
        int total = 4 + kb.length + 8 + 8 + (ttlSec > 0 ? 4 : 0);
        ByteBuffer bb = ByteBuffer.allocate(total);
        bb.putInt(kb.length); bb.put(kb); bb.putLong(ts); bb.putDouble(value);
        if (ttlSec > 0) bb.putInt(ttlSec);
        return bb.array();
    }

    private TsPoint decodeTsPayload(byte[] payload) {
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

    private List<WalSegmentInfo> listSegmentsForMeasure(String measure) throws IOException {
        int shardIdx = router.shardOf(measure);
        return walLogs[shardIdx].listSegments();
    }

    private SegmentWalLog walLogOfSeg(WalSegmentInfo seg) {
        int shardIdx = router.shardOf(seg.path().getFileName().toString());
        return walLogs[Math.abs(shardIdx) % config.shardCount()];
    }

    @Override
    public StoreType storeType() { return StoreType.TS; }

    public record TsPoint(String measure, long ts, double value, Integer ttlSec) {
        public long expireAt() {
            return ttlSec == null ? Long.MAX_VALUE : ts + (long) ttlSec * 1000L;
        }
    }

    public record TsBatchItem(String measure, long ts, double value, int ttlSec) {
        public static TsBatchItem of(String m, long t, double v) { return new TsBatchItem(m, t, v, 0); }
    }

    public enum AggregateFn {
        AVG, MIN, MAX, COUNT, SUM;

        public TsAggregate apply(List<TsPoint> pts) {
            if (pts.isEmpty()) return TsAggregate.empty("", 0, 0);
            String m = pts.get(0).measure();
            long n = pts.size();
            double sum = pts.stream().mapToDouble(TsPoint::value).sum();
            return switch (this) {
                case AVG  -> new TsAggregate(m, 0, 0, sum / n, Double.NaN, Double.NaN, n, sum);
                case MIN  -> new TsAggregate(m, 0, 0, Double.NaN,
                        pts.stream().mapToDouble(TsPoint::value).min().orElse(0), Double.NaN, n, sum);
                case MAX  -> new TsAggregate(m, 0, 0, Double.NaN,
                        pts.stream().mapToDouble(TsPoint::value).max().orElse(0), Double.NaN, n, sum);
                case COUNT -> new TsAggregate(m, 0, 0, Double.NaN, Double.NaN, Double.NaN, n, 0);
                case SUM  -> new TsAggregate(m, 0, 0, Double.NaN, Double.NaN, Double.NaN, n, sum);
            };
        }
    }

    public record TsAggregate(String measure, long fromTs, long toTs,
                              double avg, double min, double max, long count, double sum) {
        public static TsAggregate empty(String m, long f, long t) {
            return new TsAggregate(m, f, t, Double.NaN, Double.NaN, Double.NaN, 0, 0.0);
        }
    }

    public static TsWalStoreSystem create(Path baseDir) throws IOException {
        return new TsWalStoreSystem(new WalStoreEnvDetector().detect(baseDir));
    }

    public static TsWalStoreSystem create(Path baseDir, String namespace) throws IOException {
        return new TsWalStoreSystem(new WalStoreEnvDetector().detect(baseDir, namespace));
    }
}
