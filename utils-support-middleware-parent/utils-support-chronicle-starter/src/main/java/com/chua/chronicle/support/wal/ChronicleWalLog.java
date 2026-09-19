package com.chua.chronicle.support.wal;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.wal.CheckpointMeta;
import com.chua.common.support.wal.WalChain;
import com.chua.common.support.wal.WalChainHandler;
import com.chua.common.support.wal.WalConfig;
import com.chua.common.support.wal.WalException;
import com.chua.common.support.wal.WalLog;
import com.chua.common.support.wal.WalOp;
import com.chua.common.support.wal.WalRecord;
import com.chua.common.support.wal.WalReplayHandler;
import com.chua.common.support.wal.WalReplayResult;
import com.chua.common.support.wal.WalSegmentInfo;
import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueStore;
import net.openhft.chronicle.wire.DocumentContext;
import net.openhft.chronicle.wire.ValueIn;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
/**
 * @作者 CH
*/

@Spi("chronicle")
public class ChronicleWalLog implements WalLog {

    /**
     * 字段_lsn
    */
    private static final String FIELD_LSN = "lsn";
    /**
     * 字段_op
    */
    private static final String FIELD_OP = "op";
    /**
     * 字段_payload
    */
    private static final String FIELD_PAYLOAD = "payload";
    /**
     * 字段_checkpoint
    */
    private static final String FIELD_CHECKPOINT = "checkpointLsn";
    /**
     * Chronicle 分片文件名前缀（{@code cycle.<hex>.cq4}）
     */
    private static final String CYCLE_FILE_PREFIX = "cycle.";
    /**
     * Chronicle 分片文件名后缀
     */
    private static final String CYCLE_FILE_SUFFIX = ".cq4";

    /**
     * 队列
    */
    private final ChronicleQueue queue;
    /**
     * Appender
    */
    private final ExcerptAppender appender;
    /**
     * 配置
    */
    private final WalConfig config;
    /**
     * 当前LSN
    */
    private final AtomicLong currentLsn = new AtomicLong(0L);
    /**
     * checkpointlsn
    */
    private final AtomicLong checkpointLsn = new AtomicLong(0L);
    /**
     * closed
    */
    private volatile boolean closed;

    /**
     * 创建 chroniclewal日志 实例
     * @param config 配置
     */
    public ChronicleWalLog(WalConfig config) {
        this.config = config;
        Path dir = resolveDir(config);
        try {
            File file = dir.toFile();
            if (!file.exists() && !file.mkdirs()) {
                throw new WalException("Cannot create Chronicle WAL directory: " + dir);
            }
            this.queue = SingleChronicleQueueBuilder.single(file).build();
            this.appender = queue.createAppender();
        } catch (WalException e) {
            throw e;
        } catch (Exception e) {
            throw new WalException("Chronicle WAL init failed: " + dir, e);
        }
        scanTail();
    }

    /**
     * 解析Dir
     *
     * @param config 配置
     * @return resolveDir的结果
     */
    private Path resolveDir(WalConfig config) {
        Path walDir = config.walDir();
        if (walDir != null) {
            return walDir.resolve(config.namespace());
        }
        return Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("wal").resolve(config.namespace());
    }

    /**
     * 扫描Tail
    */
    private void scanTail() {
        long maxLsn = 0L;
        long cp = 0L;
        ExcerptTailer tailer = queue.createTailer();
        while (true) {
            try (DocumentContext dc = tailer.readingDocument(true)) {
                if (!dc.isPresent()) {
                    break;
                }
                if (!dc.isData()) {
                    continue;
                }
                ValueIn in = dc.wire().read(FIELD_LSN);
                if (in.isPresent()) {
                    long lsn = in.int64();
                    if (lsn > maxLsn) {
                        maxLsn = lsn;
                    }
                }
            }
        }
        // read checkpoint from a dedicated document (stored as data)
        ExcerptTailer cpTailer = queue.createTailer();
        while (true) {
            try (DocumentContext dc = cpTailer.readingDocument(true)) {
                if (!dc.isPresent()) {
                    break;
                }
                if (!dc.isData()) {
                    continue;
                }
                ValueIn in = dc.wire().read(FIELD_CHECKPOINT);
                if (in.isPresent()) {
                    cp = in.int64();
 // the 最后一个 checkpoint found wins
                }
            }
        }
        this.currentLsn.set(maxLsn);
        this.checkpointLsn.set(Math.max(0L, cp));
    }

