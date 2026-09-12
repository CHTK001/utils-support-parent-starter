package com.chua.common.support.task.backup;

import java.nio.file.Path;
import java.util.List;

/**
* 备份策略 SPI 接口
*
* <p>定义备份的执行逻辑。通过 SPI 机制自动发现实现类。
* 每个实现类通过 {@link #type()} 标识支持的备份类型。
*
* <h3>内置实现</h3>
* <ul>
*   <li>"daily" — 默认按天备份策略</li>
*   <li>"directory" — 目录备份</li>
*   <li>"polled" — 轮询目录备份（deleted → insert 记录）</li>
* </ul>
*
* <h3>使用示例</h3>
* <pre>{@code
*   BackupStrategy strategy = new DefaultDailyBackupStrategy();
*   BackupResult result = strategy.execute(config);
*   if (result.isSuccess()) {
*       System.out.println("备份成功: " + result.getFileCount() + " 个文件");
*   }
* }</pre> + result.getFileCount() + " 个文件");
*   }
* }</pre>
*
* @author CH
* @since 2026/07/16
 */
public interface BackupStrategy {

    /**
    * 获取策略类型标识
    *
    * @return 策略类型
     */
    String type();

    /**
    * 执行备份
    *
    * <p>根据配置执行备份操作，返回备份结果。
    *
    * @param config 备份配置
    * @return 备份结果
     */
    BackupResult execute(BackupConfig config);

    /**
    * 执行增量备份
    *
    * <p>仅备份自上次备份以来变更的文件。
    * 默认实现调用 {@link #execute(BackupConfig)} 全量备份。
    *
    * @param config         备份配置
    * @param lastBackupTime 上次备份时间戳（毫秒）
    * @return 备份结果
     */
    default BackupResult executeIncremental(BackupConfig config, long lastBackupTime) {
        return execute(config);
    }

    /**
    * 清理过期备份
    *
    * <p>根据保留策略删除过期的备份文件。
    *
    * @param config 备份配置
    * @return 清理的文件数
     */
    default int cleanExpired(BackupConfig config) {
        return 0;
    }

    /**
    * 获取可用的备份列表
    *
    * @param config 备份配置
    * @return 备份目录列表
     */
    default List<Path> listBackups(BackupConfig config) {
        return List.of();
    }
}
