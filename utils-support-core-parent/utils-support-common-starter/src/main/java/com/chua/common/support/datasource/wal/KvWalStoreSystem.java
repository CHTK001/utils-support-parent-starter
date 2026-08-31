package com.chua.common.support.datasource.wal;

import com.chua.common.support.utils.ThreadUtils;
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
            ThreadUtils.newDaemonSingleThreadScheduledExecutor("kv-compact");

    public KvWalStoreSystem(WalStoreConfig config) throws IOException {
        this.config = config;
        this.walLogs = new SegmentWalLog[config.shardCount()];
        Path walDir = config.baseDir().resolve("_wal");
        Files.createDirectories(walDir);
        for (int i = 0; i < config.shardCount(); i++) {
            WalConfig c = WalConfig.builder().walDir(walDir).namespace(config.namespace()+"-"+i)
                    .impl(WalConfig.WalImpl.SEGMENT).syncOnWrite(false)
                    .fsyncBatchSize(config.flushBatchSize()).fsyncBatchIntervalMs(config.flushIntervalMs())
                    .maxSegmentBytes(config.segmentBytes()).maxRecordsPerSegment(100_000).build();
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
        long lsn = walLogs[idx].append((byte) 0x01, payload == null ? new byte[0] : payload);
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
        byte[] payload = KvWalFileSystem.encode(key, new byte[0]);
        append(key, payload);
        return true;
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

    /** 可复用写缓冲，最大 key=128B + value=512B + 2个int长度头 = ~644B，对齐到 1024 */
    private static final int KV_WRITE_BUF_SIZE = 1024;
    private byte[] writeBuf = new byte[KV_WRITE_BUF_SIZE];

    public long put(String key, byte[] value) throws IOException {
        byte[] kb = key.getBytes(StandardCharsets.UTF_8);
        int vlen = value == null ? 0 : value.length;
        int total = 4 + kb.length + 4 + vlen;
        if (writeBuf.length < total) writeBuf = new byte[Math.max(total * 2, KV_WRITE_BUF_SIZE)];
        ByteBuffer.wrap(writeBuf, 0, total).putInt(kb.length).put(kb).putInt(vlen);
        if (value != null) System.arraycopy(value, 0, writeBuf, 4 + kb.length + 4, vlen);
        return append(key, writeBuf);
    }

    /** 快速写入：key bytes 已由调用方预分配，避免循环中重复创建字符串 */
    public long putFast(byte[] key, byte[] value) throws IOException {
        int vlen = value == null ? 0 : value.length;
        int total = 4 + key.length + 4 + vlen;
        if (writeBuf.length < total) writeBuf = new byte[Math.max(total * 2, KV_WRITE_BUF_SIZE)];
        ByteBuffer.wrap(writeBuf, 0, total).putInt(key.length).put(key).putInt(vlen);
        if (value != null) System.arraycopy(value, 0, writeBuf, 4 + key.length + 4, vlen);
        int idx = fnv1aHash(key) % config.shardCount();
        if (idx < 0) idx += config.shardCount();
        return walLogs[idx].append((byte) 0x01, writeBuf);
    }

    private static int fnv1aHash(byte[] data) {
        int h = 0x811c9dc5;
        for (byte b : data) h = (h ^ b) * 0x01000193;
        return h;
    }

    public Optional<byte[]> getBytes(String key) throws IOException {
        final byte[][] result = {null};
        for (SegmentWalLog log : walLogs) {
            for (WalSegmentInfo seg : log.listSegments()) {
                log.replay(seg.firstLsn(), seg.lastLsn() + 1, (lsn, op, payload) -> {
                    if ((op & 0x80) != 0) return true;
                    if (payload.length >= 8) {
                        int klen = ByteBuffer.wrap(payload).getInt();
                        if (klen > 0 && klen + 8 <= payload.length) {
                            String k = new String(payload, 4, klen, StandardCharsets.UTF_8);
                            int vlen = ByteBuffer.wrap(payload, klen + 4, 4).getInt();
                            if (k.equals(key)) {
                                result[0] = Arrays.copyOfRange(payload, klen + 8, klen + 8 + vlen);
                            }
                        }
                    }
                    return true;
                });
            }
        }
        return result[0] == null ? Optional.empty() : Optional.of(result[0]);
    }

    /** 供测试访问内部 walLogs，生产环境不应暴露 */
    SegmentWalLog[] getWalLogs() { return walLogs; }

    public static KvWalStoreSystem create(Path baseDir) throws IOException {
        return new KvWalStoreSystem(new WalStoreEnvDetector().detect(baseDir));
    }
}
