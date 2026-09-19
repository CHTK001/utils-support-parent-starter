package com.chua.common.support.datasource.wal;

import java.nio.file.Path;

/**
 * WAL 存储系统自动检测配置。
 *
 * <p>由 {@link WalStoreEnvDetector} 根据运行时环境自动计算，
 * 包含分片数、段大小、刷盘策略等关键参数。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WalStoreConfig {

    private final int shardCount;
    private final long segmentBytes;
    private final int flushBatchSize;
    private final long flushIntervalMs;
    private final int cpuCores;
    private final boolean isSSD;
    private final Path baseDir;
    private final String namespace;

    /**
     * 构造方法，创建 WalStore配置 实例。
     *
     * @param b 方法入参 b
     */
    private WalStoreConfig(Builder b) {
        this.shardCount = b.shardCount;
        this.segmentBytes = b.segmentBytes;
        this.flushBatchSize = b.flushBatchSize;
        this.flushIntervalMs = b.flushIntervalMs;
        this.cpuCores = b.cpuCores;
        this.isSSD = b.isSSD;
        this.baseDir = b.baseDir;
        this.namespace = b.namespace;
    }

    public int shardCount() { return shardCount; }
    public long segmentBytes() { return segmentBytes; }
    public int flushBatchSize() { return flushBatchSize; }
    public long flushIntervalMs() { return flushIntervalMs; }
    public int cpuCores() { return cpuCores; }
    public boolean isSSD() { return isSSD; }
    public Path baseDir() { return baseDir; }
    public String namespace() { return namespace; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private int shardCount = 100;
        private long segmentBytes = 50L * 1024 * 1024;
        private int flushBatchSize = 1024;
        private long flushIntervalMs = 500L;
        private int cpuCores = Runtime.getRuntime().availableProcessors();
        private boolean isSSD = false;
        private Path baseDir;
        private String namespace = "default";

        public Builder shardCount(int n) {
            this.shardCount = n;
            return this;
        }
        public Builder segmentBytes(long bytes) {
            this.segmentBytes = bytes;
            return this;
        }
        public Builder flushBatchSize(int n) {
            this.flushBatchSize = n;
            return this;
        }
        public Builder flushIntervalMs(long ms) {
            this.flushIntervalMs = ms;
            return this;
        }
        public Builder cpuCores(int n) {
            this.cpuCores = n;
            return this;
        }
        public Builder isSSD(boolean v) {
            this.isSSD = v;
            return this;
        }
        public Builder baseDir(Path p) {
            this.baseDir = p;
            return this;
        }
        public Builder namespace(String ns) {
            this.namespace = ns;
            return this;
        }

        public WalStoreConfig build() {
            return new WalStoreConfig(this);
        }
    }
}
