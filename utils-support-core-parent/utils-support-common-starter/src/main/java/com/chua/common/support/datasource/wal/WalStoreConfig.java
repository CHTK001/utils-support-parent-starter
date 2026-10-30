package com.chua.common.support.datasource.wal;

import java.nio.file.Path;
import lombok.ToString;

/**
 * WAL 存储系统自动检测配置。
 *
 * <p>由 {@link WalStoreEnvDetector} 根据运行时环境自动计算，
 * 包含分片数、段大小、刷盘策略等关键参数。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@ToString
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

    /**
     * shard Count
     * @return 返回值
    */
    public int shardCount() {
        return shardCount;
    }
    /**
     * segment Bytes
     * @return 返回值
    */
    public long segmentBytes() {
        return segmentBytes;
    }
    /**
     * flush Batch Size
     * @return 返回值
    */
    public int flushBatchSize() {
        return flushBatchSize;
    }
    /**
     * flush Interval Ms
     * @return 返回值
    */
    public long flushIntervalMs() {
        return flushIntervalMs;
    }
    /**
     * cpu Cores
     * @return 返回值
    */
    public int cpuCores() {
        return cpuCores;
    }
    /**
     * is SS D
     * @return 返回值
    */
    public boolean isSSD() {
        return isSSD;
    }
    /**
     * base Dir
     * @return 返回值
    */
    public Path baseDir() {
        return baseDir;
    }
    /**
     * namespace
     * @return 返回值
    */
    public String namespace() {
        return namespace;
    }

    /**
     * builder
     * @return 返回值
    */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int shardCount = 100;
        private long segmentBytes = 50L * 1024 * 1024;
        private int flushBatchSize = 1024;
        private long flushIntervalMs = 500L;
        private int cpuCores = Runtime.getRuntime().availableProcessors();
        private boolean isSSD = false;
        private Path baseDir;
        private String namespace = "default";

        /**
         * shard Count
         * @param n 整数
         * @return 返回值
        */
        public Builder shardCount(int n) {
            this.shardCount = n;
            return this;
        }
        /**
         * segment Bytes
         * @param bytes 长整数
         * @return 返回值
        */
        public Builder segmentBytes(long bytes) {
            this.segmentBytes = bytes;
            return this;
        }
        /**
         * flush Batch Size
         * @param n 整数
         * @return 返回值
        */
        public Builder flushBatchSize(int n) {
            this.flushBatchSize = n;
            return this;
        }
        /**
         * flush Interval Ms
         * @param ms 长整数
         * @return 返回值
        */
        public Builder flushIntervalMs(long ms) {
            this.flushIntervalMs = ms;
            return this;
        }
        /**
         * cpu Cores
         * @param n 整数
         * @return 返回值
        */
        public Builder cpuCores(int n) {
            this.cpuCores = n;
            return this;
        }
        /**
         * is SS D
         * @param v 布尔值
         * @return 返回值
        */
        public Builder isSSD(boolean v) {
            this.isSSD = v;
            return this;
        }
        /**
         * base Dir
         * @param p 路径
         * @return 返回值
        */
        public Builder baseDir(Path p) {
            this.baseDir = p;
            return this;
        }
        /**
         * namespace
         * @param ns 字符串
         * @return 返回值
        */
        public Builder namespace(String ns) {
            this.namespace = ns;
            return this;
        }

        /**
         * 构建
         * @return 返回值
        */
        public WalStoreConfig build() {
            return new WalStoreConfig(this);
        }
    }
}
