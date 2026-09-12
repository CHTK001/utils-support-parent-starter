package com.chua.common.support.datasource.wal;

import com.chua.common.support.tree.BPlusTree;
import com.chua.common.support.utils.ThreadUtils;
import com.chua.common.support.wal.*;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
* WAL 存储系统抽象基类，亿级数据分片架构。
 */
@Slf4j
public abstract class AbstractWalStoreSystem<K extends Comparable<K>> implements WalStoreSystem<K>, Closeable {

    protected final WalStoreConfig config;
    protected final ShardRouter router;
    protected final ShardedIndex index;
    protected final SegmentWalLog[] walLogs;
    protected final ScheduledExecutorService compactScheduler;
    protected volatile boolean closed = false;
    protected final AtomicLong totalRecords = new AtomicLong(0);

    protected AbstractWalStoreSystem(WalStoreConfig config) throws IOException {
        this.config = config;
        this.router = new ShardRouter(config.shardCount());
        this.index = new ShardedIndex(config.shardCount());
        this.walLogs = new SegmentWalLog[config.shardCount()];
        this.compactScheduler = ThreadUtils.newDaemonSingleThreadScheduledExecutor("wal-store-compact");
        open();
    }

    protected void open() throws IOException {
        Path walDir = config.baseDir().resolve("_wal");
        Files.createDirectories(walDir);
        for (int i = 0; i < config.shardCount(); i++) {
            WalConfig shardCfg = WalConfig.builder()
                    .walDir(walDir)
                    .namespace(namespace(i))
                    .impl(WalConfig.WalImpl.SEGMENT)
                    .syncOnWrite(false)
                    .fsyncBatchSize(config.flushBatchSize())
                    .fsyncBatchIntervalMs(config.flushIntervalMs())
                    .maxSegmentBytes(config.segmentBytes())
                    .maxRecordsPerSegment(100_000)
                    .build();
            walLogs[i] = (SegmentWalLog) WalFactory.open(shardCfg);
        }
    }

    @Override
    public Path baseDir() { return config.baseDir(); }

