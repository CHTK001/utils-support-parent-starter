package com.chua.common.support.task.backup;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 数据库定时备份任务注册表
 *
 * <p>集中管理多个命名定时备份任务：注册（同名先停旧）、手动触发、
 * 停止单个/全部、查询运行状态。供宿主的管理接口或运维页面使用。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 *   BackupTaskRegistry registry = new BackupTaskRegistry();
 *   registry.register("monitordb", config, 1, 360, TimeUnit.MINUTES);
 *   registry.trigger("monitordb");      // 手动执行一次
 *   BackupTaskStatus status = registry.status("monitordb");
 *   registry.stop("monitordb");
 * }</pre>
 *
 * @author CH
 * @since 2026/11/24
 */
@Slf4j
public class BackupTaskRegistry {

    /**
     * 任务表：任务名 → 定时任务
     */
    private final Map<String, ScheduledDatabaseBackup> tasks = new ConcurrentHashMap<>();

    /**
     * 注册定时备份任务（首次延迟等于一个周期）。
     *
     * <p>同名任务已存在时先停止旧任务再注册新任务。
     *
     * @param name   任务名（唯一）
     * @param config 数据库备份配置
     * @param period 执行周期
     * @param unit   时间单位
     * @return 已启动的定时任务
     */
    public synchronized ScheduledDatabaseBackup register(String name, DatabaseBackupConfig config,
                                                         long period, TimeUnit unit) {
        return register(name, config, period, period, unit);
    }

    /**
     * 注册定时备份任务。
     *
     * <p>同名任务已存在时先停止旧任务再注册新任务。
     *
     * @param name         任务名（唯一）
     * @param config       数据库备份配置
     * @param initialDelay 首次执行延迟
     * @param period       执行周期
     * @param unit         时间单位
     * @return 已启动的定时任务
     */
    public synchronized ScheduledDatabaseBackup register(String name, DatabaseBackupConfig config,
                                                         long initialDelay, long period, TimeUnit unit) {
        ScheduledDatabaseBackup existing = tasks.get(name);
        if (existing != null) {
            existing.stop();
            log.info("[backup] 停止同名定时任务后重新注册: {}", name);
        }
        ScheduledDatabaseBackup task = new ScheduledDatabaseBackup(config, initialDelay, period, unit);
        tasks.put(name, task);
        log.info("[backup] 注册定时备份任务: {} (周期 {} {})", name, period, unit);
        return task;
    }

    /**
     * 手动触发一次指定任务（不等待下一次调度）。
     *
     * @param name 任务名
     * @return 备份结果
     * @throws IllegalArgumentException 任务不存在
     */
    public BackupResult trigger(String name) {
        ScheduledDatabaseBackup task = tasks.get(name);
        if (task == null) {
            throw new IllegalArgumentException("备份任务不存在: " + name);
        }
        return task.backupOnce();
    }

    /**
     * 停止并移除指定任务。
     *
     * @param name 任务名
     * @return 是否成功停止（任务不存在返回 false）
     */
    public synchronized boolean stop(String name) {
        ScheduledDatabaseBackup task = tasks.remove(name);
        if (task == null) {
            return false;
        }
        task.stop();
        return true;
    }

    /**
     * 停止并移除全部任务。
     */
    public synchronized void stopAll() {
        tasks.values().forEach(ScheduledDatabaseBackup::stop);
        tasks.clear();
        log.info("[backup] 已停止全部定时备份任务");
    }

    /**
     * 查询指定任务运行状态。
     *
     * @param name 任务名
     * @return 任务状态；任务不存在返回 null
     */
    public BackupTaskStatus status(String name) {
        ScheduledDatabaseBackup task = tasks.get(name);
        BackupTaskStatus status = task == null ? null : task.status();
        if (status != null) {
            status.setName(name);
        }
        return status;
    }

    /**
     * 查询全部任务运行状态。
     *
     * @return 任务状态列表（按任务名升序）
     */
    public List<BackupTaskStatus> statusAll() {
        return tasks.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    BackupTaskStatus status = entry.getValue().status();
                    status.setName(entry.getKey());
                    return status;
                })
                .collect(Collectors.toList());
    }
}
