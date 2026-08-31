package com.chua.common.support.datasource.wal;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.vector.RuntimeDetector;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * WAL 存储系统环境检测器，实现 {@link RuntimeDetector} SPI。
 *
 * <p>根据可用内存、CPU 核数、磁盘类型自动计算最优配置参数，
 * 适用于亿级数据存储场景。</p>
 *
 * <pre>{@code
 * // 自动检测并生成配置
 * WalStoreEnvDetector detector = new WalStoreEnvDetector();
 * WalStoreConfig config = detector.detect(baseDir);
 *
 * // 或通过 SPI 获取
 * WalStoreConfig config = ServiceProvider.of(RuntimeDetector.class)
 *     .getNewExtension("wal-store-detector");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi(value = "wal-store-detector", order = 0)
public class WalStoreEnvDetector implements RuntimeDetector {

    /** 索引内存预算比例：可用内存的 25% 用于 B+Tree 索引 */
    private static final double INDEX_MEMORY_RATIO = 0.25;
    /** 每个 shard 索引节点约 20MB（含树结构开销） */
    private static final long BYTES_PER_SHARD_INDEX = 20_000_000L;
    /** 最小 shard 数（保证大表也能并行） */
    private static final int MIN_SHARDS = 10;
    /** 最大 shard 数（避免过多文件句柄） */
    private static final int MAX_SHARDS = 200;
    /** SSD 分段大小 100MB，HDD 50MB */
    private static final long SSD_SEGMENT_BYTES = 100L * 1024 * 1024;
    private static final long HDD_SEGMENT_BYTES = 50L * 1024 * 1024;

    @Override
    public String name() {
        return "wal-store-detector";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int priority() {
        return 0;
    }

    // ==================== 自动检测 ====================

    /**
     * 根据当前运行环境生成最优配置。
     *
     * @param baseDir 数据存储根目录
     * @return 自动检测后的配置
     */
    public WalStoreConfig detect(Path baseDir) {
        long maxMemory = Runtime.getRuntime().maxMemory();
        int cores = Runtime.getRuntime().availableProcessors();
        boolean ssd = detectSSD(baseDir);

        // 索引内存预算
        long indexBudget = (long) (maxMemory * INDEX_MEMORY_RATIO);
        int shardCount = clamp(
                (int) Math.max(MIN_SHARDS, indexBudget / BYTES_PER_SHARD_INDEX),
                MAX_SHARDS
        );

        // 分段大小根据磁盘类型决定
        long segmentBytes = ssd ? SSD_SEGMENT_BYTES : HDD_SEGMENT_BYTES;

        // 刷盘策略：SSD 更激进（批量大、间隔短）
        int flushBatch = ssd ? 2048 : 1024;
        long flushIntervalMs = ssd ? 1000L : 5000L;

        log.info("[wal-store] auto-detected: shards={}, segmentMB={}, ssd={}, cores={}",
                shardCount, segmentBytes / (1024 * 1024), ssd, cores);

        return WalStoreConfig.builder()
                .shardCount(shardCount)
                .segmentBytes(segmentBytes)
                .flushBatchSize(flushBatch)
                .flushIntervalMs(flushIntervalMs)
                .cpuCores(cores)
                .isSSD(ssd)
                .baseDir(baseDir != null ? baseDir : Path.of("./wal-data"))
                .namespace("default")
                .build();
    }

    /**
     * 带命名空间的检测。
     */
    public WalStoreConfig detect(Path baseDir, String namespace) {
        WalStoreConfig cfg = detect(baseDir);
        return WalStoreConfig.builder()
                .shardCount(cfg.shardCount())
                .segmentBytes(cfg.segmentBytes())
                .flushBatchSize(cfg.flushBatchSize())
                .flushIntervalMs(cfg.flushIntervalMs())
                .cpuCores(cfg.cpuCores())
                .isSSD(cfg.isSSD())
                .baseDir(cfg.baseDir())
                .namespace(namespace)
                .build();
    }

    // ==================== 内部检测 ====================

    private boolean detectSSD(Path baseDir) {
        if (baseDir == null) return false;
        try {
            // Windows: 检查卷是否为 SSD
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                return detectSSDWindows(baseDir);
            } else if (os.contains("linux")) {
                return detectSSDLinux(baseDir);
            }
        } catch (Exception e) {
            log.debug("[wal-store] SSD detection failed: {}", e.getMessage());
        }
        return false;
    }

    private boolean detectSSDWindows(Path baseDir) {
        try {
            String dir = baseDir.toAbsolutePath().toString();
            String disk = dir.substring(0, 2); // "C:\"
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-Command",
                    "Get-PhysicalDisk -DeviceId (Get-Disk -PartitionAccessible | Where-Object {$_.PartitionStyle -eq 'MBR' -or $_.PartitionStyle -eq 'GPT'} | Select-Object -First 1).DeviceId | Select-Object MediaType | ConvertTo-Json"
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return false; }
            String out = new String(p.getInputStream().readAllBytes()).trim();
            return out.contains("SSD") || out.contains("fixed");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean detectSSDLinux(Path baseDir) {
        try {
            String disk = baseDir.toAbsolutePath().toString().substring(0, 3); // "/dev/sd"
            Process pb = new ProcessBuilder("lsblk", "-d", "-o", "NAME,ROTA", disk)
                    .redirectErrorStream(true).start();
            boolean done = pb.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) { pb.destroyForcibly(); return false; }
            String out = new String(pb.getInputStream().readAllBytes()).trim();
            // ROTA=0 表示 SSD，ROTA=1 表示 HDD
            String[] lines = out.split("\n");
            for (String line : lines) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2 && "0".equals(parts[1])) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static int clamp(int value, int max) {
        return Math.min(Math.max(value, 1), max);
    }
}
