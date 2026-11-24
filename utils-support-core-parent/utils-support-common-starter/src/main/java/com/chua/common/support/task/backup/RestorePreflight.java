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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 数据库恢复预检
 *
 * <p>恢复前的独立显式 API，分两档：
 * <ul>
 *   <li>{@link #check(DatabaseRestoreConfig)} — 只读预检：脚本可读、备份清单
 *       （含可选 SHA-256 校验和）、目标库连通性、空库检查、语句解析计数，
 *       不对目标库执行任何写操作；</li>
 *   <li>{@link #dryRun(DatabaseRestoreConfig)} — 试运行：在单连接事务内
 *       （REPEATABLE_READ，不支持时回退 SERIALIZABLE）执行全部脚本后回滚，
 *       验证语句真实可执行；目标库数据随事务回滚，本次新建的表在回滚后
 *       显式删除兜底（H2/MySQL 的 DDL 隐式提交、无法随事务回滚），
 *       恢复目标库原状；请勿对生产库开 dryRun。</li>
 * </ul>
 *
 * <p>纯 JDBC 实现，不依赖 Spring DataSource，也不复用
 * {@link DatabaseRestoreStrategy} 的执行入口，避免与真实恢复流程互相干扰。
 *
 * @author CH
 * @since 2026/11/24
 */
@Slf4j
public final class RestorePreflight {

    /**
     * 预检模式：只读检查
     */
    public static final String MODE_CHECK = "CHECK";
    /**
     * 预检模式：事务内试运行
     */
    public static final String MODE_DRY_RUN = "DRY_RUN";

    private RestorePreflight() {
    }

    /**
     * 只读预检：连接、脚本、清单、空库、语句计数；不执行任何写操作。
     *
     * @param config 恢复配置
     * @return 预检结果（永不返回 null）
     */
    public static PreflightResult check(DatabaseRestoreConfig config) {
        long start = System.currentTimeMillis();
        List<CheckItem> checks = new ArrayList<>();
        List<Path> tempDirs = new ArrayList<>();
        try {
            List<Path> scripts = resolveScripts(config, checks, tempDirs);
            if (scripts == null) {
                return finish(MODE_CHECK, checks, 0, start, null);
            }
            int statements = countStatements(scripts, checks);
            BackupManifest manifest = resolveManifest(config, scripts, checks);
            try (Connection conn = connect(config, checks)) {
                if (conn != null) {
                    checkEmpty(conn, config, checks);
                }
            }
            return finish(MODE_CHECK, checks, statements, start, null);
        } catch (Exception e) {
            log.warn("[backup] 恢复预检失败: {}", e.getMessage());
            checks.add(new CheckItem("预检执行", false, e.getMessage()));
            return finish(MODE_CHECK, checks, 0, start, e.getMessage());
        } finally {
            tempDirs.forEach(DatabaseBackupStrategy::deleteDirectory);
        }
    }

    /**
     * 试运行：在单连接事务内执行全部脚本后回滚，验证语句真实可执行。
     *
     * <p>注意：MySQL、H2 等数据库的 DDL 会隐式提交、无法随事务回滚；
     * 试运行在回滚后会删除本次新建（执行前不存在）的表以恢复目标库原状；
     * 索引、外键等其它 DDL 对象的隐式提交残留无法完全避免。
     * 请勿对生产库执行 dryRun。
     *
     * @param config 恢复配置
     * @return 预检结果（永不返回 null）
     */
    public static PreflightResult dryRun(DatabaseRestoreConfig config) {
        long start = System.currentTimeMillis();
        List<CheckItem> checks = new ArrayList<>();
        List<Path> tempDirs = new ArrayList<>();
        List<String> createdTables = new ArrayList<>();
        try {
            List<Path> scripts = resolveScripts(config, checks, tempDirs);
            if (scripts == null) {
                return finish(MODE_DRY_RUN, checks, 0, start, null);
            }
            int statements = countStatements(scripts, checks);
            resolveManifest(config, scripts, checks);
            try (Connection conn = DriverManager.getConnection(
                    config.getJdbcUrl(), config.getUsername(), config.getPassword())) {
                checks.add(new CheckItem("目标库连接", true, config.getJdbcUrl()));
                beginSnapshot(conn, checks);
                int executed = 0;
                int skipped = 0;
                List<String> tablesBefore = List.of();
                try {
                    if (!config.isSkipEmptyCheck()) {
                        int existing = countUserTables(conn);
                        if (existing > 0) {
                            throw new IllegalStateException("目标库已存在 " + existing
                                    + " 张业务表，拒绝试运行（防止误覆盖；确需跳过请设置 skipEmptyCheck=true）");
                        }
                        checks.add(new CheckItem("空库检查", true, "目标库无业务表"));
                    }
                    tablesBefore = collectUserTables(conn);
                    for (Path script : scripts) {
                        String content = Files.readString(script, StandardCharsets.UTF_8);
                        for (String raw : FlywayScripts.splitStatements(content)) {
                            String sql = raw.trim();
                            if (sql.isEmpty()) {
                                continue;
                            }
                            if (config.isDropBeforeCreate()) {
                                String table = extractCreateTableName(sql);
                                if (table != null) {
                                    try (Statement statement = conn.createStatement()) {
                                        statement.execute("DROP TABLE IF EXISTS " + table);
                                    }
                                }
                            }
                            try (Statement statement = conn.createStatement()) {
                                statement.execute(sql);
                                executed++;
                                String created = extractCreateTableName(sql);
                                if (created != null && !createdTables.contains(created)) {
                                    createdTables.add(created);
                                }
                            } catch (SQLException e) {
                                if (!config.isContinueOnError()) {
                                    throw e;
                                }
                                skipped++;
                                log.warn("[backup] 试运行语句失败(继续): {} - {}",
                                        FlywayScripts.truncate(sql, 80), e.getMessage());
                            }
                        }
                    }
                    checks.add(new CheckItem("语句试执行", true,
                            "可执行 " + executed + " 条" + (skipped > 0 ? "，跳过 " + skipped + " 条" : "")));
                } finally {
                    rollbackQuietly(conn);
                    dropCreatedTablesQuietly(conn, tablesBefore, createdTables, checks);
                }
                return finish(MODE_DRY_RUN, checks, statements, start, null);
            }
        } catch (Exception e) {
            log.warn("[backup] 恢复试运行失败: {}", e.getMessage());
            checks.add(new CheckItem("语句试执行", false, e.getMessage()));
            return finish(MODE_DRY_RUN, checks, 0, start, e.getMessage());
        } finally {
            tempDirs.forEach(DatabaseBackupStrategy::deleteDirectory);
        }
    }

    // ==================== 内部步骤 ====================

    /**
     * 解析恢复来源：.sql 直接返回；.zip 解包到临时目录按文件名排序返回全部 .sql。
     * 校验失败返回 null（失败项已写入 checks）。
     */
    private static List<Path> resolveScripts(DatabaseRestoreConfig config, List<CheckItem> checks,
                                             List<Path> tempDirs) throws IOException {
        if (config == null) {
            checks.add(new CheckItem("配置校验", false, "DatabaseRestoreConfig 不能为空"));
            return null;
        }
        if (config.getJdbcUrl() == null || config.getJdbcUrl().isBlank()) {
            checks.add(new CheckItem("配置校验", false, "jdbcUrl 不能为空"));
            return null;
        }
        Path file = config.getScriptFile();
        if (file == null) {
            checks.add(new CheckItem("配置校验", false, "scriptFile 不能为空"));
            return null;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")) {
            if (!Files.exists(file)) {
                checks.add(new CheckItem("脚本可读", false, "归档不存在: " + file));
                return null;
            }
            Path tempDir = Files.createTempDirectory("db-preflight-");
            tempDirs.add(tempDir);
            List<Path> scripts = new ArrayList<>();
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file), StandardCharsets.UTF_8)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String entryName = entry.getName();
                    if (entryName.startsWith("/") || entryName.contains("..")) {
                        continue;
                    }
                    String baseName = entryName.substring(entryName.lastIndexOf('/') + 1);
                    if (!baseName.toLowerCase(Locale.ROOT).endsWith(".sql")) {
                        continue;
                    }
                    Path target = tempDir.resolve(baseName).normalize();
                    if (!target.startsWith(tempDir)) {
                        continue;
                    }
                    if (Files.exists(target)) {
                        target = tempDir.resolve(entryName.replace('/', '_')).normalize();
                    }
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                    scripts.add(target);
                }
            }
            if (scripts.isEmpty()) {
                checks.add(new CheckItem("脚本可读", false, "ZIP 归档中未找到 .sql 脚本: " + file));
                return null;
            }
            scripts.sort(Comparator.comparing(path -> path.getFileName().toString()));
            checks.add(new CheckItem("脚本可读", true, scripts.size() + " 个脚本（ZIP 归档解包）"));
            return scripts;
        }
        if (!Files.exists(file)) {
            checks.add(new CheckItem("脚本可读", false, "脚本不存在: " + file));
            return null;
        }
        if (!Files.isReadable(file)) {
            checks.add(new CheckItem("脚本可读", false, "脚本不可读: " + file));
            return null;
        }
        checks.add(new CheckItem("脚本可读", true, file.toString()));
        return List.of(file);
    }

    /**
     * 统计全部脚本可解析的非空语句数。
     */
    private static int countStatements(List<Path> scripts, List<CheckItem> checks) throws IOException {
        int statements = 0;
        for (Path script : scripts) {
            String content = Files.readString(script, StandardCharsets.UTF_8);
            for (String raw : FlywayScripts.splitStatements(content)) {
                if (!raw.trim().isEmpty()) {
                    statements++;
                }
            }
        }
        checks.add(new CheckItem("语句解析", true, statements + " 条语句"));
        return statements;
    }

    /**
     * 定位备份清单（.sql 同目录或 ZIP 归档内）；verifyChecksum=true 时逐脚本校验 SHA-256。
     * 清单缺失/校验失败写入 checks；无清单且未强制校验时记录提示并返回 null。
     */
    private static BackupManifest resolveManifest(DatabaseRestoreConfig config, List<Path> scripts,
                                                  List<CheckItem> checks) throws IOException {
        Path manifestFile = null;
        Path source = config.getScriptFile();
        if (source != null && source.getParent() != null) {
            Path sibling = source.getParent().resolve(BackupManifest.MANIFEST_FILE_NAME);
            if (Files.exists(sibling)) {
                manifestFile = sibling;
            }
        }
        if (manifestFile == null && !scripts.isEmpty()) {
            Path sibling = scripts.get(0).getParent().resolve(BackupManifest.MANIFEST_FILE_NAME);
            if (Files.exists(sibling)) {
                manifestFile = sibling;
            }
        }
        if (manifestFile == null || !Files.exists(manifestFile)) {
            if (config.isVerifyChecksum()) {
                checks.add(new CheckItem("备份清单", false,
                        "verifyChecksum=true 但未找到 " + BackupManifest.MANIFEST_FILE_NAME));
                throw new IllegalStateException("备份清单缺失且已开启强制校验");
            }
            checks.add(new CheckItem("备份清单", true, "无清单（未开启强制校验，跳过校验和）"));
            return null;
        }
        BackupManifest manifest = BackupManifest.fromJson(Files.readString(manifestFile, StandardCharsets.UTF_8));
        if (config.isVerifyChecksum()) {
            for (Path script : scripts) {
                String scriptName = script.getFileName().toString();
                BackupManifest.FileEntry entry = manifest.entryFor(scriptName);
                if (entry == null) {
                    checks.add(new CheckItem("备份清单", false, "缺少文件条目: " + scriptName));
                    throw new IllegalStateException("备份清单缺少文件条目: " + scriptName);
                }
                String actual = BackupManifest.sha256Hex(script);
                if (!actual.equalsIgnoreCase(entry.sha256())) {
                    checks.add(new CheckItem("备份清单", false,
                            "SHA-256 不匹配: " + scriptName + ", 期望 " + entry.sha256() + ", 实际 " + actual));
                    throw new IllegalStateException("SHA-256 校验和不匹配: " + scriptName);
                }
            }
            checks.add(new CheckItem("备份清单", true, scripts.size() + " 个脚本校验和全部通过"));
        } else {
            checks.add(new CheckItem("备份清单", true, "清单就绪（未开启强制校验）"));
        }
        return manifest;
    }

    /**
     * 建立目标库连接。
     */
    private static Connection connect(DatabaseRestoreConfig config, List<CheckItem> checks) {
        try {
            if (config.getDriverClass() != null && !config.getDriverClass().isBlank()) {
                try {
                    Class.forName(config.getDriverClass());
                } catch (ClassNotFoundException e) {
                    log.warn("[backup] 指定驱动类未找到（尝试由 jdbcUrl 自动发现）: {}", config.getDriverClass());
                }
            }
            Connection conn = DriverManager.getConnection(
                    config.getJdbcUrl(), config.getUsername(), config.getPassword());
            checks.add(new CheckItem("目标库连接", true, config.getJdbcUrl()));
            return conn;
        } catch (SQLException e) {
            checks.add(new CheckItem("目标库连接", false, e.getMessage()));
            return null;
        }
    }

    /**
     * 空库检查（skipEmptyCheck=true 时跳过）。
     */
    private static void checkEmpty(Connection conn, DatabaseRestoreConfig config, List<CheckItem> checks)
            throws SQLException {
        if (config.isSkipEmptyCheck()) {
            checks.add(new CheckItem("空库检查", true, "已跳过（skipEmptyCheck=true）"));
            return;
        }
        int existing = countUserTables(conn);
        if (existing > 0) {
            checks.add(new CheckItem("空库检查", false, "目标库已存在 " + existing + " 张业务表"));
            throw new IllegalStateException("目标库非空，预检不通过");
        }
        checks.add(new CheckItem("空库检查", true, "目标库无业务表"));
    }

    /**
     * 开启一致性快照事务：REPEATABLE_READ，不支持时回退 SERIALIZABLE。
     */
    private static void beginSnapshot(Connection conn, List<CheckItem> checks) {
        try {
            conn.setAutoCommit(false);
        } catch (SQLException e) {
            checks.add(new CheckItem("事务开启", false, e.getMessage()));
        }
        try {
            conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            checks.add(new CheckItem("事务隔离级别", true, "REPEATABLE_READ"));
        } catch (SQLException e) {
            try {
                conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                checks.add(new CheckItem("事务隔离级别", true, "SERIALIZABLE（REPEATABLE_READ 不支持）"));
            } catch (SQLException e2) {
                checks.add(new CheckItem("事务隔离级别", true, "使用驱动默认级别"));
            }
        }
    }

    /**
     * 统计目标库业务表数量（过滤系统 schema）。
     */
    private static int countUserTables(Connection conn) throws SQLException {
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
     * 采集目标库业务表名（过滤系统 schema）。
     */
    private static List<String> collectUserTables(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = meta.getTables(conn.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                if (schema != null && DatabaseBackupStrategy.SYSTEM_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String table = rs.getString("TABLE_NAME");
                if (table != null && !table.isBlank()) {
                    tables.add(table);
                }
            }
        }
        return tables;
    }

    /**
     * 删除试运行期间新建的表：DDL 在 H2/MySQL 等数据库会隐式提交、无法随事务回滚，
     * 回滚后显式 DROP 本次新建（执行前不存在）的表，恢复目标库原状。逐表吞异常，尽力而为。
     */
    private static void dropCreatedTablesQuietly(Connection conn, List<String> tablesBefore,
                                                 List<String> createdTables, List<CheckItem> checks) {
        if (createdTables.isEmpty()) {
            return;
        }
        try {
            conn.setAutoCommit(true);
        } catch (SQLException e) {
            log.warn("[backup] 试运行清理切换自动提交失败: {}", e.getMessage());
        }
        Set<String> before = new HashSet<>();
        for (String name : tablesBefore) {
            before.add(name.toUpperCase(Locale.ROOT));
        }
        Set<String> created = new HashSet<>();
        for (String name : createdTables) {
            created.add(name.toUpperCase(Locale.ROOT));
        }
        List<String> dropped = new ArrayList<>();
        List<String> missed = new ArrayList<>();
        try {
            for (String current : collectUserTables(conn)) {
                String key = current.toUpperCase(Locale.ROOT);
                if (created.contains(key) && !before.contains(key)) {
                    try (Statement statement = conn.createStatement()) {
                        statement.execute("DROP TABLE IF EXISTS " + current);
                        dropped.add(current);
                    } catch (SQLException e) {
                        missed.add(current + "（" + e.getMessage() + "）");
                    }
                }
            }
        } catch (SQLException e) {
            missed.add(e.getMessage());
        }
        checks.add(new CheckItem("试运行清理", missed.isEmpty(),
                "本次新建表 " + createdTables.size() + " 张，已删除 " + dropped.size()
                        + (missed.isEmpty() ? "，目标库已恢复原状" : "，未删净: " + missed)));
    }

    /**
     * 提取 CREATE TABLE 语句的目标表名（非 CREATE TABLE 返回 null）。
     */
    private static String extractCreateTableName(String sql) {
        Matcher matcher = DatabaseRestoreStrategy.CREATE_TABLE.matcher(sql.trim());
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 回滚并吞掉异常（只读/试运行事务收尾）。
     */
    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException e) {
            log.warn("[backup] 试运行回滚失败: {}", e.getMessage());
        }
    }

    /**
     * 汇总预检结果。
     */
    private static PreflightResult finish(String mode, List<CheckItem> checks, int statementCount,
                                          long start, String errorMessage) {
        boolean success = errorMessage == null && checks.stream().allMatch(CheckItem::passed);
        return new PreflightResult(success, mode, statementCount, checks.size(),
                List.copyOf(checks), System.currentTimeMillis() - start, errorMessage);
    }

    /**
     * 单项预检结果
     *
     * @param name    检查项名称
     * @param passed  是否通过
     * @param message 说明（通过时为摘要，失败时为原因）
     */
    public record CheckItem(String name, boolean passed, String message) {
    }

    /**
     * 预检结果
     *
     * @param success         是否全部通过
     * @param mode            预检模式（{@link #MODE_CHECK} / {@link #MODE_DRY_RUN}）
     * @param statementCount  解析出的语句总数
     * @param filesChecked    检查的脚本数
     * @param checks          各检查项明细
     * @param durationMillis  预检耗时
     * @param errorMessage    失败原因（成功为 null）
     */
    public record PreflightResult(boolean success, String mode, int statementCount, int filesChecked,
                                  List<CheckItem> checks, long durationMillis, String errorMessage) {

        /**
         * 紧凑构造器：明细防御性拷贝。
         */
        public PreflightResult {
            checks = checks == null ? List.of() : List.copyOf(checks);
        }
    }
}
