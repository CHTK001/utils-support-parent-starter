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
    private final java.util.concurrent.ConcurrentHashMap<String, byte[]> memIndex = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong totalRecords = new AtomicLong(0);
    private volatile boolean closed = false;
    private final ScheduledExecutorService scheduler =
            ThreadUtils.newDaemonSingleThreadScheduledExecutor("kv-compact");

    /**
     * 构造方法，创建 KvWalStoreSystem 实例。
     *
     * @param config 配置，不允许为 null
     * @throws IOException 当执行过程不满足前置条件时
     */
    public KvWalStoreSystem(WalStoreConfig config) throws IOException {
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
        rebuildIndex();
        if (config.flushIntervalMs() > 0) {
            scheduler.scheduleAtFixedRate(this::fsyncAll,
                    config.flushIntervalMs(), config.flushIntervalMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    /**
     * fsync全部。
     */
    private void fsyncAll() {
        for (SegmentWalLog log : walLogs) { try { log.sync(); } catch (IOException ignored) {} }
    }

    @Override public String type() { return "kv"; }
    @Override public Path baseDir() { return config.baseDir(); }
    @Override public int shardCount() { return config.shardCount(); }
    @Override public StoreType storeType() { return StoreType.KV; }

    @Override
    public long append(String key, byte[] payload) throws IOException {
        if (closed) {
            throw new IllegalStateException("closed");
        }
        int idx = shardHash(key.getBytes(StandardCharsets.UTF_8)) % config.shardCount();
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
        memIndex.remove(key);
        return true;
    }

    @Override
    public void compact() throws IOException {
        for (SegmentWalLog log : walLogs) { log.purgeCheckpointed(1); }
        rebuildIndex();
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
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        for (SegmentWalLog log : walLogs) { try { log.close(); } catch (IOException ignored) {} }
    }

    // ==================== KV 专用 ====================

    /**
     * 可复用写缓冲，最大 key=128B + value=512B + 2个int长度头 = ~644B，对齐到 1024
     */
    private static final int KV_WRITE_BUF_SIZE = 1024;
    /** 写入Buf */
    private byte[] writeBuf = new byte[KV_WRITE_BUF_SIZE];

    /**
    * FNV-1a 一致性 hash，put 和 putFast 必须使用同一算法保证路由正确
    * @param data 数据，不允许为 null
    * @return 结果数值
    */
    private static int shardHash(byte[] data) {
        int h = 0x811c9dc5;
        for (byte b : data) {
            h = (h ^ b) * 0x01000193;
        }
        return h & 0x7FFFFFFF;
    }

    /**
     * 放入。
     *
     * @param key 键，不允许为 null
     * @param value 值，不允许为 null
     * @return 结果数值
     * @throws IOException 当执行过程不满足前置条件时
     */
    public long put(String key, byte[] value) throws IOException {
        byte[] kb = key.getBytes(StandardCharsets.UTF_8);
        int vlen = value == null ? 0 : value.length;
        int total = 4 + kb.length + 4 + vlen;
        if (writeBuf.length < total) {
            writeBuf = new byte[Math.max(total * 2, KV_WRITE_BUF_SIZE)];
        }
        ByteBuffer.wrap(writeBuf, 0, total).putInt(kb.length).put(kb).putInt(vlen);
        if (value != null) {
            System.arraycopy(value, 0, writeBuf, 4 + kb.length + 4, vlen);
        }
        int idx = shardHash(kb) % config.shardCount();
        byte[] payload = Arrays.copyOf(writeBuf, total);
        long lsn = walLogs[idx].append((byte) 0x01, payload);
        memIndex.put(key, value == null ? new byte[0] : Arrays.copyOf(value, vlen));
        totalRecords.incrementAndGet();
        return lsn;
    }

    /**
     * 快速写入：key bytes 已由调用方预分配，避免循环中重复创建字符串
     * @param key 键，不允许为 null
     * @param value 值，不允许为 null
     * @return 结果数值
     */
    public long putFast(byte[] key, byte[] value) throws IOException {
        int vlen = value == null ? 0 : value.length;
        int total = 4 + key.length + 4 + vlen;
        if (writeBuf.length < total) {
            writeBuf = new byte[Math.max(total * 2, KV_WRITE_BUF_SIZE)];
        }
        ByteBuffer.wrap(writeBuf, 0, total).putInt(key.length).put(key).putInt(vlen);
        if (value != null) {
            System.arraycopy(value, 0, writeBuf, 4 + key.length + 4, vlen);
        }
        int idx = shardHash(key) % config.shardCount();
        byte[] payload = Arrays.copyOf(writeBuf, total);
        long lsn = walLogs[idx].append((byte) 0x01, payload);
        try {
            memIndex.put(new String(key, StandardCharsets.UTF_8), value == null ? new byte[0] : Arrays.copyOf(value, vlen));
        } catch (Exception ignored) {
        }
        totalRecords.incrementAndGet();
        return lsn;
    }

    /**
     * 获取字节数组。
     *
     * @param key 键，不允许为 null
     * @return 可选结果，不存在时为 Optional.empty()
     * @throws IOException 当执行过程不满足前置条件时
     */
    public Optional<byte[]> getBytes(String key) throws IOException {
        byte[] v = memIndex.get(key);
        return v == null ? Optional.empty() : Optional.of(v);
    }

    @Override
    public void rebuildIndex() throws IOException {
        memIndex.clear();
        for (SegmentWalLog log : walLogs) {
            try {
                log.replay((lsn, op, payload) -> {
                    if ((op & 0x80) != 0) {
                        memIndex.remove(decodeKey(payload));
                        return true;
                    }
                    String k = decodeKey(payload);
                    if (k != null) {
                        memIndex.put(k, extractValue(payload));
                    }
                    return true;
                });
            } catch (IOException ignored) {}
        }
    }

    /**
     * 解码键。
     *
     * @param payload 方法入参 payload
     * @return 结果字符串
     */
    private static String decodeKey(byte[] payload) {
        if (payload == null || payload.length < 8) {
            return null;
        }
        int klen = ByteBuffer.wrap(payload).getInt();
        if (klen <= 0 || klen + 8 > payload.length) {
            return null;
        }
        return new String(payload, 4, klen, StandardCharsets.UTF_8);
    }

    /**
     * extract值。
     *
     * @param payload 方法入参 payload
     * @return 结果值
     */
    private static byte[] extractValue(byte[] payload) {
        int klen = ByteBuffer.wrap(payload).getInt();
        int vlen = ByteBuffer.wrap(payload, klen + 4, 4).getInt();
        return Arrays.copyOfRange(payload, klen + 8, klen + 8 + vlen);
    }

    /** 供测试访问内部 walLogs，生产环境不应暴露 */
    SegmentWalLog[] getWalLogs() { return walLogs; }

    /**
     * 创建。
     *
     * @param baseDir base目录，不允许为 null
     * @return KvWalStoreSystem 对象
     * @throws IOException 当执行过程不满足前置条件时
     */
    public static KvWalStoreSystem create(Path baseDir) throws IOException {
        return new KvWalStoreSystem(new WalStoreEnvDetector().detect(baseDir));
    }
}
