package com.chua.common.support.task.backup;

import com.chua.common.support.lang.datasource.flyway.FlywayScripts;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 数据库恢复策略
 *
 * <p>将 {@link DatabaseBackupStrategy} 导出的 SQL 脚本（或其 ZIP 归档）重新执行到
 * 目标数据库。纯 JDBC 实现，不依赖 Spring DataSource。
 *
 * <p>语句拆分复用 {@link FlywayScripts#splitStatements(String)}
 * （跳过 {@code --} 注释、单引号内分号不切分），执行侧跳过空语句。
 *
 * <h3>安全护栏</h3>
 * <ul>
 *   <li>空库检查 — 目标库已存在业务表且未开 {@code skipEmptyCheck} 时拒绝恢复；</li>
 *   <li>{@code dropBeforeCreate} — 对脚本中的 {@code CREATE TABLE} 先执行
 *       {@code DROP TABLE IF EXISTS}（表名自动提取）；</li>
 *   <li>{@code dryRun} — 只校验连接、脚本可读性与语句数量，不执行写操作。</li>
 * </ul>
 *
 * <p><b>事务边界</b>：语句逐条自动提交，失败按 {@code continueOnError} 决定中止或继续；
 * 不做整体回滚（MySQL 中 DDL 与 DML 混合事务不可靠，逻辑备份恢复以逐条落地为准）。
 *
 * @author CH
 * @since 2026/11/24
 */
@Slf4j
public class DatabaseRestoreStrategy {

    /**
     * CREATE TABLE 语句的目标表名提取（{@code CREATE [TEMPORARY] TABLE [IF NOT EXISTS] `t` / "t" / t}）
     */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+(?:TEMPORARY\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[`\"]?([A-Za-z_]\\w*)[`\"]?");

    /**
     * ZIP 归档中允许恢复的脚本扩展名
     */
    private static final String SQL_EXTENSION = ".sql";

    /**
     * ZIP 归档解包临时目录（.sql 直接恢复时为 null）
     */
    private Path tempDir;

    /**
     * 执行数据库恢复。
     *
     * @param config 恢复配置
     * @return 恢复结果（永不返回 null；失败时 success=false）
     */
    public DatabaseRestoreResult restore(DatabaseRestoreConfig config) {
        long start = System.currentTimeMillis();
        try {
            validate(config);
            loadDriverIfPresent(config.getDriverClass());
            tempDir = null;
            List<Path> scripts = resolveScripts(config.getScriptFile());
            try {
                return doRestore(config, scripts, start);
            } finally {
                if (tempDir != null) {
                    DatabaseBackupStrategy.deleteDirectory(tempDir);
                    tempDir = null;
                }
            }
        } catch (Exception e) {
            log.error("[backup] 数据库恢复失败: {}", e.getMessage(), e);
            return DatabaseRestoreResult.failure("数据库恢复失败: " + e.getMessage());
        }
    }

    /**
     * 恢复主体：连接目标库 → 空库检查 → 逐脚本逐语句执行。
     */
    private DatabaseRestoreResult doRestore(DatabaseRestoreConfig config, List<Path> scripts, long start)
            throws Exception {
        int statements = 0;
        int skipped = 0;
        int dropped = 0;
        List<String> errors = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(
                config.getJdbcUrl(), config.getUsername(), config.getPassword())) {
            if (!config.isSkipEmptyCheck()) {
                int existing = countUserTables(conn);
                if (existing > 0) {
                    throw new IllegalStateException("目标库已存在 " + existing
                            + " 张业务表，拒绝恢复（防止误覆盖；确需覆盖请设置 skipEmptyCheck=true）");
                }
            }
            for (Path script : scripts) {
                String content = Files.readString(script, StandardCharsets.UTF_8);
                for (String raw : FlywayScripts.splitStatements(content)) {
                    String sql = raw.trim();
                    if (sql.isEmpty()) {
                        continue;
                    }
                    statements++;
                    if (config.isDryRun()) {
                        continue;
                    }
                    if (config.isDropBeforeCreate()) {
                        String table = extractCreateTableName(sql);
                        if (table != null) {
                            try (Statement statement = conn.createStatement()) {
                                statement.execute("DROP TABLE IF EXISTS " + table);
                            }
                            dropped++;
                        }
                    }
                    try (Statement statement = conn.createStatement()) {
                        statement.execute(sql);
                    } catch (SQLException e) {
                        if (!config.isContinueOnError()) {
                            throw e;
                        }
                        skipped++;
                        errors.add(FlywayScripts.truncate(sql, 120) + " -> " + e.getMessage());
                        log.warn("[backup] 恢复语句执行失败(继续): {} - {}", FlywayScripts.truncate(sql, 80), e.getMessage());
                    }
                }
            }
        }
        long duration = System.currentTimeMillis() - start;
        if (config.isDryRun()) {
            log.info("[backup] 恢复试运行通过: {} 个脚本, {} 条语句", scripts.size(), statements);
        } else {
            log.info("[backup] 数据库恢复完成: {} 条语句, 跳过 {}, DROP {}",
                    statements, skipped, dropped);
        }
        DatabaseRestoreResult result = DatabaseRestoreResult.success(
                scripts.get(0), statements, skipped, dropped, duration);
        result.setErrors(errors);
        return result;
    }

    /**
     * 解析恢复来源：.sql 直接返回；.zip 解包到临时目录按文件名排序返回其中全部 .sql。
     */
    private List<Path> resolveScripts(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")) {
            tempDir = Files.createTempDirectory("db-restore-");
            List<Path> scripts = new ArrayList<>();
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file), StandardCharsets.UTF_8)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String entryName = entry.getName();
                    // 防路径穿越：拒绝绝对路径与 .. 上跳
                    if (entryName.startsWith("/") || entryName.contains("..")) {
                        log.warn("[backup] 跳过 ZIP 中不安全条目: {}", entryName);
                        continue;
                    }
                    if (!entryName.toLowerCase(Locale.ROOT).endsWith(SQL_EXTENSION)) {
                        continue;
                    }
                    Path target = tempDir.resolve(entryName.replace('/', '_')).normalize();
                    if (!target.startsWith(tempDir)) {
                        continue;
                    }
                    Files.copy(zip, target);
                    scripts.add(target);
                }
            }
            if (scripts.isEmpty()) {
                throw new IllegalStateException("ZIP 归档中未找到 .sql 脚本: " + file);
            }
            scripts.sort(Comparator.comparing(path -> path.getFileName().toString()));
            log.info("[backup] ZIP 归档解包完成: {} 个脚本", scripts.size());
            return scripts;
        }
        if (!Files.exists(file)) {
            throw new IllegalStateException("脚本文件不存在: " + file);
        }
        return List.of(file);
    }

    /**
     * 统计目标库业务表数量（过滤系统 schema），用于空库检查。
     */
    private int countUserTables(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        int count = 0;
        try (ResultSet rs = meta.getTables(conn.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                if (schema != null && DatabaseBackupStrategy.SYSTEM_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                count++;
            }
        }
        return count;
    }

    /**
     * 提取 CREATE TABLE 语句的目标表名（非 CREATE TABLE 返回 null）。
     */
    private String extractCreateTableName(String sql) {
        Matcher matcher = CREATE_TABLE.matcher(sql.trim());
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 显式加载驱动类（可选；加载失败不阻断，驱动可能已由 SPI 自动注册）。
     */
    private void loadDriverIfPresent(String driverClass) {
        if (driverClass == null || driverClass.isBlank()) {
            return;
        }
        try {
            Class.forName(driverClass);
        } catch (ClassNotFoundException e) {
            log.warn("[backup] 指定驱动类未找到（尝试由 jdbcUrl 自动发现）: {}", driverClass);
        }
    }

    /**
     * 校验必填配置。
     */
    private void validate(DatabaseRestoreConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("DatabaseRestoreConfig 不能为空");
        }
        if (config.getJdbcUrl() == null || config.getJdbcUrl().isBlank()) {
            throw new IllegalArgumentException("jdbcUrl 不能为空");
        }
        if (config.getScriptFile() == null) {
            throw new IllegalArgumentException("scriptFile 不能为空");
        }
    }
}