    @Override
    /**
     * 追加
    */
    public long append(byte op, byte[] payload) throws IOException {
        ensureOpen();
        long lsn = currentLsn.incrementAndGet();
        try (DocumentContext dc = appender.writingDocument()) {
            dc.wire().write(FIELD_LSN).int64(lsn);
            dc.wire().write(FIELD_OP).int8(op);
            dc.wire().write(FIELD_PAYLOAD).bytes(payload == null ? Bytes.empty() : Bytes.wrapForRead(payload));
        }
        return lsn;
    }

    @Override
    /**
     * 同步
    */
    public void sync() throws IOException {
        ensureOpen();
    }

    @Override
    /**
     * 当前lsn
    */
    public long currentLsn() {
        return currentLsn.get();
    }

    @Override
    /**
     * 加载Checkpoint
    */
    public CheckpointMeta loadCheckpoint() throws IOException {
        ensureOpen();
        return new CheckpointMeta(checkpointLsn.get(), 1, 0L, System.currentTimeMillis());
    }

    @Override
    /**
     * 标记Checkpoint
    */
    public void markCheckpoint(long lsn) throws IOException {
        ensureOpen();
        long target = Math.min(lsn, currentLsn.get());
        target = Math.max(0L, target);
        this.checkpointLsn.set(target);
        try (DocumentContext dc = appender.writingDocument()) {
            dc.wire().write(FIELD_CHECKPOINT).int64(target);
            dc.wire().write(FIELD_OP).text("checkpoint");
        }
    }

    @Override
    /**
     * 重置Checkpoint
    */
    public void resetCheckpoint() throws IOException {
        ensureOpen();
        this.checkpointLsn.set(0L);
    }

    @Override
    /**
     * forcecheckpoint
    */
    public void forceCheckpoint(long lsn) throws IOException {
        ensureOpen();
        long target = Math.max(0L, lsn);
        this.checkpointLsn.set(target);
        try (DocumentContext dc = appender.writingDocument()) {
            dc.wire().write(FIELD_CHECKPOINT).int64(target);
            dc.wire().write(FIELD_OP).text("checkpoint");
        }
    }

    @Override
    /**
     * Replay
    */
    public WalReplayResult replay(WalReplayHandler handler) throws IOException {
        return replay(checkpointLsn.get() + 1, Long.MAX_VALUE, handler);
    }

    @Override
    /**
     * Replay
    */
    public WalReplayResult replay(long fromLsn, WalReplayHandler handler) throws IOException {
        return replay(fromLsn, Long.MAX_VALUE, handler);
    }

    @Override
    /**
     * Replay
    */
    public WalReplayResult replay(long fromLsn, long toLsn, WalReplayHandler handler) throws IOException {
        ensureOpen();
        List<WalRecord> records = new ArrayList<>();
        ExcerptTailer tailer = queue.createTailer();
        boolean stopped = false;
        while (true) {
            try (DocumentContext dc = tailer.readingDocument(true)) {
                if (!dc.isPresent()) {
                    break;
                }
                if (!dc.isData()) {
                    continue;
                }
                ValueIn lsnIn = dc.wire().read(FIELD_LSN);
                if (!lsnIn.isPresent()) {
                    continue;
                }
                long lsn = lsnIn.int64();
                byte op = dc.wire().read(FIELD_OP).int8();
                byte[] payload = dc.wire().read(FIELD_PAYLOAD).bytes();
                if (payload == null) {
                    continue;
                }
                if (lsn < fromLsn || lsn >= toLsn) {
                    continue;
                }
                WalRecord rec = new WalRecord(lsn, op, payload);
                records.add(rec);
                if (handler != null) {
                    try {
                        if (!handler.onRecord(lsn, op, payload)) {
                            stopped = true;
                            break;
                        }
                    } catch (WalException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new WalException("Replay handler error at lsn=" + lsn, e);
                    }
                }
            }
        }
        return new WalReplayResult(new CheckpointMeta(checkpointLsn.get(), 1, 0L, System.currentTimeMillis()), records);
    }

    @Override
    /**
     * 追加Chain
    */
    public long appendChain(WalChainHandler handler) throws IOException {
        ensureOpen();
        List<WalOp> ops = new ArrayList<>();
        try {
            handler.apply(new ChronicleWalChain(ops));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new WalException("Chain append failed", e);
        }
        long lastLsn = currentLsn.get();
        for (WalOp op : ops) {
            lastLsn = append(op.op(), op.payload());
        }
        return lastLsn;
    }

