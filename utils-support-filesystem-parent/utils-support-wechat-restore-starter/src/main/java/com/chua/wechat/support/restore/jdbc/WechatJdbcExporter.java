package com.chua.wechat.support.restore.jdbc;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.task.restore.DataRestoreConfig;
import com.chua.common.support.task.restore.DataRestoreResult;
import com.chua.common.support.task.restore.ExportFormat;
import com.chua.wechat.support.restore.WechatBlobCodec;
import com.chua.wechat.support.restore.WechatDataRestore;
import com.chua.wechat.support.restore.WechatExportUtils;
import lombok.extern.slf4j.Slf4j;
import org.sqlite.SQLiteConfig;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 微信明文数据库（SQLite）JDBC 导出器。
 *
 * <p>配合 {@link com.chua.wechat.support.restore.sqlcipher.SqlCipherDecryptor} 使用：
 * 把已解密的明文 SQLite 数据库逐表读出，按 {@link ExportFormat} 输出为可读文件。</p>
 *
 * <h3>输出约定</h3>
 * <ul>
 *   <li>CSV / EXCEL — 每张表一个文件，文件名 {@code <库名>__<表名>.<后缀>}（自动去重）</li>
 *   <li>SQL — 每张表一个 {@code CREATE TABLE + INSERT} 脚本</li>
 *   <li>无数据或无用户表的库不产生文件，由上层汇总判定</li>
 * </ul>
 *
 * <h3>配置项（{@link DataRestoreConfig#getOptions()}）</h3>
 * <ul>
 *   <li>{@code limit} — 每张表最多导出条数，0 或缺省表示全部</li>
 *   <li>{@code table.whitelist} — 逗号分隔的表名白名单（非空时仅导出命中表）</li>
 *   <li>{@code table.blacklist} — 逗号分隔的表名黑名单</li>
 * </ul>
 *
 * <p>BLOB 列会先尝试 ZSTD 解压（微信 4.x 压缩消息体），再尝试 UTF-8 文本解码，
 * 都不成立时输出十六进制；超过 {@value #MAX_HEX_BYTES} 字节的二进制仅输出长度占位，
 * 避免导出文件被图片等大对象撑爆。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class WechatJdbcExporter {

    /**
    * SQLite JDBC 连接前缀
    */
    private static final String JDBC_URL_PREFIX = "jdbc:sqlite:";

    /**
    * SQLite 内部表名前缀
    */
    private static final String SQLITE_INTERNAL_PREFIX = "sqlite_";

    /**
    * 枚举用户表
    */
    private static final String LIST_TABLES_SQL =
            "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name";

    /**
    * options 键：表名白名单
    */
    public static final String OPTION_TABLE_WHITELIST = "table.whitelist";

    /**
    * options 键：表名黑名单
    */
    public static final String OPTION_TABLE_BLACKLIST = "table.blacklist";

    /**
    * 二进制列转十六进制输出的字节上限
    */
    private static final int MAX_HEX_BYTES = 4096;

    /**
    * 工具类禁止实例化。
    */
    private WechatJdbcExporter() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /**
    * 导出明文数据库（输出目录取配置或源文件所在目录）。
    *
    * @param dbFile 已解密的明文 SQLite 文件
    * @param config 还原配置
    * @return 还原结果
    * @throws Exception 导出异常
    */
    public static DataRestoreResult export(File dbFile, DataRestoreConfig config) throws Exception {
        File outputDir = config.getOutputDir() != null ? config.getOutputDir() : dbFile.getParentFile();
        return export(dbFile, config, outputDir);
    }

    /**
    * 导出明文数据库到指定目录。
    *
    * @param dbFile    已解密的明文 SQLite 文件
    * @param config    还原配置
    * @param outputDir 输出目录
    * @return 还原结果
    * @throws Exception 导出异常
    */
    public static DataRestoreResult export(File dbFile, DataRestoreConfig config, File outputDir) throws Exception {
        ExportFormat format = config.getFormat() != null ? config.getFormat() : ExportFormat.CSV;
        if (format == ExportFormat.JSON) {
            throw new UnsupportedOperationException("微信 SQLCipher 还原暂不支持 JSON 输出，请使用 CSV / SQL / EXCEL");
        }
        if (dbFile == null || !dbFile.isFile()) {
            throw new IllegalArgumentException("待导出的数据库文件不存在: "
                    + (dbFile == null ? "null" : dbFile.getAbsolutePath()));
        }
        if (outputDir != null && !outputDir.exists() && !outputDir.mkdirs()) {
            throw new IllegalStateException("创建输出目录失败: " + outputDir.getAbsolutePath());
        }

        String dbBase = WechatExportUtils.sanitizeFileName(stripExtension(dbFile.getName()));
        Charset charset = Charset.forName(config.getCharset());
        Set<String> usedNames = new HashSet<>();
        List<File> outputs = new ArrayList<>(16);

        try (Connection connection = open(dbFile)) {
            List<String> tables = resolveTables(connection, config);
            if (tables.isEmpty()) {
                return DataRestoreResult.failure("数据库中没有可导出的用户表: " + dbFile.getAbsolutePath());
            }
            for (String table : tables) {
                try {
                    File outputFile = exportTable(connection, outputDir, usedNames, dbBase, table,
                            format, config, charset);
                    if (outputFile != null) {
                        outputs.add(outputFile);
                        log.info("微信数据表导出完成: {}.{} -> {} ({} 字节)",
                                dbBase, table, outputFile.getName(), outputFile.length());
                    }
                } catch (Exception e) {
                    log.warn("微信数据表导出失败，已跳过: {}.{} - {}", dbBase, table, e.getMessage());
                }
            }
        }

        if (outputs.isEmpty()) {
            return DataRestoreResult.failure("数据库所有表均无数据可导出: " + dbFile.getAbsolutePath());
        }
        long totalSize = outputs.stream().mapToLong(File::length).sum();
        log.info("微信数据库导出完成: {} -> {} 个文件 (格式 {})", dbFile.getName(), outputs.size(), format.value());
        return DataRestoreResult.success(outputs, totalSize, 0);
    }

    /**
    * 打开 SQLite 连接。
    *
    * <p>默认以<b>只读</b>模式打开：导出只需读，而微信正在运行时会持续持有
    * {@code -wal} / {@code -shm}，若以读写方式打开，SQLite 在关闭时可能触发 WAL
    * 检查点并尝试截断文件，从而抛出 {@code SQLITE_IOERR_TRUNCATE}（实机复现）。
    * 只读打开失败（例如需要 WAL 恢复的库）时回退为默认读写模式。</p>
    *
    * @param dbFile 明文数据库文件
    * @return JDBC 连接
    * @throws SQLException 连接失败
    */
    private static Connection open(File dbFile) throws SQLException {
        SQLiteConfig readOnly = new SQLiteConfig();
        readOnly.setReadOnly(true);
        try {
            return DriverManager.getConnection(JDBC_URL_PREFIX + dbFile.getAbsolutePath(),
                    readOnly.toProperties());
        } catch (SQLException e) {
            log.debug("以只读模式打开失败，回退读写模式: {} - {}", dbFile.getName(), e.getMessage());
            return DriverManager.getConnection(JDBC_URL_PREFIX + dbFile.getAbsolutePath());
        }
    }

    /**
    * 解析待导出的表名列表（过滤内部表并按白/黑名单筛选）。
    *
    * @param connection JDBC 连接
    * @param config     还原配置
    * @return 表名列表
    * @throws SQLException 查询失败
    */
    private static List<String> resolveTables(Connection connection, DataRestoreConfig config) throws SQLException {
        Set<String> whitelist = parseNameSet(config.getOptions().get(OPTION_TABLE_WHITELIST));
        Set<String> blacklist = parseNameSet(config.getOptions().get(OPTION_TABLE_BLACKLIST));
        List<String> tables = new ArrayList<>(32);
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(LIST_TABLES_SQL)) {
            while (resultSet.next()) {
                String name = resultSet.getString(1);
                if (name == null || name.isBlank() || name.startsWith(SQLITE_INTERNAL_PREFIX)) {
                    continue;
                }
                if (!whitelist.isEmpty() && !whitelist.contains(name)) {
                    continue;
                }
                if (blacklist.contains(name)) {
                    continue;
                }
                tables.add(name);
            }
        }
        return tables;
    }

    /**
    * 导出单张表。
    *
    * @param connection JDBC 连接
    * @param outputDir  输出目录
    * @param usedNames  已占用文件名集合（去重用）
    * @param dbBase     库名（文件名前缀）
    * @param table      表名
    * @param format     输出格式
    * @param config     还原配置
    * @param charset    字符集
    * @return 输出文件；表无列或无数据时返回 null
    * @throws Exception 读写异常
    */
    private static File exportTable(Connection connection, File outputDir, Set<String> usedNames,
                                    String dbBase, String table, ExportFormat format,
                                    DataRestoreConfig config, Charset charset) throws Exception {
        List<String> columns = new ArrayList<>(16);
        List<Map<String, Object>> rows = readRows(connection, table, config, columns);
        if (columns.isEmpty()) {
            log.info("跳过无列的表: {}.{}", dbBase, table);
            return null;
        }
        if (rows.isEmpty()) {
            log.info("跳过无数据的表: {}.{}", dbBase, table);
            return null;
        }

        String baseName = dbBase + "__" + WechatExportUtils.sanitizeFileName(table);
        File outputFile = new File(outputDir, uniqueFileName(usedNames, baseName, extensionOf(format)));

        switch (format) {
            case EXCEL:
                // FileSystem.create 对未知类型返回 null 而不是抛异常，必须自己判空，
                // 否则用户只会看到一句无从下手的 NullPointerException
                FileSystem excelFs = FileSystem.create("excel");
                if (excelFs == null) {
                    throw new IllegalStateException("Excel 导出不可用：类路径上找不到 SPI 名称为 'excel' 的 FileSystem 实现"
                            + "（需要 utils-support-excel-starter 及其 POI 依赖）。"
                            + "请补上该依赖，或改用 format=csv / format=sql 导出");
                }
                excelFs.write(outputFile)
                        .withHeaders(columns)
                        .write(rows)
                        .finish();
                break;
            case SQL:
                String sql = WechatExportUtils.buildSqlScript(rows, table,
                        config.getTargetSchema(), config.isIncludeStructure());
                Files.writeString(outputFile.toPath(), sql, charset);
                break;
            case CSV:
            default:
                WechatExportUtils.writeCsv(outputFile, columns, rows, charset);
                break;
        }
        return outputFile;
    }

    /**
    * 读取整表数据。
    *
    * @param connection JDBC 连接
    * @param table      表名
    * @param config     还原配置
    * @param columnsOut 出参：列名列表
    * @return 行数据
    * @throws SQLException 查询失败
    */
    private static List<Map<String, Object>> readRows(Connection connection, String table,
                                                     DataRestoreConfig config,
                                                     List<String> columnsOut) throws SQLException {
        long limit = parseLong(config.getOptions().get(WechatDataRestore.OPTION_LIMIT), 0L);
        String sql = "SELECT * FROM " + quoteIdentifier(table) + (limit > 0 ? " LIMIT " + limit : "");
        List<Map<String, Object>> rows = new ArrayList<>(256);
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            int columnCount = metaData.getColumnCount();
            for (int i = 1; i <= columnCount; i++) {
                columnsOut.add(metaData.getColumnLabel(i));
            }
            while (resultSet.next()) {
                Map<String, Object> row = new LinkedHashMap<>(columnCount * 2);
                for (int i = 1; i <= columnCount; i++) {
                    row.put(columnsOut.get(i - 1), convert(resultSet.getObject(i)));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /**
    * 转换列值：BLOB 依次尝试 ZSTD 解压、UTF-8 文本、十六进制。
    *
    * @param value 原始列值
    * @return 转换后的值
    */
    private static Object convert(Object value) {
        if (!(value instanceof byte[] bytes) || bytes.length == 0) {
            return value;
        }
        // 微信 4.x 会把消息正文与 source 用 ZSTD 压进列里，先尝试解压成文本
        String inflated = WechatBlobCodec.tryDecompressText(bytes);
        if (inflated != null) {
            return inflated;
        }
        String text = decodeUtf8(bytes);
        if (text != null) {
            return text;
        }
        return bytes.length > MAX_HEX_BYTES ? "<binary " + bytes.length + " bytes>" : toHex(bytes);
    }

    /**
    * 尝试按 UTF-8 文本解码（含控制字符或替换符时判定为非文本）。
    *
    * @param bytes 字节数组
    * @return 解码后的文本，非文本返回 null
    */
    private static String decodeUtf8(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.indexOf('\uFFFD') >= 0) {
            return null;
        }
        String cleaned = text.replace("\0", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
                return null;
            }
        }
        return cleaned;
    }

    /**
    * 字节数组转小写十六进制。
    *
    * @param bytes 字节数组
    * @return 十六进制文本
    */
    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }

    /**
    * 引用 SQLite 标识符。
    *
    * @param name 标识符
    * @return 双引号包裹的标识符
    */
    private static String quoteIdentifier(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /**
    * 解析逗号分隔名单。
    *
    * @param value options 原始值
    * @return 名单集合
    */
    private static Set<String> parseNameSet(Object value) {
        if (value == null) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        for (String item : String.valueOf(value).split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isBlank()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
    * 解析 long 类型 options 值。
    *
    * @param value        原始值
    * @param defaultValue 解析失败时的默认值
    * @return long 值
    */
    private static long parseLong(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException e) {
                log.warn("options 数值解析失败，使用默认值 {}: {}", defaultValue, value);
            }
        }
        return defaultValue;
    }

    /**
    * 取输出格式对应扩展名。
    *
    * @param format 输出格式
    * @return 扩展名（含点）
    */
    private static String extensionOf(ExportFormat format) {
        switch (format) {
            case SQL:
                return ".sql";
            case EXCEL:
                return ".xlsx";
            case CSV:
            default:
                return ".csv";
        }
    }

    /**
    * 去除文件名扩展名。
    *
    * @param fileName 文件名
    * @return 不含扩展名的文件名
    */
    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
    * 生成不重复的输出文件名。
    *
    * @param usedNames 已占用文件名集合
    * @param baseName  基础文件名
    * @param extension 扩展名（含点）
    * @return 唯一文件名
    */
    private static String uniqueFileName(Set<String> usedNames, String baseName, String extension) {
        String candidate = baseName + extension;
        int suffix = 1;
        while (usedNames.contains(candidate)) {
            candidate = baseName + "_" + suffix + extension;
            suffix++;
        }
        usedNames.add(candidate);
        return candidate;
    }
}
