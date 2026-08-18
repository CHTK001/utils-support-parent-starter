package com.chua.common.support.task.backup;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;

/**
 * 备份配置
 *
 * <p>控制备份的核心行为参数，包括目录、保留策略、压缩等。
 *
 * @author CH
 * @since 2026/07/16
 */
@Data
@Builder
public class BackupConfig {

    /**
     * 备份根目录
     *
     * <p>所有备份文件的根路径。目录结构：
     * <pre>
     *   {backupDir}/
     *   ├── {yyyy-MM-dd}/          ← 当天备份（原始文件）
     *   │   ├── file1.txt
     *   │   └── file2.json
     *   ├── archive/               ← 历史备份（按天压缩）
     *   │   ├── 2026-07-15.zip
     *   │   └── 2026-07-14.zip
     *   └── ...
     * </pre>
     */
    private Path backupDir;

    /**
     * 要备份的源目录
     */
    private Path sourceDir;

    /**
     * 保留天数
     *
     * <p>超过此天数的历史备份将被清理。默认 30 天。
     */
    @Builder.Default
    /** Retentiondays */
    private int retentionDays = 30;

    /**
     * 是否压缩历史备份
     *
     * <p>超过当天的备份自动压缩为 ZIP。默认开启。
     */
    @Builder.Default
    /** Compressarchives */
    private boolean compressArchives = true;

    /**
     * 备份文件过滤模式
     *
     * <p>Glob 模式，如 "*.json"、"*.txt"。为空则备份所有文件。
     */
    @Builder.Default
    /** Include模式 */
    private String includePattern = "";

    /**
     * 排除模式
     *
     * <p>Glob 模式，如 "*.tmp"、".git"。为空则不排除。
     */
    @Builder.Default
    /** Exclude模式 */
    private String excludePattern = "";
}
