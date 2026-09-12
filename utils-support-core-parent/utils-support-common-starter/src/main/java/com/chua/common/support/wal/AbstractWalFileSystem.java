package com.chua.common.support.wal;

import com.chua.common.support.datasource.wal.WalStoreConfig;
import com.chua.common.support.file.FileSystem;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

/**
 * WAL 文件系统抽象基类，复用 {@link SegmentWalLog} 实现追加写、checkpoint、replay。
 *
 * <h3>文件布局</h3>
 * <pre>
   * ${basedir}/_wal/
 *   ${namespace}-000001.wal
 *   checkpoint.meta
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public abstract class AbstractWalFileSystem implements WalFileSystem {

    public static final byte OP_TOMBSTONE = (byte) 0x80; // OP_TOMBSTONE
    protected final WalStoreConfig config; // 配置
    protected final SegmentWalLog walLog; // wal日志
    protected final AtomicLong currentLsn = new AtomicLong(0); // 当前lsn

    /**
     * 抽象wal文件系统。
     * @param config 配置
     */
    protected AbstractWalFileSystem(WalStoreConfig config) throws IOException {
        this.config = config;
        Path walDir = config.baseDir().resolve("_wal");
        Files.createDirectories(walDir);
        WalConfig walCfg = WalConfig.builder()
                .walDir(walDir)
                .namespace(config.namespace())
                .impl(WalConfig.WalImpl.SEGMENT)
                .syncOnWrite(false)
                .fsyncBatchSize(config.flushBatchSize())
                .fsyncBatchIntervalMs(config.flushIntervalMs())
                .maxSegmentBytes(config.segmentBytes())
                .maxRecordsPerSegment(0)
                .build();
        this.walLog = (SegmentWalLog) WalFactory.open(walCfg);
        this.currentLsn.set(this.walLog.currentLsn());
    }

    // ==================== WAL 核心操作 ====================

    @Override
    public long append(byte op, byte[] payload) throws IOException {
        return walLog.append(op, payload == null ? new byte[0] : payload.clone());
    }

    @Override
    public void sync() throws IOException { walLog.sync(); }

    @Override
    public long currentLsn() { return currentLsn.get(); }

    @Override
    public Optional<WalRecord> readByLsn(long lsn) throws IOException {
        return walLog.findByLsn(lsn);
    }

    @Override
    public byte[] readAt(int segmentNo, long offset, int length) throws IOException {
        Path segFile = getSegmentPath(segmentNo);
        if (segFile == null || !Files.exists(segFile)) {
            return null;
        }
        try (FileChannel ch = FileChannel.open(segFile, StandardOpenOption.READ)) {
            if (offset + length > ch.size()) {
                return null;
            }
            ByteBuffer buf = ByteBuffer.allocate(length);
            ch.read(buf, offset);
            return buf.array();
        }
    }

    @Override
    public List<WalSegmentInfo> listSegments() throws IOException { return walLog.listSegments(); }

    @Override
    public WalSegmentInfo currentSegment() { return walLog.currentSegment(); }

    @Override
    public CheckpointMeta loadCheckpoint() throws IOException { return walLog.loadCheckpoint(); }

    @Override
    public void markCheckpoint(long lsn) throws IOException { walLog.markCheckpoint(lsn); }

    @Override
    public void compact() throws IOException { walLog.purgeCheckpointed(1); }

    // ==================== FileSystem 接口 ====================

    @Override
    public String getType() { return "wal-" + opType(); }

    @Override
    public com.chua.common.support.file.builder.ReadBuilder read(File file) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.chua.common.support.file.builder.WriteBuilder write(File file) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() throws IOException { walLog.close(); }

    // ==================== 子类扩展点 ====================

    /**
     * op类型。
     * @return op类型的结果
     */
    protected abstract byte opType();
    /**
     * decode键。
     * @param payload payload
     * @return decode键的结果
     */
    protected abstract String decodeKey(byte[] payload);

    /**
     * 配置。
     * @return 配置的结果
     */
    public WalStoreConfig config() { return config; }

    /**
     * 获取segment路径。
     * @param segmentNo segmentno
     * @return 获取segment路径的结果
     */
    protected Path getSegmentPath(int segmentNo) {
        return config.baseDir().resolve("_wal")
                .resolve(String.format("%s-%06d.wal", config.namespace(), segmentNo));
    }

    /**
     * 是否tombstone。
     * @param op op
     * @return 是否tombstone的结果
     */
    public static boolean isTombstone(byte op) {
        return (op & OP_TOMBSTONE) != 0;
    }
}
