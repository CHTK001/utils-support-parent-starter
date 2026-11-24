package com.chua.common.support.task.backup;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 数据库备份策略（type = "database"）
 *
 * <p>对「当前数据库」做纯 JDBC 逻辑备份：通过 {@link DatabaseMetaData} 读取
 * 表结构与主键，导出 {@code CREATE TABLE} 与全量 {@code INSERT} 语句为 SQL 脚本。
 * 不依赖数据库原生 dump 工具，也不依赖 Spring DataSource。
 *
 * <p>目录结构与文件备份保持一致，便于统一清理与恢复：
 * <pre>
 *   {backupDir}/
 *   ├── {yyyy-MM-dd}/database-{dbName}-{timestamp}.sql   ← 当天备份
 *   └── archive/{yyyy-MM-dd}.zip                          ← 历史备份压缩
 * </pre>
 *
 * <h3>可选能力</h3>
 * <ul>
 *   <li>远端上传 — 配置 {@link DatabaseBackupConfig#getRemote()} 后按
 *       {@link BackupUploader} SPI 上传；SFTP 实现依赖
 *       <b>utils-support-ssh-starter</b> 模块，未引入时跳过上传并告警，
 *       本地备份不受影响。</li>
 * </ul>
 *
 * <p>说明：通用 JDBC 元数据导出不覆盖存储过程、触发器、视图等高级对象；
 * 需要物理级备份时请使用数据库原生工具（如 mysqldump）。
 *
 * @author CH
 * @since 2026/11/24
 */
@Slf4j
@Spi("database")
public class DatabaseBackupStrategy implements BackupStrategy {

    /**
     * 类型
     */
    private static final String TYPE = "database";
    /**
     * Archive_dir
     */
    private static final String ARCHIVE_DIR = "archive";
    /**
     * 日期_fmt
     */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /**
     * 时间戳_fmt
     */
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    /**
     * 文件前缀
     */
    private static final String FILE_PREFIX = "database-";
    /**
     * 默认数据库名（从 URL 解析失败时使用）
     */
    private static final String DEFAULT_DB_NAME = "database";
    /**
     * 系统 schema（部分驱动如 H2 会把系统表也按 TABLE 类型返回，导出/恢复时必须跳过）。
     * 包级开放供 {@link DatabaseRestoreStrategy} 空库检查复用。
     */
    static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "information_schema", "sys", "system", "pg_catalog", "performance_schema", "mysql");

    /**
     * 系统表过滤后仍需保留的精确数值类型：恢复侧必须带回精度与标度，
     * 否则目标库按默认标度（如 H2/MySQL 的 DECIMAL(10,0)）四舍五入，造成数据失真。
     */
    private static final Set<String> DECIMAL_TYPE_NAMES = Set.of("DECIMAL", "NUMERIC");
    /**
     * 需要带回长度声明的字符/二进制类型（TYPE_NAME 大写归一后比对）。
     */
    private static final Set<String> CHARACTER_TYPE_NAMES = Set.of(
            "CHAR", "CHARACTER", "VARCHAR", "VARCHAR2", "CHARACTER VARYING", "NCHAR", "NVARCHAR",
            "NATIONAL CHAR", "NATIONAL CHARACTER", "NATIONAL CHARACTER VARYING",
            "BINARY", "VARBINARY", "BINARY VARYING");

    /**
     * 策略类型标识：database。
     */
    @Override
    public String type() {
        return TYPE;
    }

    /**
     * 文件备份入口不适用于数据库备份。
     *
     * <p>数据库备份请使用 {@link #execute(DatabaseBackupConfig)}
     * 或 {@link BackupManager#backupDatabase(DatabaseBackupConfig)}。
     */
    @Override
    public BackupResult execute(BackupConfig config) {
        return BackupResult.failure("数据库备份请使用 execute(DatabaseBackupConfig)（BackupManager#backupDatabase）");
    }

    /**
     * 执行数据库备份：导出 schema + 数据 → 归档昨日 → 清理过期 → 可选上传远端。
     *
     * <p>全过程向 {@link DatabaseBackupConfig#getListeners()} 发布
     * {@link BackupEvent}：开始（START）、每张表导出完成（TABLE）、
     * 成功（SUCCESS）、失败（FAILURE）；监听器异常只告警不中断备份。
     *
     * @param config 数据库备份配置
     * @return 备份结果
     */
    public BackupResult execute(DatabaseBackupConfig config) {
        long start = System.currentTimeMillis();
        try {
            validate(config);
            String dbName = extractDbName(config.getJdbcUrl());
            Files.createDirectories(config.getBackupDir());

            // 1. 导出当前数据库到当天目录
            String today = LocalDate.now().format(DATE_FMT);
            Path todayDir = config.getBackupDir().resolve(today);
            Files.createDirectories(todayDir);
            List<Path> files = new ArrayList<>();
            fire(config, event(config, dbName, BackupEvent.PHASE_START).build());
            ExportOutcome outcome = exportDatabase(config, todayDir, files, dbName);

            // 2. 归档昨日 + 清理过期（目录结构与文件备份一致）
            if (config.isCompressArchives()) {
                archiveYesterday(config);
            }
            cleanExpired(toFileConfig(config));

            long duration = System.currentTimeMillis() - start;
            long totalSize = files.stream().mapToLong(DatabaseBackupStrategy::sizeOf).sum();
            BackupResult result = BackupResult.success(todayDir, files, totalSize, duration);

            // 3. 可选：上传远端（依赖 utils-support-ssh-starter 等 SPI 实现）
            uploadRemote(config, files);

            fire(config, event(config, dbName, BackupEvent.PHASE_SUCCESS)
                    .result(result)
                    .totalTables(outcome.tables())
                    .finishedTables(outcome.tables())
                    .totalRows(outcome.rows())
                    .build());
            return result;
        } catch (Exception e) {
            log.error("[backup] 数据库备份失败: {}", e.getMessage(), e);
            BackupResult failure = BackupResult.failure("数据库备份失败: " + e.getMessage());
            fire(config, BackupEvent.builder()
                    .type(TYPE)
                    .time(LocalDateTime.now())
                    .phase(BackupEvent.PHASE_FAILURE)
                    .result(failure)
                    .errorMessage(e.getMessage())
                    .build());
            return failure;
        }
    }

    /**
     * 构造带公共字段（类型/库名/目录/时间/阶段）的事件 builder。
     */
    private BackupEvent.BackupEventBuilder event(DatabaseBackupConfig config, String dbName, String phase) {
        return BackupEvent.builder()
                .type(TYPE)
                .dbName(dbName)
                .backupDir(config.getBackupDir())
                .time(LocalDateTime.now())
                .phase(phase);
    }

    /**
     * 按事件阶段分发到对应监听器回调；单个监听器异常只告警，不中断备份与其余监听器。
     */
    private void fire(DatabaseBackupConfig config, BackupEvent event) {
        List<BackupListener> listeners = config.getListeners();
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        for (BackupListener listener : listeners) {
            try {
                switch (event.getPhase()) {
                    case BackupEvent.PHASE_START -> listener.onStart(event);
                    case BackupEvent.PHASE_TABLE -> listener.onTableDone(event);
                    case BackupEvent.PHASE_SUCCESS -> listener.onSuccess(event);
                    case BackupEvent.PHASE_FAILURE -> listener.onFailure(event);
                    default -> log.warn("[backup] 未知备份事件阶段: {}", event.getPhase());
                }
            } catch (Exception e) {
                log.warn("[backup] 备份监听器 {} 处理 {} 事件失败: {}",
                        listener.getClass().getSimpleName(), event.getPhase(), e.getMessage());
            }
        }
    }

    /**
     * 执行数据库恢复（默认委托 {@link DatabaseRestoreStrategy}；自定义 database 策略可覆盖）。
     *
     * @param config 数据库恢复配置
     * @return 恢复结果
     */
    public DatabaseRestoreResult restore(DatabaseRestoreConfig config) {
        return new DatabaseRestoreStrategy().restore(config);
    }

    /**
     * 清理过期备份（委托按天策略，目录结构一致）
     */
    @Override
    public int cleanExpired(BackupConfig config) {
        return new DefaultDailyBackupStrategy().cleanExpired(config);
    }

    /**
     * 获取备份列表（委托按天策略）
     */
    @Override
    public List<Path> listBackups(BackupConfig config) {
        return new DefaultDailyBackupStrategy().listBackups(config);
    }

    // ==================== 导出实现 ====================

    /**
     * 导出数据库到当天目录：连接 → 遍历表 → 写 CREATE TABLE + INSERT，
     * 每张表导出完成后发布 TABLE 事件。
     *
     * @return 导出统计（表数与总行数）
     */
    private ExportOutcome exportDatabase(DatabaseBackupConfig config, Path todayDir, List<Path> files,
                                         String dbName) throws Exception {
        loadDriverIfPresent(config.getDriverClass());
        Path target = todayDir.resolve(FILE_PREFIX + dbName + "-" + LocalDateTime.now().format(TS_FMT) + ".sql");

        try (Connection conn = DriverManager.getConnection(config.getJdbcUrl(), config.getUsername(), config.getPassword())) {
            DatabaseMetaData meta = conn.getMetaData();
            String quote = identifierQuote(meta);
            List<String> tables = listTables(conn, meta, config);
            int finished = 0;
            long totalRows = 0;
            try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                writer.write("-- 数据库逻辑备份");
                writer.newLine();
                writer.write("-- 生成时间: " + LocalDateTime.now());
                writer.newLine();
                writer.write("-- 数据库: " + dbName);
                writer.newLine();
                writer.newLine();
                for (String table : tables) {
                    long rows = writeTable(writer, conn, meta, config, quote, table);
                    finished++;
                    totalRows += rows;
                    fire(config, event(config, dbName, BackupEvent.PHASE_TABLE)
                            .table(table)
                            .tableRows(rows)
                            .totalRows(totalRows)
                            .totalTables(tables.size())
                            .finishedTables(finished)
                            .build());
                }
            }
            files.add(target);
            log.info("[backup] 数据库备份完成: {} 张表 → {}", tables.size(), target);
            return new ExportOutcome(tables.size(), totalRows);
        }
    }

    /**
     * 导出单张表：建表语句 + 全量 INSERT。
     *
     * @return 该表导出的行数
     */
    private long writeTable(BufferedWriter writer, Connection conn, DatabaseMetaData meta,
                            DatabaseBackupConfig config, String quote, String table) throws SQLException, IOException {
        Set<String> primaryKeys = new LinkedHashSet<>();
        try (ResultSet rs = meta.getPrimaryKeys(conn.getCatalog(), config.getSchema(), table)) {
            while (rs.next()) {
                primaryKeys.add(rs.getString("COLUMN_NAME"));
            }
        }

        writer.write(buildCreateTable(conn, meta, config, quote, table, primaryKeys));
        writer.newLine();
        long rows = exportRows(writer, conn, config, quote, table);
        writer.newLine();
        return rows;
    }

    /**
     * 生成 CREATE TABLE 语句。
     */
    private String buildCreateTable(Connection conn, DatabaseMetaData meta, DatabaseBackupConfig config,
                                    String quote, String table, Set<String> primaryKeys) throws SQLException {
        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE ").append(quote(table, quote)).append(" (\n");
        try (ResultSet rs = meta.getColumns(conn.getCatalog(), config.getSchema(), table, "%")) {
            boolean first = true;
            while (rs.next()) {
                if (!first) {
                    ddl.append(",\n");
                }
                String column = rs.getString("COLUMN_NAME");
                String typeName = rs.getString("TYPE_NAME");
                ddl.append("  ").append(quote(column, quote)).append(' ')
                        .append(typeName == null ? "TEXT" : typeName);
                int precision = rs.getInt("COLUMN_SIZE");
                int scale = rs.getInt("DECIMAL_DIGITS");
                String normalized = typeName == null ? "" : typeName.toUpperCase(Locale.ROOT);
                if (precision > 0 && DECIMAL_TYPE_NAMES.contains(normalized)) {
                    // 精确数值：必须带回精度与标度，否则目标库按默认标度取整导致数据失真
                    ddl.append('(').append(precision).append(',').append(Math.max(scale, 0)).append(')');
                } else if (precision > 0 && CHARACTER_TYPE_NAMES.contains(normalized)) {
                    ddl.append('(').append(precision).append(')');
                }
                if (rs.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls) {
                    ddl.append(" NOT NULL");
                }
                String defaultValue = rs.getString("COLUMN_DEF");
                if (defaultValue != null) {
                    ddl.append(" DEFAULT ").append(defaultValue);
                }
                first = false;
            }
        }
        if (!primaryKeys.isEmpty()) {
            ddl.append(",\n  PRIMARY KEY (");
            boolean first = true;
            for (String pk : primaryKeys) {
                if (!first) {
                    ddl.append(", ");
                }
                ddl.append(quote(pk, quote));
                first = false;
            }
            ddl.append(')');
        }
        ddl.append("\n);\n");
        return ddl.toString();
    }

    /**
     * 导出全量数据为 INSERT 语句，按 batchSize flush。
     *
     * @return 导出的行数
     */
    private long exportRows(BufferedWriter writer, Connection conn, DatabaseBackupConfig config,
                            String quote, String table) throws SQLException, IOException {
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM " + quote(table, quote))) {
            ResultSetMetaData rsm = rs.getMetaData();
            int columnCount = rsm.getColumnCount();
            boolean[] binary = new boolean[columnCount + 1];
            for (int i = 1; i <= columnCount; i++) {
                binary[i] = isBinaryType(rsm.getColumnType(i));
            }

            StringBuilder row = new StringBuilder();
            int pending = 0;
            long rows = 0;
            while (rs.next()) {
                rows++;
                row.setLength(0);
                row.append("INSERT INTO ").append(quote(table, quote)).append(" VALUES (");
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) {
                        row.append(", ");
                    }
                    appendValue(row, rs, i, binary[i]);
                }
                row.append(");\n");
                writer.write(row.toString());
                if (++pending >= config.getBatchSize()) {
                    writer.flush();
                    pending = 0;
                }
            }
            writer.flush();
            return rows;
        }
    }

    /**
     * 追加单列值：二进制列转 0x 十六进制，其余按字符串转义单引号，NULL 保持原样。
     */
    private void appendValue(StringBuilder row, ResultSet rs, int index, boolean binary) throws SQLException {
        if (binary) {
            byte[] bytes = rs.getBytes(index);
            if (bytes == null) {
                row.append("NULL");
            } else {
                row.append("0x").append(hex(bytes));
            }
            return;
        }
        String value = rs.getString(index);
        if (value == null) {
            row.append("NULL");
        } else {
            row.append('\'').append(value.replace("'", "''")).append('\'');
        }
    }

    /**
     * 判断 JDBC 类型是否为二进制类型。
     */
    private boolean isBinaryType(int type) {
        return type == Types.BINARY || type == Types.VARBINARY
                || type == Types.LONGVARBINARY || type == Types.BLOB;
    }

    /**
     * 字节数组转十六进制字符串。
     */
    private String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 查询需要备份的表清单（按 include/exclude 过滤）。
     */
    private List<String> listTables(Connection conn, DatabaseMetaData meta, DatabaseBackupConfig config)
            throws SQLException {
        Set<String> include = config.getIncludeTables() == null ? Set.of() : config.getIncludeTables();
        Set<String> exclude = config.getExcludeTables() == null ? Set.of() : config.getExcludeTables();
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = meta.getTables(conn.getCatalog(), config.getSchema(), "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String schemaName = rs.getString("TABLE_SCHEM");
                if (schemaName != null && SYSTEM_SCHEMAS.contains(schemaName.toLowerCase())) {
                    continue;
                }
                String name = rs.getString("TABLE_NAME");
                if (!include.isEmpty() && !include.contains(name)) {
                    continue;
                }
                if (exclude.contains(name)) {
                    continue;
                }
                tables.add(name);
            }
        }
        return tables;
    }

    /**
     * 获取数据库标识符引用符（无则回退双引号）。
     */
    private String identifierQuote(DatabaseMetaData meta) throws SQLException {
        String quote = meta.getIdentifierQuoteString();
        if (quote == null || quote.isBlank() || " ".equals(quote)) {
            return "\"";
        }
        return quote;
    }

    /**
     * 用数据库引用符包裹标识符。
     */
    private String quote(String identifier, String quoteString) {
        return quoteString + identifier + quoteString;
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
     * 从 JDBC URL 提取数据库名，失败时返回默认名。
     */
    private String extractDbName(String jdbcUrl) {
        try {
            String rest = jdbcUrl.substring(jdbcUrl.indexOf("://") + 3);
            int slash = rest.indexOf('/');
            if (slash < 0) {
                return DEFAULT_DB_NAME;
            }
            String db = rest.substring(slash + 1);
            int end = db.length();
            int question = db.indexOf('?');
            if (question >= 0) {
                end = question;
            }
            int semicolon = db.indexOf(';');
            if (semicolon >= 0) {
                end = Math.min(end, semicolon);
            }
            db = db.substring(0, end);
            return db.isBlank() ? DEFAULT_DB_NAME : db;
        } catch (Exception e) {
            return DEFAULT_DB_NAME;
        }
    }

    /**
     * 校验必填配置。
     */
    private void validate(DatabaseBackupConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("DatabaseBackupConfig 不能为空");
        }
        if (config.getJdbcUrl() == null || config.getJdbcUrl().isBlank()) {
            throw new IllegalArgumentException("jdbcUrl 不能为空");
        }
        if (config.getBackupDir() == null) {
            throw new IllegalArgumentException("backupDir 不能为空");
        }
    }

    // ==================== 归档与上传 ====================

    /**
     * 将昨日备份目录压缩为 ZIP（目录结构与按天文件备份一致），成功后删除原目录。
     */
    private void archiveYesterday(DatabaseBackupConfig config) {
        String yesterday = LocalDate.now().minusDays(1).format(DATE_FMT);
        Path yesterdayDir = config.getBackupDir().resolve(yesterday);
        if (!Files.exists(yesterdayDir)) {
            return;
        }
        Path zipFile = config.getBackupDir().resolve(ARCHIVE_DIR).resolve(yesterday + ".zip");
        try {
            Files.createDirectories(zipFile.getParent());
            try (ZipOutputStream zipStream = new ZipOutputStream(Files.newOutputStream(zipFile));
                 Stream<Path> walk = Files.walk(yesterdayDir)) {
                walk.filter(Files::isRegularFile).forEach(file -> {
                    try {
                        zipStream.putNextEntry(new ZipEntry(
                                yesterdayDir.relativize(file).toString().replace('\\', '/')));
                        Files.copy(file, zipStream);
                        zipStream.closeEntry();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
            deleteDirectory(yesterdayDir);
        } catch (Exception e) {
            log.warn("[backup] 归档昨日数据库备份失败: {}", e.getMessage());
        }
    }

    /**
     * 递归删除目录。包级开放供 {@link DatabaseRestoreStrategy} 清理 ZIP 解包临时目录复用。
     */
    static void deleteDirectory(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    log.warn("[backup] 删除文件失败: {} - {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("[backup] 删除目录失败: {} - {}", dir, e.getMessage());
        }
    }

    /**
     * 按配置上传备份文件到远端（可选能力）。
     *
     * <p>SFTP 上传依赖 <b>utils-support-ssh-starter</b> 模块提供的
     * SftpBackupUploader SPI 实现；该模块不在 classpath 时 SPI 查找返回空，
     * 此处跳过上传并告警，不影响本地备份结果。
     */
    private void uploadRemote(DatabaseBackupConfig config, List<Path> files) {
        BackupRemoteConfig remote = config.getRemote();
        if (remote == null || remote.getHost() == null || remote.getHost().isBlank()) {
            return;
        }
        String protocol = remote.getProtocol() == null || remote.getProtocol().isBlank()
                ? BackupRemoteConfig.PROTOCOL_SFTP : remote.getProtocol();
        BackupUploader uploader = ServiceProvider.of(BackupUploader.class).getExtension(protocol);
        if (uploader == null) {
            log.warn("[backup] 未找到 {} 上传器（SFTP 需引入 utils-support-ssh-starter 模块），跳过远端上传", protocol);
            return;
        }
        for (Path file : files) {
            uploader.upload(file, remote);
        }
        log.info("[backup] 备份文件已上传至远端 {}@{}:{}", remote.getUsername(), remote.getHost(), remote.getPort());
    }

    /**
     * 转换为文件备份配置（复用按天策略的清理与列表能力）。
     */
    private BackupConfig toFileConfig(DatabaseBackupConfig config) {
        return BackupConfig.builder()
                .backupDir(config.getBackupDir())
                .retentionDays(config.getRetentionDays())
                .compressArchives(config.isCompressArchives())
                .build();
    }

    /**
     * 获取文件大小（失败返回 0）。
     */
    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * 导出统计（表数与总行数），供备份成功事件使用。
     */
    private record ExportOutcome(int tables, long rows) {
    }
}
