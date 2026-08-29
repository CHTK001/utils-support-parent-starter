package com.chua.common.support.datasource.wal;

import com.chua.common.support.wal.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLong;

/**
 * KV 存储引擎。
 */
public class KvWalStoreSystem implements WalStoreSystem<String> {

    private final WalStoreConfig config;
    private final SegmentWalLog[] walLogs;
    private final ShardedIndex index = new ShardedIndex(1);
    private final AtomicLong totalRecords = new AtomicLong(0);
    private volatile boolean closed = false;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r,"kv-compact"); t.setDaemon(true); return t; });

    public KvWalStoreSystem(WalStoreConfig config) throws IOException {
        this.config = config;
        this.walLogs = new SegmentWalLog[config.shardCount()];
        Path walDir = config.baseDir().resolve("_wal");
        Files.createDirectories(walDir);
        for (int i = 0; i < config.shardCount(); i++) {
            WalConfig c = WalConfig.builder().walDir(walDir).namespace(config.namespace()+"-"+i)
                    .impl(WalConfig.WalImpl.SEGMENT).syncOnWrite(false)
                    .fsyncBatchSize(config.flushBatchSize()).fsyncBatchIntervalMs(config.flushIntervalMs())
                    .maxSegmentBytes(config.segmentBytes()).maxRecordsPerSegment(0).build();
            walLogs[i] = (SegmentWalLog) WalFactory.open(c);
        }
    }

    @Override public String type() { return "kv"; }
    @Override public Path baseDir() { return config.baseDir(); }
    @Override public int shardCount() { return config.shardCount(); }
    @Override public StoreType storeType() { return StoreType.KV; }

    @Override
    public long append(String key, byte[] payload) throws IOException {
        if (closed) throw new IllegalStateException("closed");
        int idx = Math.abs(key.hashCode()) % config.shardCount();
        long lsn = walLogs[idx].append((byte) 0x01, payload == null ? new byte[0] : payload.clone());
        totalRecords.incrementAndGet();
        return lsn;
    }

    @Override
    public Optional<byte[]> get(String key) throws IOException {
        return Optional.empty(); // index-based lookup in future
    }

    @Override
    public boolean contains(String key) { return false; }

    @Override
    public List<Map.Entry<String, byte[]>> range(String from, String to) throws IOException {
        return range(from, to, 0, Integer.MAX_VALUE);
    }

    @Override
    public List<Map.Entry<String, byte[]>> range(String from, String to, int offset, int limit) throws IOException {
        return Collections.emptyList();
    }

    @Override
    public boolean delete(String key) throws IOException {
        return false;
    }

    @Override
    public void rebuildIndex() throws IOException {}

    @Override
    public void compact() throws IOException {
        for (SegmentWalLog log : walLogs) { log.purgeCheckpointed(1); }
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
        scheduler.shutdownNow();
        for (SegmentWalLog log : walLogs) { try { log.close(); } catch (IOException ignored) {} }
    }

    // ==================== KV 专用 ====================

    public long put(String key, byte[] value) throws IOException {
        byte[] kb = key.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(4 + kb.length + 4 + (value == null ? 0 : value.length));
        bb.putInt(kb.length); bb.put(kb);
        bb.putInt(value == null ? 0 : value.length);
        if (value != null) bb.put(value);
        return append(key, bb.array());
    }

    public Optional<byte[]> getBytes(String key) throws IOException {
        for (SegmentWalLog log : walLogs) {
            for (WalSegmentInfo seg : log.listSegments()) {
                final boolean[] found = {false};
                final byte[][] result = {null};
                log.replay(seg.firstLsn(), seg.lastLsn() + 1, (lsn, op, payload) -> {
                    if ((op & 0x80) != 0) return true;
                    if (payload.length >= 4) {
                        int klen = ByteBuffer.wrap(payload).getInt();
                        if (klen > 0 && klen + 4 <= payload.length) {
                            String k = new String(payload, 4, klen, StandardCharsets.UTF_8);
                            if (k.equals(key)) {
                                result[0] = Arrays.copyOfRange(payload, klen + 8, payload.length);
                                found[0] = true;
                                return false;
                            }
                        }
                    }
                    return true;
                });
                if (found[0]) return Optional.of(result[0]);
            }
        }
        return Optional.empty();
    }

    public static KvWalStoreSystem create(Path baseDir) throws IOException {
        return new KvWalStoreSystem(new WalStoreEnvDetector().detect(baseDir));
    }
}
