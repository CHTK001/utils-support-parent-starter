package com.chua.common.support.task.backup;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;

/**
 * 数据库恢复配置
 *
 * <p>将 {@link DatabaseBackupStrategy} 导出的 SQL 脚本（或其 ZIP 归档）
 * 重新执行到目标数据库。命名与文件恢复体系
 * （{@link RestoreConfig}）错开，避免混淆。
 *
 * <h3>安全护栏</h3>
 * <ul>
 *   <li>空库检查 — 目标库已存在业务表且未开 {@link #setSkipEmptyCheck(boolean)}
 *       时拒绝恢复，防止误覆盖；</li>
 *   <li>{@link #setDropBeforeCreate(boolean)} — 对脚本中的 {@code CREATE TABLE}
 *       先执行 {@code DROP TABLE IF EXISTS}（表名自动提取），默认关闭；</li>
 *   <li>{@link #setDryRun(boolean)} — 只校验连接/脚本/语句数，不执行任何写操作。</li>
 * </ul>
 *
 * @author CH
 * @since 2026/11/24
 */
@Data
@Builder
public class DatabaseRestoreConfig {

    // ==================== 目标库连接 ====================

    /**
     * 目标库 JDBC 连接串
     */
    private String jdbcUrl;

    /**
     * 数据库用户名
     */
    private String username;

    /**
     * 数据库密码
     */
    private String password;

    /**
     * 驱动类名（可选；JDBC 4+ 通常可由 jdbcUrl 自动发现驱动）
     */
    private String driverClass;

    // ==================== 恢复来源 ====================

    /**
     * 待恢复的 SQL 脚本文件（也支持 {@link DatabaseBackupStrategy} 产出的 .zip 归档，
     * 内含 .sql 时按文件名排序依次恢复）
     */
    private Path scriptFile;

    // ==================== 安全与行为开关 ====================

    /**
     * 对脚本中的 CREATE TABLE 先执行 DROP TABLE IF EXISTS（表名自动提取）。
     * 默认 false：目标库已有同名表时按数据库报错处理（受 continueOnError 影响）。
     */
    @Builder.Default
    private boolean dropBeforeCreate = false;

    /**
     * 跳过空库检查。默认 false：目标库已存在业务表时拒绝恢复，防止误覆盖。
     */
    @Builder.Default
    private boolean skipEmptyCheck = false;

    /**
     * 单条语句执行失败时是否继续执行后续语句。
     * 默认 false：首次失败即中止并返回失败结果。
     */
    @Builder.Default
    private boolean continueOnError = false;

    /**
     * 试运行：只校验连接、脚本可读性与语句数量，不执行任何写操作。默认 false。
     */
    @Builder.Default
    private boolean dryRun = false;
}