    @Override
    public String type() { return storeType().name().toLowerCase(); }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        compactScheduler.shutdownNow();
        for (SegmentWalLog log : walLogs) {
            try { log.close(); } catch (IOException ignored) {}
        }
    }

    // ==================== 写入 =============
    @Override
    public long append(K key, byte[] payload) throws IOException {
        if (closed) throw new IllegalStateException("StoreSystem 已关闭");
        String keyStr = key.toString();
        int shardIdx = router.shardOf(keyStr);
        long lsn = walLogs[shardIdx].append((byte) opType(), payload == null ? new byte[0] : payload.clone());
        totalRecords.incrementAndGet();
        return lsn;
    }

    // ==================== 点查 =============
    @Override
    public Optional<byte[]> get(K key) throws IOException {
        if (closed) throw new IllegalStateException("StoreSystem 已关闭");
        String keyStr = key.toString();
        Optional<ShardedIndex.EntryLoc> locOpt = index.get(keyStr);
        if (locOpt.isEmpty()) return Optional.empty();
        ShardedIndex.EntryLoc loc = locOpt.get();
        // 从对应分片读取
        return Optional.ofNullable(readFromSegment(loc.segmentNo(), loc.offset(), loc.length()));
    }

    @Override
    public boolean contains(K key) throws IOException {
        return index.contains(key.toString());
    }

    // ==================== 范围查 =============
    @Override
    public List<Map.Entry<K, byte[]>> range(K from, K to) throws IOException {
        return range(from, to, 0, Integer.MAX_VALUE);
    }

    @Override
    public List<Map.Entry<K, byte[]>> range(K from, K to, int offset, int limit) throws IOException {
        List<Map.Entry<String, ShardedIndex.EntryLoc>> indexed =
                index.range(from.toString(), to.toString(), offset, limit);
        List<Map.Entry<K, byte[]>> result = new ArrayList<>();
        for (Map.Entry<String, ShardedIndex.EntryLoc> e : indexed) {
            byte[] payload = readFromSegment(e.getValue().segmentNo(), e.getValue().offset(), e.getValue().length());
            if (payload != null) result.add(Map.entry((K) e.getKey(), payload));
        }
        return result;
    }

    // ==================== 删除 =============
    @Override
    public boolean delete(K key) throws IOException {
        String keyStr = key.toString();
        if (!index.contains(keyStr)) return false;
        int shardIdx = router.shardOf(keyStr);
        walLogs[shardIdx].append((byte) (opType() | AbstractWalFileSystem.OP_TOMBSTONE), new byte[0]);
        index.remove(keyStr);
        totalRecords.decrementAndGet();
        return true;
    }

    // ==================== 索引管理 =============
    @Override
    public void rebuildIndex() throws IOException {
        log.info("[wal-store] rebuilding index for type={}", type());
        index.clear();
        totalRecords.set(0);
        for (int i = 0; i < config.shardCount(); i++) rebuildShardIndex(i);
        log.info("[wal-store] index rebuilt: {} records", totalRecords.get());
    }

    protected void rebuildShardIndex(int shardIdx) throws IOException {
        for (WalSegmentInfo seg : walLogs[shardIdx].listSegments()) {
            walLogs[shardIdx].replay(seg.firstLsn(), seg.lastLsn() + 1, (lsn, op, payload) -> {
                if (isTombstone(op)) return true;
                String key = decodeKey(payload);
                if (key == null) return true;
                index.put(key, new ShardedIndex.EntryLoc(seg.segmentNo(), lsn, payload.length));
                totalRecords.incrementAndGet();
                return true;
            });
        }
    }

    @Override
    public void compact() throws IOException {
        for (SegmentWalLog log : walLogs) log.purgeCheckpointed(1);
        rebuildIndex();
    }

    // ==================== 统计 =============
    @Override
    public int size() { return (int) Math.max(0, totalRecords.get()); }

    @Override
    public List<WalSegmentInfo> listSegments() throws IOException {
        List<WalSegmentInfo> all = new ArrayList<>();
        for (SegmentWalLog log : walLogs) all.addAll(log.listSegments());
        return all;
    }

    // ==================== 子类扩展点 =============
    protected abstract byte opType();
    protected abstract String decodeKey(byte[] payload);
    protected abstract Object decodeValue(K key, byte[] payload);

    // ==================== 内部工具 =============
    private byte[] readFromSegment(int segmentNo, long offset, int length) {
        if (segmentNo < 0 || segmentNo >= walLogs.length) return null;
        try {
            java.nio.file.Path segFile = walLogs[segmentNo].listSegments().stream()
                    .filter(s -> s.segmentNo() == segmentNo).findFirst()
                    .map(WalSegmentInfo::path).orElse(null);
            if (segFile == null) return null;
            try (java.io.FileInputStream fis = new java.io.FileInputStream(segFile.toFile());
                 java.nio.channels.FileChannel ch = fis.getChannel()) {
                if (offset + length > ch.size()) return null;
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(length);
                ch.read(buf, offset);
                return buf.array();
            }
        } catch (IOException e) { return null; }
    }

    protected static boolean isTombstone(byte op) { return (op & AbstractWalFileSystem.OP_TOMBSTONE) != 0; }

    protected String namespace(int shardIdx) {
        return config.namespace() + "-" + String.format("%04d", shardIdx);
    }

    // ==================== 工厂 =============
    public static <K extends Comparable<K>> WalStoreSystem<K> createAuto(
            Class<? extends WalStoreSystem<K>> clazz, Path baseDir) throws IOException {
        WalStoreEnvDetector detector = new WalStoreEnvDetector();
        return create(clazz, detector.detect(baseDir));
    }

    @SuppressWarnings("unchecked")
    public static <K extends Comparable<K>> WalStoreSystem<K> create(
            Class<? extends WalStoreSystem<K>> clazz, WalStoreConfig config) {
        try {
            return (WalStoreSystem<K>) clazz.getConstructor(WalStoreConfig.class).newInstance(config);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create store: " + clazz.getSimpleName(), e);
        }
    }
}
