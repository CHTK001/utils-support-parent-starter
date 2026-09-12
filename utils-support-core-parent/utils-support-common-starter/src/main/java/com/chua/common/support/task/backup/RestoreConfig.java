package com.chua.common.support.task.backup;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;

/**
* 恢复配置
*
* <p>控制数据恢复的行为参数，包括恢复源、目标目录、日期范围等。
*
* @author CH
* @since 2026/07/16
 */
@Data
@Builder
public class RestoreConfig {

    /**
    * 备份根目录
    *
    * <p>包含历史备份文件的根路径。
     */
    private Path backupDir;

    /**
    * 恢复目标目录
    *
    * <p>数据将被恢复到此目录。
     */
    private Path targetDir;

    /**
    * 恢复日期（yyyy-MM-dd 格式）
    *
    * <p>指定恢复哪一天的备份。为空则恢复最新的备份。
     */
    private String date;

    /**
    * 是否覆盖已存在的文件
    *
    * <p>默认 true，覆盖目标目录中的同名文件。
     */
    @Builder.Default
    private boolean overwrite = true; // overwrite

    /**
    * 是否恢复后删除备份源文件
    *
    * <p>默认 false，保留备份文件。
     */
    @Builder.Default
    private boolean deleteAfterRestore = false; // 删除之后restore

    /**
    * 恢复文件过滤模式
    *
    * <p>Glob 模式，如 "*.json"。为空则恢复所有文件。
     */
    @Builder.Default
    private String includePattern = ""; // include模式
}
