package com.chua.common.support.wal;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 分片 WAL 实现，支持按大小/记录数自动滚动分片、checkpoint 管理、范围回放。
 *
 * <h2>目录布局</h2>
 * <pre>
 * ${walDir}/
 * ├── segments/
 * │   ├── ${namespace}-000001.wal
 * │   ├── ${namespace}-000002.wal
 * │   └── ${namespace}-000003.wal   ← 当前活跃分片
 * └── checkpoint.meta                ← checkpoint 元信息
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SegmentWalLog implements WalLog {

    /**
     * 分片目录名
     */
    private static final String SEGMENTS_DIR = "segments";

    /**
     * 分片文件名格式
     */
    private static final String SEGMENT_NAME_FORMAT = "%s-%06d.wal";

    /**
     * checkpoint 文件名
     */
    private static final String CHECKPOINT_FILE_NAME = "checkpoint.meta";

    /**
     * checkpoint 文件魔数
     */
    private static final byte[] CHECKPOINT_MAGIC = new byte[]{'C', 'K', 'P', 'T'};

    /**
     * checkpoint 元信息固定大小
     */
    private static final int CHECKPOINT_META_SIZE = 4 + 8 + 4 + 8 + 8;

    /**
     * 分片目录
     */
    private final Path segmentsDir;

    /**
     * checkpoint 文件路径
     */
    private final Path checkpointFile;

    /**
     * 配置
     */
    private final WalConfig config;

    /**
     * 命名空间（用于分片文件名前缀）
     */
    private final String namespace;

    /**
     * 当前活跃分片序号
     */
    private int activeSegmentNo;

    /**
      * 当前活跃分片 随机access文件
     */
    private RandomAccessFile activeRaf;

    /**
      * 当前活跃分片 文件通道
     */
    private FileChannel activeChannel;

    /**
     * 当前活跃分片输出流
     */
    private DataOutputStream activeOut;

    /**
     * 当前活跃分片已写入字节
     */
    private long activeWrittenBytes;

    /**
     * 当前活跃分片已写入记录数
     */
    private int activeRecordCount;

    /**
     * 当前活跃分片首条 LSN
     */
    private long activeFirstLsn;

    /**
     * 当前最大 LSN
     */
    private long currentLsn;

    /**
     * 当前 checkpoint LSN
     */
    private long checkpointLsn;

    /**
     * 当前 checkpoint 段号
     */
    private int checkpointSegmentNo;

    /**
     * 当前 checkpoint 段内偏移
     */
    private long checkpointOffset;

    /**
     * 自上次 fsync 起累计写入条数
     */
    private int pendingFsyncOps;

    /**
     * 是否已关闭
     */
    private boolean closed;

    /** 可复用的 CRC32 实例，避免每次 追加 分配对象 */
    private final CRC32 crc = new CRC32();

    /** 可复用的写缓冲，最大单条记录大小（含头部），首次使用按需扩容 */
    private byte[] writeBuf;

    /**
      * 创建 segmentwal日志 实例
     * @param config 配置
     */
    public SegmentWalLog(WalConfig config) throws IOException {
        this.config = config;
        this.namespace = config.namespace();
        this.segmentsDir = config.walDir().resolve(SEGMENTS_DIR);
        this.checkpointFile = config.walDir().resolve(CHECKPOINT_FILE_NAME);
        Files.createDirectories(segmentsDir);
        open();
    }

    /** 打开 */
    private void open() throws IOException {
        List<WalSegmentInfo> segments = scanSegments();
        if (segments.isEmpty()) {
            this.activeSegmentNo = 1;
            this.activeFirstLsn = 0L;
            this.activeRecordCount = 0;
            this.activeWrittenBytes = 0L;
            openActiveSegmentWriter(1);
        } else {
            WalSegmentInfo last = segments.get(segments.size() - 1);
            this.activeSegmentNo = last.segmentNo();
            this.activeFirstLsn = last.lastLsn() == 0 ? 0 : last.firstLsn();
            this.currentLsn = last.lastLsn();
            this.activeRecordCount = last.recordCount();
            this.activeWrittenBytes = Files.size(last.path());
            openActiveSegmentWriter(activeSegmentNo);
        }
        CheckpointMeta meta = readCheckpointFromDisk();
        this.checkpointLsn = meta.checkpointLsn();
        this.checkpointSegmentNo = meta.checkpointSegmentNo();
        this.checkpointOffset = meta.checkpointOffset();
    }

    /**
     * 扫描Segments
     *
     * @return 扫描segments的结果
     */
    private List<WalSegmentInfo> scanSegments() {
        flushBuffered();
        if (!Files.exists(segmentsDir)) {
            return Collections.emptyList();
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(segmentsDir)) {
            files = stream
                    .filter(p -> p.getFileName().toString().endsWith(".wal")
                            && p.getFileName().toString().startsWith(namespace + "-"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return Collections.emptyList();
        }
        List<WalSegmentInfo> result = new ArrayList<>();
        for (Path path : files) {
            WalSegmentInfo info = scanSingleSegment(path);
            if (info != null) {
                result.add(info);
            }
        }
        return result;
    }

    /**
     * 扫描单个segment
     *
     * @param path 路径
     * @return 扫描单个segment的结果
     */
    private WalSegmentInfo scanSingleSegment(Path path) {
        String name = path.getFileName().toString();
        String prefix = namespace + "-";
        if (!name.startsWith(prefix)) {
            return null;
        }
        int segNo;
        try {
            String numPart = name.substring(prefix.length(), name.length() - 4);
            segNo = Integer.parseInt(numPart);
        } catch (NumberFormatException e) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(path)))) {
            long firstLsn = 0L;
            long lastLsn = 0L;
            int count = 0;
            try {
                while (true) {
                    byte[] header = new byte[WalConfig.RECORD_HEADER_BYTES];
                    in.readFully(header);
                    int len = readInt(header, WalConfig.CRC32_BYTES + WalConfig.LSN_BYTES + WalConfig.OP_BYTES);
                    long lsn = readLong(header, WalConfig.CRC32_BYTES);
                    if (count == 0) {
                        firstLsn = lsn;
                    }
                    lastLsn = lsn;
                    in.skip(len);
                    count++;
                }
            } catch (EOFException stop) {
                // 正常
            }
            boolean active = segNo == activeSegmentNo && count > 0;
            return new WalSegmentInfo(segNo, firstLsn, lastLsn, count, path, active);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 打开活跃segmentwriter
     *
     * @param segNo segno
     */
    private void openActiveSegmentWriter(int segNo) throws IOException {
        Path p = segmentPath(segNo);
        this.activeRaf = new RandomAccessFile(p.toFile(), "rw");
        this.activeChannel = activeRaf.getChannel();
        this.activeOut = new DataOutputStream(new BufferedOutputStream(
                new java.io.FileOutputStream(activeRaf.getFD())));
        if (activeWrittenBytes == 0L) {
            // 新分片：定位到末尾追加
            activeRaf.seek(activeRaf.length());
        } else {
            activeRaf.seek(activeWrittenBytes);
        }
    }

    /**
     * segment路径
     *
     * @param segNo segno
     * @return segment路径的结果
     */
    private Path segmentPath(int segNo) {
        String name = String.format(SEGMENT_NAME_FORMAT, namespace, segNo);
        return segmentsDir.resolve(name);
    }

    @Override
    /** 追加 */
    public long append(byte op, byte[] payload) throws IOException {
        ensureOpen();
        if (payload == null) {
            payload = new byte[0];
        }
        long lsn = currentLsn + 1;
        byte[] body = buildBody(lsn, op, payload);
        if (activeRecordCount == 0) {
            activeFirstLsn = lsn;
        }
        if (needsRoll(body.length)) {
            rollSegment();
        }
        activeOut.write(body);
        activeWrittenBytes += body.length;
        activeRecordCount++;
        currentLsn = lsn;
        pendingFsyncOps++;
        maybeFsync();
        return lsn;
    }

    /**
     * needsroll
     *
     * @param incomingBytes 收入bytes
     * @return needsRoll的结果
     */
    private boolean needsRoll(int incomingBytes) {
        if (activeWrittenBytes + incomingBytes > config.maxSegmentBytes()) {
            return true;
        }
        if (activeRecordCount + 1 > config.maxRecordsPerSegment()) {
            return true;
        }
        return false;
    }

    /** rollsegment */
    private void rollSegment() throws IOException {
        if (activeOut != null) {
            try {
                activeOut.flush();
            } catch (IOException ignored) {
            }
            try {
                activeOut.close();
            } catch (IOException ignored) {
            }
            activeOut = null;
        }
        if (activeChannel != null) {
            try {
                activeChannel.force(true);
                activeChannel.close();
            } catch (IOException ignored) {
            }
            activeChannel = null;
        }
        if (activeRaf != null) {
            try {
                activeRaf.close();
            } catch (IOException ignored) {
            }
            activeRaf = null;
        }
        activeSegmentNo++;
        activeWrittenBytes = 0L;
        activeRecordCount = 0;
        activeFirstLsn = 0L;
        openActiveSegmentWriter(activeSegmentNo);
    }

    @Override
    /** 同步 */
    public void sync() throws IOException {
        ensureOpen();
        force();
        pendingFsyncOps = 0;
    }

    /** Force */
    private void force() throws IOException {
        if (activeOut != null) {
            activeOut.flush();
        }
        if (activeChannel != null) {
            activeChannel.force(true);
        }
    }

    /**
     * 将缓冲中的写入数据冲刷到文件，保证后续读路径可见。
     *
     * <p>当 {@code syncOnWrite=false} 时写入先进入 {@link BufferedOutputStream}，
      * 若读路径（replay/列表segments/findbylsn）直接打开新文件流，会读不到仍未落盘的记录。
     * 本方法在任何读操作前调用，确保写后读一致性（flush 到文件即可见，无需每次 fsync）。</p>
     */
    private void flushBuffered() {
        if (activeOut != null) {
            try {
                activeOut.flush();
            } catch (IOException ignored) {
                // 忽略冲刷失败，读路径尽量读到已落盘部分
            }
        }
    }

    /** maybefsync */
    private void maybeFsync() throws IOException {
        if (!config.syncOnWrite()) {
            return;
        }
        if (config.fsyncBatchSize() <= 1 || pendingFsyncOps >= config.fsyncBatchSize()) {
            force();
            pendingFsyncOps = 0;
        }
    }

    @Override
    /** 当前lsn */
    public long currentLsn() {
        return currentLsn;
    }

    // ==================== Checkpoint ====================

    @Override
    /** 加载Checkpoint */
    public CheckpointMeta loadCheckpoint() throws IOException {
        ensureOpen();
        return readCheckpointFromDisk();
    }

    /**
     * 读取checkpoint从disk
     *
     * @return 读取checkpoint从disk的结果
     */
    private CheckpointMeta readCheckpointFromDisk() throws IOException {
        if (!Files.exists(checkpointFile)) {
            return CheckpointMeta.empty();
        }
        try (FileChannel ch = FileChannel.open(checkpointFile, StandardOpenOption.READ)) {
            if (ch.size() < CHECKPOINT_META_SIZE) {
                return CheckpointMeta.empty();
            }
            ByteBuffer buf = ByteBuffer.allocate(CHECKPOINT_META_SIZE);
            int read = ch.read(buf);
            if (read < CHECKPOINT_META_SIZE) {
                return CheckpointMeta.empty();
            }
            buf.flip();
            byte[] magic = new byte[4];
            buf.get(magic);
            for (int i = 0; i < CHECKPOINT_MAGIC.length; i++) {
                if (magic[i] != CHECKPOINT_MAGIC[i]) {
                    return CheckpointMeta.empty();
                }
            }
            long lsn = buf.getLong();
            int segNo = buf.getInt();
            long offset = buf.getLong();
            long ts = buf.getLong();
            return new CheckpointMeta(lsn, segNo, offset, ts);
        }
    }

    /**
     * 写入checkpoint转为disk
     *
     * @param meta meta
     */
    private void writeCheckpointToDisk(CheckpointMeta meta) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(CHECKPOINT_META_SIZE);
        buf.put(CHECKPOINT_MAGIC);
        buf.putLong(meta.checkpointLsn());
        buf.putInt(meta.checkpointSegmentNo());
        buf.putLong(meta.checkpointOffset());
        buf.putLong(meta.timestamp());
        buf.flip();
        try (FileChannel ch = FileChannel.open(checkpointFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ch.write(buf);
            ch.force(true);
        }
    }

    @Override
    /** 标记Checkpoint */
    public void markCheckpoint(long lsn) throws IOException {
        ensureOpen();
        if (lsn > currentLsn) {
            lsn = currentLsn;
        }
        if (lsn < checkpointLsn) {
            throw new WalException("不能回退 checkpoint: 当前=" + checkpointLsn + ", 目标=" + lsn);
        }
        this.checkpointLsn = lsn;
        this.checkpointSegmentNo = activeSegmentNo;
        this.checkpointOffset = activeWrittenBytes;
        writeCheckpointToDisk(new CheckpointMeta(lsn, activeSegmentNo, activeWrittenBytes,
                System.currentTimeMillis()));
    }

    @Override
    /** 重置Checkpoint */
    public void resetCheckpoint() throws IOException {
        ensureOpen();
        this.checkpointLsn = 0L;
        this.checkpointSegmentNo = 1;
        this.checkpointOffset = 0L;
        try {
            Files.deleteIfExists(checkpointFile);
        } catch (IOException ignored) {
        }
    }

    @Override
    /** forcecheckpoint */
    public void forceCheckpoint(long lsn) throws IOException {
        ensureOpen();
        if (lsn > currentLsn) {
            lsn = currentLsn;
        }
        if (lsn < 0) {
            lsn = 0L;
        }
        this.checkpointLsn = lsn;
        this.checkpointSegmentNo = activeSegmentNo;
        this.checkpointOffset = activeWrittenBytes;
        writeCheckpointToDisk(new CheckpointMeta(lsn, activeSegmentNo, activeWrittenBytes,
                System.currentTimeMillis()));
    }

    // ==================== Replay ====================

    @Override
    /** Replay */
    public WalReplayResult replay(WalReplayHandler handler) throws IOException {
        CheckpointMeta meta = loadCheckpoint();
        return replay(meta.checkpointLsn() + 1, Long.MAX_VALUE, handler);
    }

    @Override
    /** Replay */
    public WalReplayResult replay(long fromLsn, WalReplayHandler handler) throws IOException {
        return replay(fromLsn, Long.MAX_VALUE, handler);
    }

    @Override
    /** Replay */
    public WalReplayResult replay(long fromLsn, long toLsn, WalReplayHandler handler) throws IOException {
        ensureOpen();
        CheckpointMeta meta = loadCheckpoint();
        List<WalRecord> records = new ArrayList<>();
        List<WalSegmentInfo> segments = scanSegments();
        for (WalSegmentInfo seg : segments) {
            if (seg.lastLsn() < fromLsn) {
                continue;
            }
            if (seg.firstLsn() >= toLsn) {
                break;
            }
            replaySegmentInto(seg, fromLsn, toLsn, handler, records);
        }
        return new WalReplayResult(meta, records);
    }

    /**
      * replaysegmentinto
     * @param seg seg
     * @param fromLsn 从lsn
     * @param toLsn 转为lsn
     * @param handler 处理器
     * @param records records
     */
    private void replaySegmentInto(WalSegmentInfo seg, long fromLsn, long toLsn,
                                   WalReplayHandler handler,
                                   List<WalRecord> records) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(seg.path())))) {
            while (true) {
                byte[] header;
                try {
                    header = new byte[WalConfig.RECORD_HEADER_BYTES];
                    in.readFully(header);
                } catch (EOFException stop) {
                    break;
                }
                int len = readInt(header, WalConfig.CRC32_BYTES + WalConfig.LSN_BYTES + WalConfig.OP_BYTES);
                long lsn = readLong(header, WalConfig.CRC32_BYTES);
                byte op = header[WalConfig.CRC32_BYTES + WalConfig.LSN_BYTES];
                byte[] payload = new byte[len];
                try {
                    in.readFully(payload);
                } catch (EOFException stop) {
                    break;
                }
                long expectedCrc = readInt(header, 0) & 0xFFFFFFFFL;
                long actualCrc = crc32(op, payload);
                if (expectedCrc != actualCrc) {
                    throw WalException.crcMismatch(lsn);
                }
                if (lsn >= fromLsn && lsn < toLsn) {
                    WalRecord record = new WalRecord(lsn, op, payload);
                    records.add(record);
                    try {
                        boolean cont = handler.onRecord(lsn, op, payload);
                        if (!cont) {
                            break;
                        }
                    } catch (WalException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new WalException("WAL 回放处理器异常: lsn=" + lsn, e);
                    }
                }
            }
        }
    }

    // ==================== Chain ====================

    @Override
    /** 追加Chain */
    public long appendChain(WalChainHandler handler) throws IOException {
        ensureOpen();
        List<WalOp> ops = new ArrayList<>();
        WalChainImpl chain = new WalChainImpl(ops);
        try {
            handler.apply(chain);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new WalException("WAL 链式写入异常", e);
        }
        long lastLsn = currentLsn;
        for (WalOp op : ops) {
            lastLsn = append(op.op(), op.payload());
        }
        return lastLsn;
    }

    // ==================== Query ====================

    @Override
    /** 查找bylsn */
    public Optional<WalRecord> findByLsn(long lsn) throws IOException {
        ensureOpen();
        List<WalSegmentInfo> segments = scanSegments();
        for (WalSegmentInfo seg : segments) {
            if (lsn < seg.firstLsn() || lsn > seg.lastLsn()) {
                continue;
            }
            Optional<WalRecord> found = findInSegment(seg, lsn);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * 查找入segment
     *
     * @param seg seg
     * @param lsn lsn
     * @return find入segment的结果
     */
    private Optional<WalRecord> findInSegment(WalSegmentInfo seg, long lsn) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(seg.path())))) {
            while (true) {
                byte[] header;
                try {
                    header = new byte[WalConfig.RECORD_HEADER_BYTES];
                    in.readFully(header);
                } catch (EOFException stop) {
                    return Optional.empty();
                }
                int len = readInt(header, WalConfig.CRC32_BYTES + WalConfig.LSN_BYTES + WalConfig.OP_BYTES);
                long curLsn = readLong(header, WalConfig.CRC32_BYTES);
                byte op = header[WalConfig.CRC32_BYTES + WalConfig.LSN_BYTES];
                byte[] payload = new byte[len];
                try {
                    in.readFully(payload);
                } catch (EOFException stop) {
                    return Optional.empty();
                }
                long expectedCrc = readInt(header, 0) & 0xFFFFFFFFL;
                long actualCrc = crc32(op, payload);
                if (expectedCrc != actualCrc) {
                    throw WalException.crcMismatch(curLsn);
                }
                if (curLsn == lsn) {
                    return Optional.of(new WalRecord(curLsn, op, payload));
                }
            }
        }
    }

    @Override
    /** purgecheckpointed */
    public int purgeCheckpointed(int keepSegments) {
        List<WalSegmentInfo> all = scanSegments();
        int deleted = 0;
        int eligible = all.size() - keepSegments;
        for (int i = 0; i < eligible && i < all.size(); i++) {
            WalSegmentInfo seg = all.get(i);
            if (seg.segmentNo() == activeSegmentNo) {
                continue;
            }
            if (seg.lastLsn() > checkpointLsn) {
                continue;
            }
            try {
                Files.deleteIfExists(seg.path());
                deleted++;
            } catch (IOException ignored) {
            }
        }
        return deleted;
    }

    @Override
    /** 当前segment */
    public WalSegmentInfo currentSegment() {
        Path p = segmentPath(activeSegmentNo);
        return new WalSegmentInfo(activeSegmentNo, activeFirstLsn, currentLsn,
                activeRecordCount, p, true);
    }

    @Override
    /** 列表segments */
    public List<WalSegmentInfo> listSegments() {
        return scanSegments();
    }

    @Override
    /** 关闭 */
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            force();
        } catch (IOException ignored) {
        }
        if (activeOut != null) {
            try {
                activeOut.flush();
                activeOut.close();
            } catch (IOException ignored) {
            }
            activeOut = null;
        }
        if (activeChannel != null) {
            try {
                activeChannel.close();
            } catch (IOException ignored) {
            }
            activeChannel = null;
        }
        if (activeRaf != null) {
            try {
                activeRaf.close();
            } catch (IOException ignored) {
            }
            activeRaf = null;
        }
    }

    /** Ensure打开 */
    private void ensureOpen() {
        if (closed) {
            throw new WalException("WAL 已关闭");
        }
    }

    /**
     * 构建主体
     *
     * @param lsn lsn
     * @param op op
     * @param payload payload
     * @return 构建主体的结果
     */
    private byte[] buildBody(long lsn, byte op, byte[] payload) {
        crc.reset();
        crc.update(op);
        crc.update(payload);
        long crcValue = crc.getValue();
        int total = WalConfig.RECORD_HEADER_BYTES + payload.length;
        byte[] body = new byte[total];
        writeInt(body, 0, (int) crcValue);
        writeLong(body, WalConfig.CRC32_BYTES, lsn);
        body[WalConfig.CRC32_BYTES + WalConfig.LSN_BYTES] = op;
        writeInt(body, WalConfig.CRC32_BYTES + WalConfig.LSN_BYTES + WalConfig.OP_BYTES, payload.length);
        System.arraycopy(payload, 0, body, WalConfig.RECORD_HEADER_BYTES, payload.length);
        return body;
    }

    /**
     * Crc
     *
     * @param op op
     * @param payload payload
     * @return crc32的结果
     */
    private long crc32(byte op, byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(op);
        crc.update(payload);
        return crc.getValue();
    }

    /**
     * 写入Int
     *
     * @param dst dst
     * @param offset 偏移量
     * @param value 值
     */
    private static void writeInt(byte[] dst, int offset, int value) {
        dst[offset] = (byte) ((value >>> 24) & 0xFF);
        dst[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        dst[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        dst[offset + 3] = (byte) (value & 0xFF);
    }

    /**
     * 写入Long
     *
     * @param dst dst
     * @param offset 偏移量
     * @param value 值
     */
    private static void writeLong(byte[] dst, int offset, long value) {
        dst[offset] = (byte) ((value >>> 56) & 0xFF);
        dst[offset + 1] = (byte) ((value >>> 48) & 0xFF);
        dst[offset + 2] = (byte) ((value >>> 40) & 0xFF);
        dst[offset + 3] = (byte) ((value >>> 32) & 0xFF);
        dst[offset + 4] = (byte) ((value >>> 24) & 0xFF);
        dst[offset + 5] = (byte) ((value >>> 16) & 0xFF);
        dst[offset + 6] = (byte) ((value >>> 8) & 0xFF);
        dst[offset + 7] = (byte) (value & 0xFF);
    }

    /**
     * 读取Int
     *
     * @param src src
     * @param offset 偏移量
     * @return 读取int的结果
     */
    private static int readInt(byte[] src, int offset) {
        return ((src[offset] & 0xFF) << 24)
                | ((src[offset + 1] & 0xFF) << 16)
                | ((src[offset + 2] & 0xFF) << 8)
                | (src[offset + 3] & 0xFF);
    }

    /**
     * 读取Long
     *
     * @param src src
     * @param offset 偏移量
     * @return 读取long的结果
     */
    private static long readLong(byte[] src, int offset) {
        return ((long) (src[offset] & 0xFF) << 56)
                | ((long) (src[offset + 1] & 0xFF) << 48)
                | ((long) (src[offset + 2] & 0xFF) << 40)
                | ((long) (src[offset + 3] & 0xFF) << 32)
                | ((long) (src[offset + 4] & 0xFF) << 24)
                | ((long) (src[offset + 5] & 0xFF) << 16)
                | ((long) (src[offset + 6] & 0xFF) << 8)
                | ((long) (src[offset + 7] & 0xFF));
    }

    /**
     * 链式写入实现。
     */
    private static final class WalChainImpl implements WalChain {

        /** OPS */
        private final List<WalOp> ops;

        WalChainImpl(List<WalOp> ops) {
            this.ops = ops;
        }

        @Override
        /** 添加 */
        public WalChain add(byte op, byte[] payload) {
            ops.add(new WalOp(op, payload));
            return this;
        }

        @Override
        /** 添加 */
        public WalChain add(byte op, String s) {
            byte[] bytes = s == null ? new byte[0] : s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ops.add(new WalOp(op, bytes));
            return this;
        }

        @Override
        /** 添加 */
        public WalChain add(byte op) {
            ops.add(new WalOp(op, new byte[0]));
            return this;
        }

        @Override
        /** 获取大小 */
        public int size() {
            return ops.size();
        }
    }
}
