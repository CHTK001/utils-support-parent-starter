package com.chua.common.support.task.backup;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * 备份恢复接口
 *
 * <p>定义数据恢复的操作，支持按日期恢复、恢复最新备份、解压历史 ZIP 等。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 *   BackupRestore restore = new DefaultBackupRestore();
 *
 *   // 恢复指定日期的备份
 *   RestoreResult result = restore.restore(config);
 *
 *   // 恢复最新备份
 *   RestoreResult result = restore.restoreLatest(config);
 *
 *   // 恢复指定日期
 *   RestoreResult result = restore.restoreByDate(config, LocalDate.of(2026, 7, 15));
 * }</pre>Date(config, LocalDate.of(2026, 7, 15));
 * }</pre>
 *
 * @author CH
 * @since 2026/07/16
 */
public interface BackupRestore {

    /**
     * 恢复备份
     *
     * <p>根据配置恢复数据。若指定了日期则恢复该日期的备份，否则恢复最新。
     *
     * @param config 恢复配置
     * @return 恢复结果
     */
    RestoreResult restore(RestoreConfig config);

    /**
     * 恢复最新备份
     *
     * @param config 恢复配置
     * @return 恢复结果
     */
    RestoreResult restoreLatest(RestoreConfig config);

    /**
     * 按日期恢复
     *
     * @param config 恢复配置
     * @param date   恢复日期
     * @return 恢复结果
     */
    RestoreResult restoreByDate(RestoreConfig config, LocalDate date);

    /**
     * 获取可恢复的备份日期列表
     *
     * @param backupDir 备份根目录
     * @return 日期列表（降序）
     */
    List<LocalDate> listAvailableDates(Path backupDir);
}
