package com.chua.common.support.task.backup;

import com.chua.common.support.task.timer.Timer;
import com.chua.common.support.task.timer.TimerTask;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于时间轮的数据库定时备份任务
 *
 * <p>使用 common-starter 的哈希时间轮（{@link Timer#newTimer()}）按固定周期
 * 执行 {@link DatabaseBackupStrategy} 数据库备份，支持配置首次延迟与执行周期。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 *   DatabaseBackupConfig config = DatabaseBackupConfig.builder()
 *           .jdbcUrl("jdbc:mysql://127.0.0.1:3306/monitordb")
 *           .username("root").password("***")
 *           .backupDir(Path.of("D:/backup"))
 *           .build();
 *   // 每 6 小时备份一次，首次延迟 1 分钟
 *   ScheduledDatabaseBackup job = new ScheduledDatabaseBackup(config, 1, 360, TimeUnit.MINUTES);
 *   ...
 *   job.stop();
 * }</pre>
 *
 * @author CH
 * @since 2026/11/24
 */
@Slf4j
public class ScheduledDatabaseBackup implements AutoCloseable {

    /**
     * 数据库备份配置
     */
    private final DatabaseBackupConfig config;
    /**
     * 时间轮
     */
    private final Timer timer;
    /**
     * 定时任务句柄
     */
    private final TimerTask timerTask;

    /**
     * 是否正在执行备份
     */
    private volatile boolean running;
    /**
     * 最近一次备份开始时间
     */
    private volatile LocalDateTime lastRunTime;
    /**
     * 最近一次备份结果
     */
    private volatile BackupResult lastResult;
    /**
     * 最近一次备份耗时（毫秒）
     */
    private volatile long lastDurationMs;
    /**
     * 累计执行次数
     */
    private final AtomicLong totalRuns = new AtomicLong();
    /**
     * 累计失败次数
     */
    private final AtomicLong totalFailures = new AtomicLong();

    /**
     * 创建并启动定时备份任务
     *
     * @param config       数据库备份配置
     * @param initialDelay 首次执行延迟
     * @param period       执行周期
     * @param unit         时间单位
     */
    public ScheduledDatabaseBackup(DatabaseBackupConfig config, long initialDelay, long period, TimeUnit unit) {
        this.config = config;
        this.timer = Timer.newTimer();
        this.timerTask = timer.scheduleAtFixedRate(this::executeBackup, initialDelay, period, unit);
        log.info("[backup] 数据库定时备份已启动: 首次延迟 {} {}, 周期 {} {}", initialDelay, unit, period, unit);
    }

    /**
     * 便捷启动：按固定周期执行（首次延迟等于一个周期）
     *
     * @param config 数据库备份配置
     * @param period 执行周期
     * @param unit   时间单位
     * @return 定时备份任务
     */
    public static ScheduledDatabaseBackup start(DatabaseBackupConfig config, long period, TimeUnit unit) {
        return new ScheduledDatabaseBackup(config, period, period, unit);
    }

    /**
     * 手动执行一次备份（不依赖时间轮触发）
     *
     * @return 备份结果
     */
    public BackupResult backupOnce() {
        return executeBackup();
    }

    /**
     * 停止定时任务并关闭时间轮
     */
    public void stop() {
        if (timerTask != null) {
            timer.cancel(timerTask);
        }
        timer.shutdown();
        log.info("[backup] 数据库定时备份已停止");
    }

    /**
     * 查询任务运行状态（任务名由 {@link BackupTaskRegistry} 回填）。
     *
     * @return 任务运行状态
     */
    public BackupTaskStatus status() {
        BackupResult last = lastResult;
        return BackupTaskStatus.builder()
                .running(running)
                .lastRunTime(lastRunTime)
                .lastResult(last)
                .lastError(last == null || last.isSuccess() ? null : last.getErrorMessage())
                .lastDurationMs(lastDurationMs)
                .totalRuns(totalRuns.get())
                .totalFailures(totalFailures.get())
                .build();
    }

    /**
     * AutoCloseable 语义：关闭即停止
     */
    @Override
    public void close() {
        stop();
    }

    /**
     * 执行一次备份并记录结果日志与运行状态
     */
    private BackupResult executeBackup() {
        LocalDateTime runTime = LocalDateTime.now();
        running = true;
        long start = System.currentTimeMillis();
        try {
            BackupResult result = new DatabaseBackupStrategy().execute(config);
            lastDurationMs = System.currentTimeMillis() - start;
            lastResult = result;
            if (result.isSuccess()) {
                log.info("[backup] 定时数据库备份完成: {} 个文件, {} 字节, 耗时 {}ms",
                        result.getFileCount(), result.getTotalSize(), result.getDurationMillis());
            } else {
                log.warn("[backup] 定时数据库备份失败: {}", result.getErrorMessage());
            }
            return result;
        } finally {
            lastRunTime = runTime;
            totalRuns.incrementAndGet();
            BackupResult finished = lastResult;
            if (finished == null || !finished.isSuccess()) {
                totalFailures.incrementAndGet();
            }
            running = false;
        }
    }
}
