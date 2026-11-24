package com.chua.common.support.task.backup;

import com.chua.common.support.task.scheduler.CronTrigger;
import com.chua.common.support.task.timer.Timer;
import com.chua.common.support.task.timer.TimerTask;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于时间轮的数据库定时备份任务
 *
 * <p>使用 common-starter 的哈希时间轮（{@link Timer#newTimer()}）执行
 * {@link DatabaseBackupStrategy} 数据库备份，支持两种调度方式：
 * <ul>
 *   <li>固定周期 — {@link #ScheduledDatabaseBackup(DatabaseBackupConfig, long, long, TimeUnit)}
 *       按首次延迟 + 固定周期循环执行；</li>
 *   <li>cron 表达式 — {@link #ScheduledDatabaseBackup(DatabaseBackupConfig, String)} 按标准
 *       6 字段 cron（秒 分 时 日 月 周）触发，复用 {@link CronTrigger} 解析；每次执行完成后
 *       基于 {@link CronTrigger#nextExecutionTime(LocalDateTime)} 计算并预排下一次，
 *       支持 {@code 0 0 2 * * ?}（每天凌晨 2 点）、{@code 0 0/30 * * * MON-FRI}（工作日每半小时）等
 *       日历语义。</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 *   DatabaseBackupConfig config = DatabaseBackupConfig.builder()
 *           .jdbcUrl("jdbc:mysql://127.0.0.1:3306/monitordb")
 *           .username("root").password("***")
 *           .backupDir(Path.of("D:/backup"))
 *           .build();
 *   // 固定周期：每 6 小时备份一次，首次延迟 1 分钟
 *   ScheduledDatabaseBackup job = new ScheduledDatabaseBackup(config, 1, 360, TimeUnit.MINUTES);
 *   // cron：每天凌晨 2 点备份
 *   ScheduledDatabaseBackup cronJob = ScheduledDatabaseBackup.startWithCron(config, "0 0 2 * * ?");
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
     * cron 触发器（固定周期模式下为 null）
     */
    private final CronTrigger cronTrigger;
    /**
     * 当前在册任务句柄（固定周期模式下即周期任务；cron 模式下为最近一次单次任务）
     */
    private volatile TimerTask currentTask;
    /**
     * 是否已停止（停止后 cron 不再续排）
     */
    private volatile boolean stopped;

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
     * 创建并启动固定周期定时备份任务
     *
     * @param config       数据库备份配置
     * @param initialDelay 首次执行延迟
     * @param period       执行周期
     * @param unit         时间单位
     */
    public ScheduledDatabaseBackup(DatabaseBackupConfig config, long initialDelay, long period, TimeUnit unit) {
        this.config = config;
        this.timer = Timer.newTimer();
        this.cronTrigger = null;
        this.currentTask = timer.scheduleAtFixedRate(this::executeBackup, initialDelay, period, unit);
        log.info("[backup] 数据库定时备份已启动: 首次延迟 {} {}, 周期 {} {}", initialDelay, unit, period, unit);
    }

    /**
     * 创建并启动 cron 定时备份任务
     *
     * @param config 数据库备份配置
     * @param cron   标准 6 字段 cron 表达式（秒 分 时 日 月 周，支持 * , - / ? 与英文周缩写）
     */
    public ScheduledDatabaseBackup(DatabaseBackupConfig config, String cron) {
        this.config = config;
        // 先解析 cron 表达式：非法表达式直接抛出，避免泄漏时间轮线程
        this.cronTrigger = new CronTrigger(cron);
        this.timer = Timer.newTimer();
        scheduleNextCronRun();
        log.info("[backup] 数据库定时备份已启动(cron): {}, 下次执行 {}", cron, nextFireTime());
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
     * 便捷启动：按 cron 表达式执行
     *
     * @param config 数据库备份配置
     * @param cron   标准 6 字段 cron 表达式
     * @return 定时备份任务
     */
    public static ScheduledDatabaseBackup startWithCron(DatabaseBackupConfig config, String cron) {
        return new ScheduledDatabaseBackup(config, cron);
    }

    /**
     * 预排下一次 cron 触发：按当前时间计算下一次执行点，到期执行备份后再续排。
     * 备份抛出的任何异常都不会中断续排（{@link DatabaseBackupStrategy#execute} 内部已兜底）。
     */
    private void scheduleNextCronRun() {
        if (stopped) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = cronTrigger.nextExecutionTime(now);
        long delayMs = Math.max(0, Duration.between(now, next).toMillis());
        currentTask = timer.schedule(() -> {
            if (stopped) {
                return;
            }
            try {
                executeBackup();
            } finally {
                scheduleNextCronRun();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 查询下一次触发时间（仅 cron 模式有值；固定周期模式返回 null）。
     *
     * @return 下一次触发时间；固定周期模式返回 null
     */
    public LocalDateTime nextFireTime() {
        return cronTrigger == null ? null : cronTrigger.nextExecutionTime(LocalDateTime.now());
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
        stopped = true;
        TimerTask task = currentTask;
        if (task != null) {
            timer.cancel(task);
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
