package com.chua.common.support.task.backup;

import com.chua.common.support.lang.datasource.flyway.FlywayScripts;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import com.chua.common.support.utils.CollectionUtils;

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
 *   <li>{@code dryRun} — 只校验连接、脚本可读性与语句数量，不执行写操作；</li>
 *   <li>{@code verifyChecksum} — 恢复前按 {@link BackupManifest} 逐脚本校验
 *       SHA-256 校验和，防止损坏/篡改的备份静默落库。</li>
 * </ul>
 *
 * <p><b>进度监听</b>：{@link RestoreListener} 可在开始、进度（每 500 条语句及
 * 每脚本完成）、成功、失败时点收到 {@link RestoreEvent} 通知，用于进度展示与审计。
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
     * CREATE TABLE 语句的目标表名提取（{@code CREATE [TEMPORARY] TABLE [IF NOT EXISTS] `t` / "t" / t}）。
     * 包级开放供 {@link RestorePreflight} 试运行复用。
     */
    static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+(?:TEMPORARY\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[`\"]?([A-Za-z_]\\w*)[`\"]?");

    /**
     * ZIP 归档中允许恢复的脚本扩展名
     */
    private static final String SQL_EXTENSION = ".sql";

    /**
     * 恢复进度事件推送间隔（每处理 N 条语句推送一次，避免海量语句刷屏）
     */
    private static final int PROGRESS_INTERVAL = 500;

    /**
     * ZIP 归档解包临时目录（.sql 直接恢复时为 null）
     */
    private Path tempDir;

    /**
     * ZIP 归档内解出的 manifest.json（.sql 直接恢复时为 null，改查脚本同目录）
     */
    private Path resolvedManifest;

    /**
     * 执行数据库恢复。
     *
     * <p>流程：解析脚本（ZIP 归档先解包）→ 可选 SHA-256 校验和验证
     * （{@code verifyChecksum = true} 时强制）→ 发布 START 事件 →
     * 连接目标库 → 空库检查 → 逐脚本逐语句执行（发布 PROGRESS 事件）→
     * 发布 SUCCESS/FAILURE 事件。
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
            resolvedManifest = null;
            List<Path> scripts = resolveScripts(config.getScriptFile());
            try {
                BackupManifest manifest = resolveManifest(config, scripts);
                fireStart(config, scripts, manifest);
                return doRestore(config, scripts, manifest, start);
            } finally {
                if (tempDir != null) {
                    DatabaseBackupStrategy.deleteDirectory(tempDir);
                    tempDir = null;
                }
            }
        } catch (Exception e) {
            log.error("[backup] 数据库恢复失败: {}", e.getMessage(), e);
            DatabaseRestoreResult failure = DatabaseRestoreResult.failure("数据库恢复失败: " + e.getMessage());
            fireFailure(config, failure, e.getMessage());
            return failure;
        }
    }

    /**
     * 恢复主体：连接目标库 → 空库检查 → 逐脚本逐语句执行。
     * 每处理 {@link #PROGRESS_INTERVAL} 条语句及每个脚本完成时发布 PROGRESS 事件。
     */
    private DatabaseRestoreResult doRestore(DatabaseRestoreConfig config, List<Path> scripts,
                                            BackupManifest manifest, long start) throws Exception {
        int statements = 0;
        int skipped = 0;
        int dropped = 0;
        int filesDone = 0;
        long executed = 0;
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
                        if (statements % PROGRESS_INTERVAL == 0) {
                            fireProgress(config, script, filesDone, scripts.size(),
                                    statements, executed, skipped, "试运行解析中");
                        }
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
                        executed++;
                    } catch (SQLException e) {
                        if (!config.isContinueOnError()) {
                            throw e;
                        }
                        skipped++;
                        errors.add(FlywayScripts.truncate(sql, 120) + " -> " + e.getMessage());
                        log.warn("[backup] 恢复语句执行失败(继续): {} - {}", FlywayScripts.truncate(sql, 80), e.getMessage());
                    }
                    if (statements % PROGRESS_INTERVAL == 0) {
                        fireProgress(config, script, filesDone, scripts.size(),
                                statements, executed, skipped, "恢复中");
                    }
                }
                filesDone++;
                fireProgress(config, script, filesDone, scripts.size(),
                        statements, executed, skipped, "脚本恢复完成");
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
        fireSuccess(config, result);
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
                    // 条目 basename（zip 条目可带目录前缀，如 sub/xxx.sql）
                    String baseName = entryName.substring(entryName.lastIndexOf('/') + 1);
                    // 备份清单随归档解出，供 verifyChecksum 校验
                    if (baseName.equalsIgnoreCase(BackupManifest.MANIFEST_FILE_NAME)) {
                        Path manifestTarget = tempDir.resolve(BackupManifest.MANIFEST_FILE_NAME);
                        Files.copy(zip, manifestTarget, StandardCopyOption.REPLACE_EXISTING);
                        resolvedManifest = manifestTarget;
                        continue;
                    }
                    if (!baseName.toLowerCase(Locale.ROOT).endsWith(SQL_EXTENSION)) {
                        continue;
                    }
                    // 以纯文件名落盘，保证与备份清单 FileEntry.fileName 一致
                    Path target = tempDir.resolve(baseName).normalize();
                    if (!target.startsWith(tempDir)) {
                        continue;
                    }
                    if (Files.exists(target)) {
                        // 极少数 zip 内不同目录同名脚本：退化为路径衍生名防覆盖、防丢脚本
                        target = tempDir.resolve(entryName.replace('/', '_')).normalize();
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
     * 解析备份清单并在 {@code verifyChecksum = true} 时逐脚本校验 SHA-256。
     *
     * <p>清单来源：ZIP 归档内解出的 {@code manifest.json}，或 .sql 同目录下的
     * {@code manifest.json}。开启校验但清单缺失、条目缺失、校验和不匹配均抛异常拒绝恢复。
     *
     * @param config  恢复配置
     * @param scripts 待恢复的脚本清单
     * @return 备份清单；来源不含清单时返回 null
     */
    private BackupManifest resolveManifest(DatabaseRestoreConfig config, List<Path> scripts) throws IOException {
        Path manifestFile = resolvedManifest;
        if (manifestFile == null && config.getScriptFile().getParent() != null) {
            Path sibling = config.getScriptFile().getParent().resolve(BackupManifest.MANIFEST_FILE_NAME);
            if (Files.exists(sibling)) {
                manifestFile = sibling;
            }
        }
        if (manifestFile == null || !Files.exists(manifestFile)) {
            if (config.isVerifyChecksum()) {
                throw new IllegalStateException("verifyChecksum=true 但未找到 "
                        + BackupManifest.MANIFEST_FILE_NAME + "（.sql 同目录或 ZIP 归档内）: " + config.getScriptFile());
            }
            return null;
        }
        BackupManifest manifest = BackupManifest.fromJson(Files.readString(manifestFile, StandardCharsets.UTF_8));
        if (config.isVerifyChecksum()) {
            for (Path script : scripts) {
                String name = script.getFileName().toString();
                BackupManifest.FileEntry entry = manifest.entryFor(name);
                if (entry == null) {
                    throw new IllegalStateException("备份清单缺少文件条目: " + name);
                }
                String actual = BackupManifest.sha256Hex(script);
                if (!actual.equalsIgnoreCase(entry.sha256())) {
                    throw new IllegalStateException("SHA-256 校验和不匹配: " + name
                            + ", 期望 " + entry.sha256() + ", 实际 " + actual + "（备份文件可能已损坏或被篡改）");
                }
                log.info("[backup] 校验和通过: {} ({})", name, actual);
            }
        }
        return manifest;
    }

    // ==================== 恢复事件发布 ====================

    /**
     * 发布恢复开始事件。
     */
    private void fireStart(DatabaseRestoreConfig config, List<Path> scripts, BackupManifest manifest) {
        fire(config, RestoreEvent.builder()
                .time(LocalDateTime.now())
                .phase(RestoreEvent.PHASE_START)
                .filesTotal(scripts.size())
                .filesDone(0)
                .dryRun(config.isDryRun())
                .checksumVerified(config.isVerifyChecksum())
                .message("恢复开始: " + scripts.size() + " 个脚本"
                        + (manifest != null ? ", 备份清单就绪" : ", 无备份清单"))
                .build());
    }

    /**
     * 发布恢复进度事件。
     */
    private void fireProgress(DatabaseRestoreConfig config, Path currentFile, int filesDone, int filesTotal,
                              long statements, long executed, long skipped, String message) {
        fire(config, RestoreEvent.builder()
                .time(LocalDateTime.now())
                .phase(RestoreEvent.PHASE_PROGRESS)
                .currentFile(currentFile)
                .filesDone(filesDone)
                .filesTotal(filesTotal)
                .statementsProcessed(statements)
                .statementsExecuted(executed)
                .statementsSkipped(skipped)
                .dryRun(config.isDryRun())
                .checksumVerified(config.isVerifyChecksum())
                .message(message)
                .build());
    }

    /**
     * 发布恢复成功事件（携带结果计数）。
     */
    private void fireSuccess(DatabaseRestoreConfig config, DatabaseRestoreResult result) {
        fire(config, RestoreEvent.builder()
                .time(LocalDateTime.now())
                .phase(RestoreEvent.PHASE_SUCCESS)
                .currentFile(result.getScriptFile())
                .filesTotal(1)
                .filesDone(1)
                .statementsProcessed(result.getStatements())
                .statementsExecuted(Math.max(0, result.getStatements() - result.getSkippedStatements()))
                .statementsSkipped(result.getSkippedStatements())
                .dryRun(config.isDryRun())
                .checksumVerified(config.isVerifyChecksum())
                .result(result)
                .message("恢复成功")
                .build());
    }

    /**
     * 发布恢复失败事件。
     */
    private void fireFailure(DatabaseRestoreConfig config, DatabaseRestoreResult result, String errorMessage) {
        fire(config, RestoreEvent.builder()
                .time(LocalDateTime.now())
                .phase(RestoreEvent.PHASE_FAILURE)
                .dryRun(config != null && config.isDryRun())
                .checksumVerified(config != null && config.isVerifyChecksum())
                .result(result)
                .errorMessage(errorMessage)
                .message("恢复失败")
                .build());
    }

    /**
     * 按事件阶段分发到对应监听器回调；单个监听器异常只告警，不中断恢复与其余监听器。
     */
    private void fire(DatabaseRestoreConfig config, RestoreEvent event) {
        List<RestoreListener> listeners = config == null ? null : config.getListeners();
        if (CollectionUtils.isEmpty(listeners)) {
            return;
        }
        for (RestoreListener listener : listeners) {
            try {
                switch (event.getPhase()) {
                    case RestoreEvent.PHASE_START -> listener.onStart(event);
                    case RestoreEvent.PHASE_PROGRESS -> listener.onProgress(event);
                    case RestoreEvent.PHASE_SUCCESS -> listener.onSuccess(event);
                    case RestoreEvent.PHASE_FAILURE -> listener.onFailure(event);
                    default -> log.warn("[backup] 未知恢复事件阶段: {}", event.getPhase());
                }
            } catch (Exception e) {
                log.warn("[backup] 恢复监听器 {} 处理 {} 事件失败: {}",
                        listener.getClass().getSimpleName(), event.getPhase(), e.getMessage());
            }
        }
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
