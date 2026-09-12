package com.chua.ibd.support.restore;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.task.restore.AbstractDataRestore;
import com.chua.common.support.task.restore.DataRestoreConfig;
import com.chua.common.support.task.restore.DataRestoreResult;
import com.chua.common.support.task.restore.ExportFormat;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;

/**
 * IBD 数据还原器。
 *
 * <p>将 MySQL InnoDB 表空间文件（.ibd）还原为可读数据文件（CSV / SQL / Excel）。
 * 底层通过 Python ibd2sql 工具解析 IBD 文件，再将解析结果按指定格式导出。</p>
 *
 * <h3>输出格式</h3>
 * <ul>
 *   <li>CSV — 逗号分隔文本，每行一条数据，首行为表头</li>
 *   <li>SQL — 完整 SQL 脚本，含 DDL（建表）和 INSERT 数据</li>
 *   <li>EXCEL — Excel 工作簿，每条数据一行，表头加粗冻结</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 还原 IBD 文件为 CSV
 * DataRestore restore = DataRestore.create("ibd");
 * DataRestoreResult result = restore.restore(new File("users.ibd"));
 *
 * // 还原为 Excel，指定输出目录
 * DataRestoreConfig config = DataRestoreConfig.builder()
 *         .format(ExportFormat.EXCEL)
 *         .outputDir(new File("out"))
 *         .build();
 * DataRestore restore = DataRestore.create("ibd", config);
 * DataRestoreResult result = restore.restore(new File("orders.ibd"));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"ibd", "ibd2sql"})
public class IbdDataRestore extends AbstractDataRestore {

    /**
     * IBD 还原器 SPI 类型名
     */
    private static final String SPI_TYPE = "ibd";

    /**
     * 命令执行超时时间（秒）
     */
    private static final long COMMAND_TIMEOUT_SECONDS = 300L;

    /**
     * 使用默认配置创建。
     */
    public IbdDataRestore() {
        super(SPI_TYPE);
    }

    /**
     * 使用指定配置创建。
     *
     * @param config 还原配置
     */
    public IbdDataRestore(DataRestoreConfig config) {
        super(SPI_TYPE, config);
    }

    @Override
    protected DataRestoreResult doRestore(File source, DataRestoreConfig config) throws Exception {
        ExportFormat format = config.getFormat();
        if (format == null) {
            format = ExportFormat.CSV;
        }

        switch (format) {
            case CSV:
                return doRestoreCsv(source, config);
            case SQL:
                return doRestoreSql(source, config);
            case EXCEL:
                return doRestoreExcel(source, config);
            default:
                throw new UnsupportedOperationException("IBD 还原器不支持的输出格式: " + format);
        }
    }

    /**
     * 还原为 CSV 文件。
     *
     * @param source 源 IBD 文件
     * @param config 还原配置
     * @return 还原结果
     * @throws Exception 执行异常
     */
    private DataRestoreResult doRestoreCsv(File source, DataRestoreConfig config) throws Exception {
        // 获取表名
        String tableName = config.getTargetTable();
        if (tableName == null || tableName.isBlank()) {
            tableName = source.getName().replace(".ibd", "").replaceFirst("^\\d+", "");
            if (tableName.isBlank()) {
                tableName = source.getName();
            }
        }

        // 执行 ibd2sql 获取原始 SQL
        String rawSql = executeIbd2Sql(source);

        // 解析 SQL 为行数据
        List<Map<String, Object>> rows = parseSqlToRows(rawSql);
        List<String> columns = extractColumns(rows);

        // 构造输出文件路径
        File outputDir = config.getOutputDir() != null ? config.getOutputDir() : source.getParentFile();
        File csvFile = new File(outputDir, tableName + ".csv");
        ensureDir(csvFile.getParentFile());

        // 写入 CSV
        String charset = config.getCharset();
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(csvFile), Charset.forName(charset))) {
            // 表头
            if (config.isIncludeStructure()) {
                writer.write(String.join(",", columns) + "\n");
            }
            // 数据行
            for (Map<String, Object> row : rows) {
                List<String> values = new ArrayList<>(columns.size());
                for (String col : columns) {
                    values.add(escapeCsvField(row.get(col)));
                }
                writer.write(String.join(",", values) + "\n");
            }
        }

        log.info("IBD 还原为 CSV 完成: {} -> {} ({} 行)",
                source.getName(), csvFile.getName(), rows.size());
        return DataRestoreResult.success(List.of(csvFile), csvFile.length(), 0);
    }

    /**
     * 还原为 SQL 文件。
     *
     * @param source 源 IBD 文件
     * @param config 还原配置
     * @return 还原结果
     * @throws Exception 执行异常
     */
    private DataRestoreResult doRestoreSql(File source, DataRestoreConfig config) throws Exception {
        // 执行 ibd2sql 获取原始 SQL（已包含 DDL + INSERT）
        String rawSql = executeIbd2Sql(source);

        // 按目标库表名调整 SQL
        String tableName = config.getTargetTable();
        String schemaName = config.getTargetSchema();
        String adjustedSql;
        if (tableName != null && !tableName.isBlank()) {
            adjustedSql = replaceTableNames(rawSql, tableName);
        } else {
            adjustedSql = rawSql;
        }
        if (schemaName != null && !schemaName.isBlank()) {
            adjustedSql = "USE `" + schemaName + "`;\n" + adjustedSql;
        }

        // 构造输出文件路径
        File outputDir = config.getOutputDir() != null ? config.getOutputDir() : source.getParentFile();
        String fileName = (tableName != null ? tableName : source.getName()) + ".sql";
        File sqlFile = new File(outputDir, fileName);
        ensureDir(sqlFile.getParentFile());

        // 写入 SQL
        String charset = config.getCharset();
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(sqlFile), Charset.forName(charset))) {
            writer.write(adjustedSql);
        }

        log.info("IBD 还原为 SQL 完成: {} -> {}", source.getName(), sqlFile.getName());
        return DataRestoreResult.success(List.of(sqlFile), sqlFile.length(), 0);
    }

    /**
     * 还原为 Excel 文件。
     *
     * @param source 源 IBD 文件
     * @param config 还原配置
     * @return 还原结果
     * @throws Exception 执行异常
     */
    private DataRestoreResult doRestoreExcel(File source, DataRestoreConfig config) throws Exception {
        // 获取表名
        String tableName = config.getTargetTable();
        if (tableName == null || tableName.isBlank()) {
            tableName = source.getName().replace(".ibd", "").replaceFirst("^\\d+", "");
            if (tableName.isBlank()) {
                tableName = source.getName();
            }
        }

        // 执行 ibd2sql 获取原始 SQL
        String rawSql = executeIbd2Sql(source);

        // 解析为行数据
        List<Map<String, Object>> rows = parseSqlToRows(rawSql);
        List<String> columns = extractColumns(rows);

        // 构造输出文件路径
        File outputDir = config.getOutputDir() != null ? config.getOutputDir() : source.getParentFile();
        File excelFile = new File(outputDir, tableName + ".xlsx");
        ensureDir(excelFile.getParentFile());

        // 通过 FileSystem SPI 写入 Excel
        FileSystem excelFs = FileSystem.create("excel");
        excelFs.write(excelFile)
                .withHeaders(columns)
                .write(rows)
                .finish();

        log.info("IBD 还原为 Excel 完成: {} -> {} ({} 行)",
                source.getName(), excelFile.getName(), rows.size());
        return DataRestoreResult.success(List.of(excelFile), excelFile.length(), 0);
    }

    // ==================== 私有工具方法 ====================

    /**
     * 执行外部 Python ibd2sql 命令以生成 SQL 内容。
     *
     * <p>命令格式：python -m ibd2sql "path.ibd" --ddl --sql</p>
     *
     * @param ibdFile 待解析的 IBD 文件
     * @return 生成的 SQL 字符串
     * @throws Exception 执行失败时抛出
     */
    private String executeIbd2Sql(File ibdFile) throws Exception {
        String python = findPython();
        String command = python + " -m ibd2sql \"" + ibdFile.getAbsolutePath() + "\" --ddl --sql";
        log.debug("执行 ibd2sql: {}", command);
        CmdResult result = CmdExecutors.execute(command, COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!result.isSuccess()) {
            throw new RuntimeException("ibd2sql 执行失败, exit=" + result.getExitCode()
                    + ", error: " + result.getStderr());
        }
        return result.getStdout();
    }

    /**
     * 在系统中查找可用的 Python 可执行文件。
     *
     * @return 找到的 Python 命令名称
     */
    private static String findPython() {
        String osName = System.getProperty("os.name").toLowerCase();
        String[] candidates = osName.contains("win")
                ? new String[]{"python", "python3", "py"}
                : new String[]{"python3", "python"};
        for (String cmd : candidates) {
            try {
                CmdResult result = CmdExecutors.execute(cmd + " --version", 5, TimeUnit.SECONDS);
                if (result.isSuccess()) {
                    return cmd;
                }
            } catch (Exception e) {
                log.warn("尝试 Python 命令失败: {}", cmd, e);
            }
        }
        return "python";
    }

    /**
     * 将 ibd2sql 输出的 SQL 解析为行数据列表。
     *
     * <p>解析 CREATE TABLE 提取列名，解析 INSERT INTO 提取数据值。</p>
     *
     * @param rawSql 原始 SQL 文本
     * @return 行数据列表
     */
    private List<Map<String, Object>> parseSqlToRows(String rawSql) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String normalized = rawSql.toUpperCase();

        // 解析 CREATE TABLE 获取列名（用于表头）
        int createIdx = normalized.indexOf("CREATE TABLE");
        int insertStart = normalized.indexOf("INSERT INTO");
        if (insertStart == -1) {
            // 无数据，仅 DDL
            return rows;
        }

        String insertSection = rawSql.substring(insertStart);
        // 逐行解析 INSERT 语句
        String[] lines = insertSection.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.endsWith(";")) {
                // 提取 INSERT ... VALUES (...)
                int valuesIdx = trimmed.toUpperCase().indexOf("VALUES");
                if (valuesIdx == -1) {
                    continue;
                }
                String valuesStr = trimmed.substring(valuesIdx + 6).trim();
                // 去掉外层括号（如有）
                if (valuesStr.startsWith("(") && valuesStr.endsWith(")")) {
                    valuesStr = valuesStr.substring(1, valuesStr.length() - 1);
                }
                // 解析值列表（逗号分隔，考虑括号嵌套和引号）
                List<String> values = splitValues(valuesStr);
                if (rows.isEmpty()) {
                    // 第一行：用列名索引占位（此时列名为 0,1,2...）
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    for (int i = 0; i < values.size(); i++) {
                        row.put(String.valueOf(i), values.get(i));
                    }
                    rows.add(row);
                } else {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    for (int i = 0; i < values.size(); i++) {
                        row.put(String.valueOf(i), values.get(i));
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    /**
     * 提取行数据的列名列表。
     *
     * @param rows 行数据
     * @return 列名列表
     */
    private List<String> extractColumns(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(rows.get(0).keySet());
    }

    /**
     * 替换 SQL 中的表名。
     *
     * @param rawSql  原始 SQL
     * @param newName 新表名
     * @return 替换后的 SQL
     */
    private String replaceTableNames(String rawSql, String newName) {
        // 简单替换 CREATE TABLE 和 INSERT INTO 中的表名
        String result = rawSql;
        // 替换 CREATE TABLE `xxx`
        result = result.replaceFirst("(?i)CREATE\\s+TABLE\\s+(?:`[^`]+`|\\S+)",
                "CREATE TABLE `" + newName + "`");
        // 替换 INSERT INTO `xxx`
        result = result.replaceFirst("(?i)INSERT\\s+INTO\\s+(?:`[^`]+`|\\S+)",
                "INSERT INTO `" + newName + "`");
        return result;
    }

    /**
     * 拆分 VALUES 子句中的值列表。
     *
     * <p>处理引号内的逗号和嵌套括号。</p>
     *
     * @param valuesStr VALUES 子句内容（不含 VALUES 关键字）
     * @return 拆分后的值列表
     */
    private List<String> splitValues(String valuesStr) {
        List<String> values = new ArrayList<>();
        int depth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        int start = 0;
        for (int i = 0; i < valuesStr.length(); i++) {
            char c = valuesStr.charAt(i);
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (!inSingleQuote && !inDoubleQuote) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    values.add(valuesStr.substring(start, i).trim());
                    start = i + 1;
                }
            }
        }
        // 最后一个值
        if (start < valuesStr.length()) {
            values.add(valuesStr.substring(start).trim());
        }
        return values;
    }

    /**
     * 转义 CSV 字段。
     *
     * @param value 原始值
     * @return 转义后的值
     */
    private String escapeCsvField(Object value) {
        if (value == null) {
            return "";
        }
        String str = String.valueOf(value);
        if (str.contains(",") || str.contains("\"") || str.contains("\n") || str.contains("\r")) {
            str = str.replace("\"", "\"\"");
            return "\"" + str + "\"";
        }
        return str;
    }

    /**
     * 确保目录存在。
     *
     * @param dir 目录路径
     */
    private void ensureDir(File dir) throws Exception {
        if (dir != null && !dir.exists()) {
            boolean created = dir.mkdirs();
            if (!created) {
                log.warn("创建目录失败: {}", dir.getAbsolutePath());
            }
        }
    }
}
