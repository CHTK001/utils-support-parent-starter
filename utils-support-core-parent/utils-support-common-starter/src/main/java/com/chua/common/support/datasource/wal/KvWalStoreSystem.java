package com.chua.common.support.datasource.wal;

import com.chua.common.support.utils.ThreadUtils;
import com.chua.common.support.wal.*;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * KV 存储引擎。
 */
@Slf4j
public class KvWalStoreSystem implements WalStoreSystem<String> {

    private final WalStoreConfig config;
    private final SegmentWalLog[] walLogs;
    private final ShardedIndex index = new ShardedIndex(1);
    private final java.util.concurrent.ConcurrentHashMap<String, byte[]> memIndex = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong totalRecords = new AtomicLong(0);
    private volatile boolean closed = false;
    /**
     * KV 写入记录的操作码
    */
    private static final byte OP_PUT = 0x01;
    /**
     * 墓碑标记位，与写入操作码按位或后表示删除
    */
    private static final byte OP_TOMBSTONE = AbstractWalFileSystem.OP_TOMBSTONE;
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
     * <p>该引擎 {@code syncOnWrite(false)}，定时 fsync 是唯一的落盘时机，
     * 失败必须留痕；但不向外抛出——任务抛异常会让 {@code scheduleAtFixedRate}
     * 取消后续所有执行，等于彻底停止落盘，后果更严重。</p>
     */
    private void fsyncAll() {
        for (int i = 0; i < walLogs.length; i++) {
            try {
                walLogs[i].sync();
            } catch (IOException | RuntimeException e) {
                log.error("[WAL] KV 分片 {} 定时 fsync 失败，已确认的写入可能仍未落盘: {}",
                        i, e.getMessage(), e);
            }
        }
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
        long lsn = walLogs[idx].append(OP_PUT, payload == null ? new byte[0] : payload);
        totalRecords.incrementAndGet();
        return lsn;
    }

    /**
     * 点查：从内存索引读取（索引由 put/putFast 维护、由 rebuildIndex 从 WAL 重建）。
     *
     * @param key 键，不允许为 null
     * @return 可选结果，不存在时为 Optional.empty()
     * @throws IOException 当执行过程不满足前置条件时
     */
    @Override
    public Optional<byte[]> get(String key) throws IOException {
        return getBytes(key);
    }

    /**
     * 判断 key 是否存在。
     *
     * @param key 键，不允许为 null
     * @return 是否成功（true 表示成功）
     */
    @Override
    public boolean contains(String key) {
        return memIndex.containsKey(key);
    }

    @Override
    public List<Map.Entry<String, byte[]>> range(String from, String to) throws IOException {
        return range(from, to, 0, Integer.MAX_VALUE);
    }

    /**
     * 带分页的范围查询：按 key 升序取 [from, to) 区间内 offset 之后的 limit 条。
     *
     * @param from 下界（含）
     * @param to 上界（不含）
     * @param offset 偏移量
     * @param limit 上限
     * @return 条目列表
     * @throws IOException 当执行过程不满足前置条件时
     */
    @Override
    public List<Map.Entry<String, byte[]>> range(String from, String to, int offset, int limit) throws IOException {
        List<String> keys = new ArrayList<>(memIndex.keySet());
        keys.sort(Comparator.naturalOrder());
        List<Map.Entry<String, byte[]>> entries = new ArrayList<>();
        for (String key : keys) {
            if ((from == null || key.compareTo(from) >= 0) && (to == null || key.compareTo(to) < 0)) {
                entries.add(Map.entry(key, memIndex.get(key)));
            }
        }
        int begin = Math.min(Math.max(offset, 0), entries.size());
        int end = limit <= 0 || (long) begin + limit > entries.size() ? entries.size() : begin + limit;
        return new ArrayList<>(entries.subList(begin, end));
    }

    /**
     * 逻辑删除：追加携带键的墓碑记录并摘除内存索引条目。
     *
     * @param key 键，不允许为 null
     * @return 删除前 key 是否存在
     * @throws IOException 当墓碑写入失败时
     */
    @Override
    public boolean delete(String key) throws IOException {
        if (closed) {
            throw new IllegalStateException("StoreSystem 已关闭");
        }
        byte[] kb = key.getBytes(StandardCharsets.UTF_8);
        int idx = shardHash(kb) % config.shardCount();
        walLogs[idx].append((byte) (OP_PUT | OP_TOMBSTONE), KvWalFileSystem.encode(key, new byte[0]));
        return memIndex.remove(key) != null;
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[WAL] KV 关闭时等待 fsync 调度线程被中断: {}", e.getMessage());
        }
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

    // ==================== KV 专用 ====================

    /**
     * 可复用写缓冲，最大 key=128B + value=512B + 2个int长度头 = ~644B，对齐到 1024
     */
    private static final int KV_WRITE_BUF_SIZE = 1024;
    /**
     * 写入Buf
    */
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
        long lsn = walLogs[idx].append(OP_PUT, payload);
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
        long lsn = walLogs[idx].append(OP_PUT, payload);
        String k = new String(key, StandardCharsets.UTF_8);
        try {
            memIndex.put(k, value == null ? new byte[0] : Arrays.copyOf(value, vlen));
        } catch (RuntimeException e) {
            memIndex.remove(k);
            throw new WalException("KV 内存索引写入失败，WAL 与索引已不一致: key=" + k + ", lsn=" + lsn, e);
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

    /**
     * 重建索引：逐分片回放 WAL，重写内存索引。
     *
     * <p>任一分片回放失败都会包装为 {@link WalException} 抛出，避免把残缺索引当作重建成功。</p>
     *
     * @throws IOException 当回放过程失败时
     */
    @Override
    public void rebuildIndex() throws IOException {
        memIndex.clear();
        for (int i = 0; i < walLogs.length; i++) {
            replayShardIntoMemIndex(i);
        }
        totalRecords.set(memIndex.size());
    }

    /**
     * 回放单个分片并把有效记录写入内存索引。
     *
     * @param shardIdx 分片索引
     * @throws IOException 当回放过程失败时
     */
    private void replayShardIntoMemIndex(int shardIdx) throws IOException {
        SegmentWalLog log = walLogs[shardIdx];
        for (WalSegmentInfo seg : log.listSegments()) {
            try {
                log.replay(seg.firstLsn(), seg.lastLsn() + 1, (lsn, op, payload) -> {
                    String k = decodeKey(payload);
                    if ((op & OP_TOMBSTONE) != 0) {
                        if (k != null) {
                            memIndex.remove(k);
                        }
                        return true;
                    }
                    if (k != null) {
                        memIndex.put(k, extractValue(payload));
                    }
                    return true;
                });
            } catch (IOException | RuntimeException e) {
                throw new WalException("WAL 索引重建失败: 分片=" + config.namespace() + "-" + shardIdx
                        + ", 分段=" + seg.path(), e);
            }
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

    /**
     * 供测试访问内部 walLogs，生产环境不应暴露
    */
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