    @Override
    /**
     * 查找bylsn
    */
    public Optional<WalRecord> findByLsn(long lsn) throws IOException {
        ensureOpen();
        ExcerptTailer tailer = queue.createTailer();
        while (true) {
            try (DocumentContext dc = tailer.readingDocument(true)) {
                if (!dc.isPresent()) {
                    break;
                }
                if (!dc.isData()) {
                    continue;
                }
                ValueIn lsnIn = dc.wire().read(FIELD_LSN);
                if (!lsnIn.isPresent()) {
                    continue;
                }
                long cur = lsnIn.int64();
                byte op = dc.wire().read(FIELD_OP).int8();
                byte[] payload = dc.wire().read(FIELD_PAYLOAD).bytes();
                if (payload == null) {
                    continue;
                }
                if (cur == lsn) {
                    return Optional.of(new WalRecord(cur, op, payload));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    /**
     * purgecheckpointed（真实 删除：逐 cycle 统计 末 条 LSN，仅 移除
     * 非 活跃 且 全 部 记录 ≤ checkpoint 的 cycle 文 件，保 留 最 近 keepSegments 个）
     */
    public int purgeCheckpointed(int keepSegments) throws IOException {
        ensureOpen();
        Map<Integer, long[]> stats = scanCycleStats();
        List<Integer> cycles = allCycles(stats);
        if (cycles.isEmpty()) {
            return 0;
        }
        int activeCycle = cycles.get(cycles.size() - 1);
        long cp = checkpointLsn.get();
        List<Integer> removable = new ArrayList<>();
        for (int c : cycles) {
            if (c == activeCycle) {
                continue;
            }
            long[] s = stats.get(c);
            boolean fullyCheckpointed = s == null || s[2] == 0L
                    || (s[1] != Long.MIN_VALUE && s[1] <= cp);
            if (fullyCheckpointed) {
                removable.add(c);
            }
        }
        int keep = Math.max(1, keepSegments);
        int maxDelete = Math.max(0, cycles.size() - keep);
        int limit = Math.min(removable.size(), maxDelete);
        SingleChronicleQueue scq = queue instanceof SingleChronicleQueue s ? s : null;
        int deleted = 0;
        for (int i = 0; i < limit; i++) {
            int c = removable.get(i);
            if (scq != null) {
                try (SingleChronicleQueueStore store = scq.storeForCycle(c, 0L, false, null)) {
                    if (store != null) {
                        File f = store.currentFile();
                        if (f != null && f.exists() && !f.delete()) {
                            throw new IOException("无法删除 Chronicle WAL 分片文件: " + f);
                        }
                    }
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException("删除 Chronicle WAL 分片失败: cycle=" + c, e);
                }
            }
            File fallback = cycleFilePath(c).toFile();
            if (fallback.exists() && !fallback.delete()) {
                throw new IOException("无法删除 Chronicle WAL 分片文件: " + fallback);
            }
            deleted++;
        }
        if (scq != null && deleted > 0) {
            scq.refreshDirectoryListing();
        }
        return deleted;
    }

    @Override
    /**
     * 当前lsn所在活跃分片（真实 cycle 扫描：最 大 cycle 即 活 跃 分 片）
     */
    public WalSegmentInfo currentSegment() {
        List<WalSegmentInfo> segments = listSegments();
        for (int i = segments.size() - 1; i >= 0; i--) {
            if (segments.get(i).active()) {
                return segments.get(i);
            }
        }
        return new WalSegmentInfo(1, 0L, 0L, 0, resolveDir(config), true);
    }

    @Override
    /**
     * 列表segments（按 cycle 升 序 还 原 真 实 分 片，segmentNo 从 1 编 号）
     */
    public List<WalSegmentInfo> listSegments() {
        Map<Integer, long[]> stats = scanCycleStats();
        List<Integer> cycles = allCycles(stats);
        List<WalSegmentInfo> out = new ArrayList<>();
        int activeCycle = cycles.isEmpty() ? Integer.MIN_VALUE : cycles.get(cycles.size() - 1);
        int no = 1;
        for (int c : cycles) {
            long[] s = stats.get(c);
            long first = s == null || s[0] == Long.MAX_VALUE ? 0L : s[0];
            long last = s == null || s[1] == Long.MIN_VALUE ? 0L : s[1];
            int count = s == null ? 0 : (int) s[2];
            out.add(new WalSegmentInfo(no, first, last, count, cycleFilePath(c), c == activeCycle));
            no++;
        }
        return out;
    }

    /**
     * 全 量 扫 描 队 列，按 cycle 收 集 [firstLsn, lastLsn, recordCount] 统 计。
     *
     * @return cycle → 统计 数组（LinkedHashMap 保 持 读 取 顺 序）
     */
    private Map<Integer, long[]> scanCycleStats() {
        Map<Integer, long[]> stats = new LinkedHashMap<>();
        ExcerptTailer tailer = queue.createTailer();
        while (true) {
            try (DocumentContext dc = tailer.readingDocument(true)) {
                if (!dc.isPresent()) {
                    break;
                }
                if (!dc.isData()) {
                    continue;
                }
                int cycle = tailer.cycle();
                long[] s = stats.computeIfAbsent(cycle, k -> new long[]{Long.MAX_VALUE, Long.MIN_VALUE, 0L});
                s[2]++;
                ValueIn lsnIn = dc.wire().read(FIELD_LSN);
                if (lsnIn.isPresent()) {
                    long lsn = lsnIn.int64();
                    if (lsn < s[0]) {
                        s[0] = lsn;
                    }
                    if (lsn > s[1]) {
                        s[1] = lsn;
                    }
                }
            }
        }
        return stats;
    }

    /**
     * 合并 扫 描 命 中 与 磁 盘 文 件 的 cycle 集 合（升 序）。
     *
     * @param stats 扫 描 统 计
     * @return 全 部 cycle（升 序）
     */
    private List<Integer> allCycles(Map<Integer, long[]> stats) {
        TreeSet<Integer> set = new TreeSet<>(stats.keySet());
        set.addAll(cyclesOnDisk());
        return new ArrayList<>(set);
    }

    /**
     * 从 目 录 列 表 解 析 {@code cycle.<hex>.cq4} 文 件 得 到 cycle 集 合。
     *
     * @return 磁 盘 上 存 在 的 cycle（升 序）
     */
    private List<Integer> cyclesOnDisk() {
        List<Integer> cycles = new ArrayList<>();
        File[] files = resolveDir(config).toFile().listFiles(
                (d, n) -> n.startsWith(CYCLE_FILE_PREFIX) && n.endsWith(CYCLE_FILE_SUFFIX));
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                String hex = name.substring(CYCLE_FILE_PREFIX.length(),
                        name.length() - CYCLE_FILE_SUFFIX.length());
                try {
                    cycles.add(Integer.parseUnsignedInt(hex, 16));
                } catch (NumberFormatException ignored) {
 // 非 标 准 命 名 的 残 留 文 件，跳 过
                }
            }
        }
        Collections.sort(cycles);
        return cycles;
    }

    /**
     * 构 造 cycle 对 应 的 分 片 文 件 路 径。
     *
     * @param cycle cycle 编 号
     * @return 文 件 路 径
     */
    private Path cycleFilePath(int cycle) {
        return resolveDir(config).resolve(CYCLE_FILE_PREFIX + Integer.toHexString(cycle) + CYCLE_FILE_SUFFIX);
    }

    @Override
    /**
     * 关闭
    */
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            queue.close();
        } catch (Exception e) {
            throw new IOException("Close failed", e);
        }
    }

    /**
     * Ensure打开
    */
    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Chronicle WAL is closed");
        }
    }

    private static final class ChronicleWalChain implements WalChain {
        /**
         * OPS
        */
        private final List<WalOp> ops;

        ChronicleWalChain(List<WalOp> ops) {
            this.ops = ops;
        }

        @Override
        /**
         * 添加
        */
        public WalChain add(byte op, byte[] payload) {
            ops.add(new WalOp(op, payload == null ? new byte[0] : payload));
            return this;
        }

        @Override
        /**
         * 添加
        */
        public WalChain add(byte op, String s) {
            byte[] bytes = s == null ? new byte[0] : s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ops.add(new WalOp(op, bytes));
            return this;
        }

        @Override
        /**
         * 添加
        */
        public WalChain add(byte op) {
            ops.add(new WalOp(op, new byte[0]));
            return this;
        }

        @Override
        /**
         * 获取大小
        */
        public int size() {
            return ops.size();
        }
    }
}


