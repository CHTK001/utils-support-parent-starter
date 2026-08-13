package com.chua.common.support.wal;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * WAL 配置。
 *
 * <p>使用 {@link Builder} 构造，不可变。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class WalConfig {

    /**
     * 默认文件魔数 "WAL1"
     */
    public static final byte[] DEFAULT_MAGIC = new byte[]{'W', 'A', 'L', '1'};

    /**
     * 默认单条记录头 CRC32 长度
     */
    public static final int CRC32_BYTES = 4;

    /**
     * 默认单条记录 LSN 字节数
     */
    public static final int LSN_BYTES = 8;

    /**
     * 默认操作类型字节数
     */
    public static final int OP_BYTES = 1;

    /**
     * 默认 payload 长度字段字节数
     */
    public static final int LEN_BYTES = 4;

    /**
     * 单条记录固定头大小（crc + lsn + op + len）
     */
    public static final int RECORD_HEADER_BYTES = CRC32_BYTES + LSN_BYTES + OP_BYTES + LEN_BYTES;

    /**
     * WAL 目录或单文件目录
     */
    private final Path walDir;

    /**
     * 命名空间（同一目录下多业务隔离文件名前缀）
     */
    private final String namespace;

    /**
     * WAL 实现类型
     */
    private final WalImpl impl;

    /**
     * 是否每次写入后 fsync
     */
    private final boolean syncOnWrite;

    /**
     * fsync 批量大小（每 N 条 flush 后 force 一次，<=1 表示每次都 fsync）
     */
    private final int fsyncBatchSize;

    /**
     * fsync 批量间隔（毫秒，后台线程定时强制刷盘）
     */
    private final long fsyncBatchIntervalMs;

    /**
     * 是否使用 mmap（MEMORY_MAP 模式，写入走 RandomAccessWriter；读取走 MappedByteBuffer）
     */
    private final boolean useMemoryMap;

    /**
     * 分片最大字节数（仅 SEGMENT 生效）
     */
    private final long maxSegmentBytes;

    /**
     * 分片最大记录数（仅 SEGMENT 生效）
     */
    private final int maxRecordsPerSegment;

    /**
     * 保留多少个已 checkpoint 分片
     */
    private final int keepCheckpointedSegments;

    /**
     * 文件魔数（用于识别 WAL 文件）
     */
    private final byte[] magic;

    private WalConfig(Builder b) {
        this.walDir = b.walDir;
        this.namespace = b.namespace;
        this.impl = b.impl;
        this.syncOnWrite = b.syncOnWrite;
        this.fsyncBatchSize = b.fsyncBatchSize;
        this.fsyncBatchIntervalMs = b.fsyncBatchIntervalMs;
        this.useMemoryMap = b.useMemoryMap;
        this.maxSegmentBytes = b.maxSegmentBytes;
        this.maxRecordsPerSegment = b.maxRecordsPerSegment;
        this.keepCheckpointedSegments = b.keepCheckpointedSegments;
        this.magic = b.magic.clone();
    }

    /**
     * WAL 目录路径。
     *
     * @return 目录路径
     */
    public Path walDir() {
        return walDir;
    }

    /**
     * 命名空间。
     *
     * @return 命名空间字符串
     */
    public String namespace() {
        return namespace;
    }

    /**
     * WAL 实现类型。
     *
     * @return 实现枚举
     */
    public WalImpl impl() {
        return impl;
    }

    /**
     * 是否每次写入后强制 fsync。
     *
     * @return true=fsync
     */
    public boolean syncOnWrite() {
        return syncOnWrite;
    }

    /**
     * fsync 批量大小。
     *
     * @return 批大小
     */
    public int fsyncBatchSize() {
        return fsyncBatchSize;
    }

    /**
     * fsync 批量间隔（毫秒）。
     *
     * @return 间隔毫秒
     */
    public long fsyncBatchIntervalMs() {
        return fsyncBatchIntervalMs;
    }

    /**
     * 是否使用 mmap 读写。
     *
     * @return true=mmap
     */
    public boolean useMemoryMap() {
        return useMemoryMap;
    }

    /**
     * 分片最大字节数（仅 SEGMENT 生效）。
     *
     * @return 字节数
     */
    public long maxSegmentBytes() {
        return maxSegmentBytes;
    }

    /**
     * 分片最大记录数（仅 SEGMENT 生效）。
     *
     * @return 记录数
     */
    public int maxRecordsPerSegment() {
        return maxRecordsPerSegment;
    }

    /**
     * 保留多少个已 checkpoint 分片。
     *
     * @return 保留数量
     */
    public int keepCheckpointedSegments() {
        return keepCheckpointedSegments;
    }

    /**
     * 文件魔数。
     *
     * @return 魔数字节副本
     */
    public byte[] magic() {
        return magic.clone();
    }

    /**
     * 创建 Builder。
     *
     * @return Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * WAL 实现类型枚举。
     *
     * @author CH
     * @since 4.0.0.42
     */
    public enum WalImpl {

        /**
         * 单文件实现，不分片
         */
        SIMPLE,

        /**
         * 分片 + 索引实现
         */
        SEGMENT,

        /**
         * 基于 Chronicle Queue 的实现（由 utils-support-chronicle-starter 提供）
         */
        CHRONICLE,

        /**
         * 基于 Kafka 的实现（由 utils-support-kafka-starter 提供）
         */
        KAFKA
    }

    /**
     * WAL 配置 Builder。
     *
     * @author CH
     * @since 4.0.0.42
     */
    public static final class Builder {

    /**
     * WAL 目录
     */
    private Path walDir = Paths.get("./wal");

    /**
     * 命名空间（默认 "default"）
     */
    private String namespace = "default";

    /**
     * 实现类型（默认 SEGMENT）
     */
    private WalImpl impl = WalImpl.SEGMENT;

    /**
     * 是否 fsync（默认 true）
     */
    private boolean syncOnWrite = true;

    /**
     * fsync 批量大小（默认 100）
     */
    private int fsyncBatchSize = 100;

    /**
     * fsync 批量间隔毫秒（默认 50ms）
     */
    private long fsyncBatchIntervalMs = 50L;

    /**
     * 是否使用 mmap（默认 false，SIMPLE 模式下生效）
     */
    private boolean useMemoryMap = false;

    /**
     * 分片最大字节数（默认 64MB）
     */
    private long maxSegmentBytes = 64L * 1024L * 1024L;

    /**
     * 分片最大记录数（默认 100000）
     */
    private int maxRecordsPerSegment = 100_000;

    /**
     * 保留 checkpoint 分片数（默认 1）
     */
    private int keepCheckpointedSegments = 1;

    /**
     * 文件魔数（默认 "WAL1"）
     */
    private byte[] magic = DEFAULT_MAGIC.clone();

    /**
     * 设置 WAL 目录。
     */
    public Builder walDir(Path walDir) {
        this.walDir = walDir;
        return this;
    }

    /**
     * 设置命名空间。
     */
    public Builder namespace(String namespace) {
        this.namespace = namespace == null ? "default" : namespace;
        return this;
    }

    /**
     * 设置实现类型。
     */
    public Builder impl(WalImpl impl) {
        this.impl = impl;
        return this;
    }

    /**
     * 设置是否 fsync。
     */
    public Builder syncOnWrite(boolean syncOnWrite) {
        this.syncOnWrite = syncOnWrite;
        return this;
    }

    /**
     * 设置 fsync 批量大小。
     */
    public Builder fsyncBatchSize(int fsyncBatchSize) {
        this.fsyncBatchSize = Math.max(1, fsyncBatchSize);
        return this;
    }

    /**
     * 设置 fsync 批量间隔毫秒。
     */
    public Builder fsyncBatchIntervalMs(long fsyncBatchIntervalMs) {
        this.fsyncBatchIntervalMs = Math.max(0L, fsyncBatchIntervalMs);
        return this;
    }

    /**
     * 设置是否使用 mmap。
     */
    public Builder useMemoryMap(boolean useMemoryMap) {
        this.useMemoryMap = useMemoryMap;
        return this;
    }

        /**
         * 设置分片最大字节数。
         */
        public Builder maxSegmentBytes(long maxSegmentBytes) {
            this.maxSegmentBytes = maxSegmentBytes;
            return this;
        }

        /**
         * 设置分片最大记录数。
         */
        public Builder maxRecordsPerSegment(int maxRecordsPerSegment) {
            this.maxRecordsPerSegment = maxRecordsPerSegment;
            return this;
        }

        /**
         * 设置保留 checkpoint 分片数。
         */
        public Builder keepCheckpointedSegments(int keepCheckpointedSegments) {
            this.keepCheckpointedSegments = keepCheckpointedSegments;
            return this;
        }

        /**
         * 设置文件魔数。
         */
        public Builder magic(byte[] magic) {
            this.magic = magic == null ? DEFAULT_MAGIC.clone() : magic.clone();
            return this;
        }

        /**
         * 构建 WalConfig 实例。
         *
         * @return WalConfig
         */
        public WalConfig build() {
            return new WalConfig(this);
        }
    }
}
