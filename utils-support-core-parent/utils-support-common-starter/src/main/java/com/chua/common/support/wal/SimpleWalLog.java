package com.chua.common.support.wal;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32;

/**
 * 单文件 WAL 实现，不分片。
 *
 * <p>适合小数据量场景（&lt; 10w 条记录）。大数据量请使用 {@link SegmentWalLog}。</p>
 *
 * <h2>文件格式</h2>
 * <pre>
 * Header（一次写入后不再追加）:
 *   magic[N]
 * Records（顺序追加）:
 *   [crc32(4)][lsn(8)][op(1)][len(4)][payload(len)]
 * </pre>
 *
 * <h2>checkpoint 文件</h2>
 * <pre>
 * ${walDir}/checkpoint.meta:
 *   magic[4] = "CKPT"
 *   checkpointLsn[8]
 *   segmentNo[4]（固定 1）
 *   offset[8]
 *   timestamp[8]
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SimpleWalLog implements WalLog {

    /**
     * WAL 文件名
     */
    private static final String WAL_FILE_NAME = "wal.log";

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
     * WAL 单文件路径
     */
    private final Path walFile;

    /**
     * checkpoint 文件路径
     */
    private final Path checkpointFile;

    /**
     * 配置
     */
    private final WalConfig config;

    /**
     * RandomAccessFile（提供 FileChannel 以支持 fsync 和 mmap）
     */
    private RandomAccessFile raf;

    /**
     * FileChannel
     */
    private FileChannel channel;

    /**
     * 写入通道：mmap 模式下为 MappedByteBuffer，否则为 DataOutputStream（包装 FileChannel）
     */
    private DataOutputStream out;

    /**
     * mmap 缓冲区
     */
    private MappedByteBuffer mmapBuffer;

    /**
     * mmap 模式下当前写位置
     */
    private long mmapWritePos;

    /**
     * mmap 缓冲区大小（固定 16MB，超出需扩容）
     */
    private static final long MMAP_SIZE = 16L * 1024L * 1024L;

    /**
     * 当前最大 LSN
     */
    private long currentLsn;

    /**
     * 当前 checkpoint LSN
     */
    private long checkpointLsn;

    /**
     * 已 checkpoint 段内偏移
     */
    private long checkpointOffset;

    /**
     * 已写入字节数
     */
    private long writtenBytes;

    /**
     * 已写入记录数
     */
    private int recordCount;

    /**
     * 自上次 fsync 起累计写入条数
     */
    private int pendingFsyncOps;

    /**
     * 是否已关闭
     */
    private boolean closed;

    public SimpleWalLog(WalConfig config) throws IOException {
        this.config = config;
        this.walFile = config.walDir().resolve(WAL_FILE_NAME);
        this.checkpointFile = config.walDir().resolve(CHECKPOINT_FILE_NAME);
        Files.createDirectories(walFile.getParent());
        open();
    }

    private void open() throws IOException {
        // 打开 RandomAccessFile（支持读写 + 追加 + mmap）
        this.raf = new RandomAccessFile(walFile.toFile(), "rw");
        this.channel = raf.getChannel();

        boolean exists = Files.exists(walFile);
        if (!exists) {
            // 首次创建：写 magic
            if (config.useMemoryMap()) {
                this.mmapBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, MMAP_SIZE);
                writeMagicToMmap();
            } else {
                this.out = new DataOutputStream(new java.io.BufferedOutputStream(
                        new java.io.FileOutputStream(raf.getFD())));
                out.write(config.magic());
                out.flush();
            }
            if (config.syncOnWrite()) {
                force();
            }
        } else {
            // 已存在：扫描最后 LSN 与记录数
            long len = raf.length();
            this.writtenBytes = len;
            scanTail(len);
            if (config.useMemoryMap()) {
                long mapSize = Math.max(MMAP_SIZE, len + MMAP_SIZE);
                this.mmapBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, mapSize);
                this.mmapWritePos = len;
            } else {
                raf.seek(len);
                this.out = new DataOutputStream(new java.io.BufferedOutputStream(
                        new java.io.FileOutputStream(raf.getFD())));
            }
        }

        // 加载 checkpoint
        CheckpointMeta meta = readCheckpointFromDisk();
        this.checkpointLsn = meta.checkpointLsn();
        this.checkpointOffset = meta.checkpointOffset();
    }

    private void writeMagicToMmap() throws IOException {
        byte[] magic = config.magic();
        mmapBuffer.put(magic);
        mmapWritePos = magic.length;
        writtenBytes = magic.length;
    }

    private void scanTail(long len) throws IOException {
        if (len <= config.magic().length) {
            this.currentLsn = 0L;
            this.recordCount = 0;
            return;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(walFile)))) {
            byte[] magic = config.magic();
            byte[] head = new byte[magic.length];
            in.readFully(head);
            long lastLsn = 0L;
            int count = 0;
            try {
                while (true) {
                    byte[] header = new byte[WalConfig.RECORD_HEADER_BYTES];
                    in.readFully(header);
                    int lenField = readInt(header, WalConfig.CRC32_BYTES + WalConfig.LSN_BYTES + WalConfig.OP_BYTES);
                    long lsn = readLong(header, WalConfig.CRC32_BYTES);
                    byte[] payload = new byte[lenField];
                    in.readFully(payload);
                    lastLsn = lsn;
                    count++;
                }
            } catch (EOFException stop) {
                // 正常结束
            }
            this.currentLsn = lastLsn;
            this.recordCount = count;
        }
    }

    @Override
    public long append(byte op, byte[] payload) throws IOException {
        ensureOpen();
        if (payload == null) {
            payload = new byte[0];
        }
        long lsn = currentLsn + 1;
        byte[] body = buildBody(lsn, op, payload);
        if (config.useMemoryMap()) {
            ensureMmapCapacity(mmapWritePos + body.length);
            mmapBuffer.put(body);
            mmapWritePos += body.length;
        } else {
            out.write(body);
            out.flush();
        }
        writtenBytes += body.length;
        recordCount++;
        currentLsn = lsn;
        pendingFsyncOps++;
        maybeFsync();
        return lsn;
    }

    private void ensureMmapCapacity(long needed) throws IOException {
        if (mmapBuffer == null) {
            return;
        }
        if (needed <= MMAP_SIZE) {
            return;
        }
        long newSize = needed + MMAP_SIZE;
        force();
        unmapMmap();
        mmapBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, newSize);
    }

    /**
     * 反射 unmap MappedByteBuffer，兼容 JDK 9+ 模块系统。
     */
    private void unmapMmap() {
        if (mmapBuffer == null || mmapBufferRef == null) {
            return;
        }
        try {
            Object result = mmapBufferRef.invoke(null, mmapBuffer);
            if (result instanceof Boolean) {
                // sun.misc.Unsafe.invokeCleaner 返回 boolean
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static final java.lang.reflect.Method mmapBufferRef;

    static {
        java.lang.reflect.Method m = null;
        try {
            Class<?> unsafeCls = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field f = unsafeCls.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object unsafe = f.get(null);
            m = unsafeCls.getMethod("invokeCleaner", java.nio.ByteBuffer.class);
            // 静态调用 invokeCleaner 时无需 unsafe 实例（因为是 static）
        } catch (ReflectiveOperationException ignored) {
        }
        mmapBufferRef = m;
    }

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
    public void sync() throws IOException {
        ensureOpen();
        force();
        pendingFsyncOps = 0;
    }

    /**
     * 真正 fsync：调用 FileChannel.force(true)。
     *
     * @throws IOException IO 异常
     */
    private void force() throws IOException {
        if (config.useMemoryMap() && mmapBuffer != null) {
            mmapBuffer.force();
        } else if (out != null) {
            out.flush();
        }
        if (channel != null) {
            channel.force(true);
        }
    }

    @Override
    public long currentLsn() {
        return currentLsn;
    }

    // ==================== Checkpoint ====================

    @Override
    public CheckpointMeta loadCheckpoint() throws IOException {
        ensureOpen();
        return readCheckpointFromDisk();
    }

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
    public void markCheckpoint(long lsn) throws IOException {
        ensureOpen();
        if (lsn > currentLsn) {
            lsn = currentLsn;
        }
        this.checkpointLsn = lsn;
        this.checkpointOffset = writtenBytes;
        writeCheckpointToDisk(new CheckpointMeta(lsn, 1, checkpointOffset,
                System.currentTimeMillis()));
    }

    @Override
    public void resetCheckpoint() throws IOException {
        ensureOpen();
        this.checkpointLsn = 0L;
        this.checkpointOffset = 0L;
        try {
            Files.deleteIfExists(checkpointFile);
        } catch (IOException ignored) {
        }
    }

    @Override
    public void forceCheckpoint(long lsn) throws IOException {
        ensureOpen();
        this.checkpointLsn = lsn;
        this.checkpointOffset = writtenBytes;
        writeCheckpointToDisk(new CheckpointMeta(lsn, 1, checkpointOffset,
                System.currentTimeMillis()));
    }

    // ==================== Replay ====================

    @Override
    public WalReplayResult replay(WalReplayHandler handler) throws IOException {
        CheckpointMeta meta = loadCheckpoint();
        return replay(meta.checkpointLsn() + 1, Long.MAX_VALUE, handler);
    }

    @Override
    public WalReplayResult replay(long fromLsn, WalReplayHandler handler) throws IOException {
        return replay(fromLsn, Long.MAX_VALUE, handler);
    }

    @Override
    public WalReplayResult replay(long fromLsn, long toLsn, WalReplayHandler handler) throws IOException {
        ensureOpen();
        CheckpointMeta meta = loadCheckpoint();
        List<WalRecord> records = new ArrayList<>();
        if (!Files.exists(walFile) || Files.size(walFile) <= config.magic().length) {
            return new WalReplayResult(meta, records);
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(walFile)))) {
            byte[] magic = config.magic();
            byte[] head = new byte[magic.length];
            in.readFully(head);
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
        return new WalReplayResult(meta, records);
    }

    // ==================== Chain ====================

    @Override
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
    public Optional<WalRecord> findByLsn(long lsn) throws IOException {
        ensureOpen();
        if (!Files.exists(walFile) || Files.size(walFile) <= config.magic().length) {
            return Optional.empty();
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(walFile)))) {
            byte[] magic = config.magic();
            byte[] head = new byte[magic.length];
            in.readFully(head);
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
    public int purgeCheckpointed(int keepSegments) {
        return 0;
    }

    @Override
    public WalSegmentInfo currentSegment() {
        return new WalSegmentInfo(1,
                currentLsn == 0 ? 0 : 1,
                currentLsn,
                recordCount,
                walFile,
                true);
    }

    @Override
    public List<WalSegmentInfo> listSegments() {
        return Collections.singletonList(currentSegment());
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            force();
        } catch (IOException ignored) {
        }
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
            }
            out = null;
        }
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            channel = null;
        }
        if (raf != null) {
            try {
                raf.close();
            } catch (IOException ignored) {
            }
            raf = null;
        }
        unmapMmap();
        mmapBuffer = null;
    }

    private void ensureOpen() {
        if (closed) {
            throw new WalException("WAL 已关闭: " + walFile);
        }
    }

    private byte[] buildBody(long lsn, byte op, byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(op);
        crc.update(payload);
        long crcValue = crc.getValue();
        byte[] body = new byte[WalConfig.RECORD_HEADER_BYTES + payload.length];
        writeInt(body, 0, (int) crcValue);
        writeLong(body, WalConfig.CRC32_BYTES, lsn);
        body[WalConfig.CRC32_BYTES + WalConfig.LSN_BYTES] = op;
        writeInt(body, WalConfig.CRC32_BYTES + WalConfig.LSN_BYTES + WalConfig.OP_BYTES, payload.length);
        System.arraycopy(payload, 0, body, WalConfig.RECORD_HEADER_BYTES, payload.length);
        return body;
    }

    private long crc32(byte op, byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(op);
        crc.update(payload);
        return crc.getValue();
    }

    private static void writeInt(byte[] dst, int offset, int value) {
        dst[offset] = (byte) ((value >>> 24) & 0xFF);
        dst[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        dst[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        dst[offset + 3] = (byte) (value & 0xFF);
    }

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

    private static int readInt(byte[] src, int offset) {
        return ((src[offset] & 0xFF) << 24)
                | ((src[offset + 1] & 0xFF) << 16)
                | ((src[offset + 2] & 0xFF) << 8)
                | (src[offset + 3] & 0xFF);
    }

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

        private final List<WalOp> ops;

        WalChainImpl(List<WalOp> ops) {
            this.ops = ops;
        }

        @Override
        public WalChain add(byte op, byte[] payload) {
            ops.add(new WalOp(op, payload));
            return this;
        }

        @Override
        public WalChain add(byte op, String s) {
            byte[] bytes = s == null ? new byte[0] : s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ops.add(new WalOp(op, bytes));
            return this;
        }

        @Override
        public WalChain add(byte op) {
            ops.add(new WalOp(op, new byte[0]));
            return this;
        }

        @Override
        public int size() {
            return ops.size();
        }
    }
}
