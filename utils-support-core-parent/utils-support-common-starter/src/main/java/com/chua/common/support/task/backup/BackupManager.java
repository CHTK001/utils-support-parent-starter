package com.chua.common.support.task.backup;

import com.chua.common.support.spi.ServiceProvider;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * 备份管理器
 *
 * <p>提供统一的备份操作入口，支持通过 SPI 自动发现备份策略实现。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 *   // 使用默认策略
 *   BackupManager manager = new BackupManager();
 *   BackupResult result = manager.backup(config);
 *
 *   // 使用指定策略
 *   BackupResult result = manager.backup(config, "daily");
 *
 *   // 使用目录备份
 *   BackupResult result = manager.backupDirectory(config);
 *
 *   // 使用轮询目录备份
 *   BackupResult result = manager.backupPolledDirectory(config);
 * }</pre>*   // 使用轮询目录备份
 *   BackupResult result = manager.backupPolledDirectory(config);
 * }</pre>
 *
 * @author CH
 * @since 2026/07/16
 */
public class BackupManager {

    /**
     * 使用默认策略（daily）执行备份
     *
     * @param config 备份配置
     * @return 备份结果
     */
    public BackupResult backup(BackupConfig config) {
        return backup(config, "daily");
    }

    /**
     * 使用指定策略执行备份
     *
     * <p>通过 SPI 查找策略实现，未找到则使用默认 daily 策略。
     *
     * @param config       备份配置
     * @param strategyType 策略类型标识
     * @return 备份结果
     */
    public BackupResult backup(BackupConfig config, String strategyType) {
        BackupStrategy strategy = findStrategy(strategyType);
        return strategy.execute(config);
    }

    /**
     * 执行增量备份
     *
     * @param config         备份配置
     * @param lastBackupTime 上次备份时间戳
     * @return 备份结果
     */
    public BackupResult backupIncremental(BackupConfig config, long lastBackupTime) {
        BackupStrategy strategy = findStrategy("directory");
        return strategy.executeIncremental(config, lastBackupTime);
    }

    /**
     * 执行目录备份
     *
     * @param config 备份配置
     * @return 备份结果
     */
    public BackupResult backupDirectory(BackupConfig config) {
        return backup(config, "directory");
    }

    /**
     * 执行轮询目录备份
     *
     * @param config 备份配置
     * @return 备份结果
     */
    public BackupResult backupPolledDirectory(BackupConfig config) {
        return backup(config, "polled");
    }

    /**
     * 执行轮询目录备份（自定义转换器）
     *
     * @param config      备份配置
     * @param transformer 记录转换器
     * @return 备份结果
     */
    public BackupResult backupPolledDirectory(BackupConfig config, PolledDirectoryBackup.RecordTransformer transformer) {
        PolledDirectoryBackup strategy = new PolledDirectoryBackup(transformer);
        return strategy.execute(config);
    }

    /**
     * 清理过期备份
     *
     * @param config 备份配置
     * @return 清理的文件数
     */
    public int cleanExpired(BackupConfig config) {
        return findStrategy("daily").cleanExpired(config);
    }

    /**
     * 获取备份列表
     *
     * @param config 备份配置
     * @return 备份文件列表
     */
    public List<Path> listBackups(BackupConfig config) {
        return findStrategy("daily").listBackups(config);
    }

    // ==================== 恢复操作 ====================

    /** Restore */
    private final BackupRestore restore = new DefaultBackupRestore();

    /**
    * 恢复备份（按配置中的日期）
    *
    * @param config 恢复配置
    * @return 恢复结果
    */
    public RestoreResult restore(RestoreConfig config) {
        return restore.restore(config);
    }

    /**
     * 恢复最新备份
     *
     * @param config 恢复配置
     * @return 恢复结果
     */
    public RestoreResult restoreLatest(RestoreConfig config) {
        return restore.restoreLatest(config);
    }

    /**
     * 按日期恢复
     *
     * @param config 恢复配置
     * @param date   恢复日期
     * @return 恢复结果
     */
    public RestoreResult restoreByDate(RestoreConfig config, LocalDate date) {
        return restore.restoreByDate(config, date);
    }

    /**
     * 获取可恢复的备份日期列表
     *
     * @param backupDir 备份根目录
     * @return 日期列表（降序）
     */
    public List<LocalDate> listAvailableDates(Path backupDir) {
        return restore.listAvailableDates(backupDir);
    }

    /**
     * 通过 SPI 查找策略实现
     * @param type 类型
     * @return findStrategy的结果
     */
    private BackupStrategy findStrategy(String type) {
        try {
            BackupStrategy strategy = ServiceProvider.of(BackupStrategy.class)
                    .getExtension(type);
            if (strategy != null) {
                return strategy;
            }
        } catch (Exception ignored) {
        }
        // 回退到内置实现
        return switch (type) {
            case "directory" -> new DirectoryBackup();
            case "polled" -> new PolledDirectoryBackup();
            default -> new DefaultDailyBackupStrategy();
        };
    }
}
