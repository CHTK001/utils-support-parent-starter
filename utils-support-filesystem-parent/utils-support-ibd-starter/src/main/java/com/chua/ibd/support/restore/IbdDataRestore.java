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
 * <h3>前置条件</h3>
 * <p>本实现<b>不是零依赖</b>：它靠外部 Python 工具链解析 {@code .ibd}，因此需要</p>
 * <ol>
 *   <li>系统上有可用的 Python（{@code python} / {@code python3} / {@code py} 之一在 PATH 上）；</li>
 *   <li>该 Python 能调到 ibd2sql。注意 <b>ibd2sql 没有发布到 PyPI</b>，只能从
 *       <a href="https://github.com/ddcw/ibd2sql">github.com/ddcw/ibd2sql</a>
 *       获取（纯 Python3、无第三方依赖，下载即用）；而且仓库 v2.x 是<b>包</b>、
 *       没有 {@code __main__.py}，所以 {@code python -m ibd2sql} <b>跑不通</b>，
 *       真正的入口是仓库根目录的 {@code main.py}。</li>
 * </ol>
 * <p>接法任选其一：把仓库根目录放进 {@code PYTHONPATH}（本实现会自动定位其中的
 * {@code main.py}），或通过 {@code options['ibd2sql.path']} 指定 {@code main.py} 或其所在目录。
 * 两者都没有时会抛出<b>可直接照做</b>的提示（见
 * {@link #executeIbd2Sql(File, DataRestoreConfig)}）。</p>
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
     * options 键：ibd2sql 入口路径（{@code main.py} / {@code ibd2sql.py}，或包含它们的目录）。
     *
     * <p>ibd2sql <b>没有发布到 PyPI</b>，只能从
     * <a href="https://github.com/ddcw/ibd2sql">github.com/ddcw/ibd2sql</a> 获取；
     * 仓库版本（v2.x）是个<b>包</b>且没有 {@code __main__.py}，所以
     * {@code python -m ibd2sql} 是跑不通的，必须走它的 {@code main.py}。
     * 本实现会按「显式配置 → {@code -m ibd2sql} → 自动定位包旁的 main.py」依次探测，
     * 都找不到时给出明确提示。</p>
     */
    public static final String OPTION_IBD2SQL_PATH = "ibd2sql.path";

    /**
     * 命令执行超时时间（秒）
     */
    private static final long COMMAND_TIMEOUT_SECONDS = 300L;

    /**
     * ibd2sql 仓库入口脚本名（v2.x 布局：仓库根目录下的 main.py）
     */
    private static final String IBD2SQL_MAIN = "main.py";

    /**
     * ibd2sql 单文件模块名（v1.x 布局，可直接 {@code -m ibd2sql}）
     */
    private static final String IBD2SQL_MODULE = "ibd2sql.py";

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
        String rawSql = executeIbd2Sql(source, config);

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
        String rawSql = executeIbd2Sql(source, config);

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
        String rawSql = executeIbd2Sql(source, config);

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
     * 执行外部 ibd2sql 命令以生成 SQL 内容。
     *
     * <p>注意<b>不能写死 {@code python -m ibd2sql}</b>：ibd2sql 没有发布到 PyPI，
     * 仓库 v2.x 是个<b>包</b>且没有 {@code __main__.py}，{@code -m ibd2sql} 必然失败
     * （报 {@code No module named ibd2sql.__main__}），真正的入口是仓库根目录的
     * {@code main.py}。所以这里按下面的顺序探测实际可用的调用方式：</p>
     * <ol>
     *   <li>{@code options['ibd2sql.path']} 显式指定（文件 = 入口脚本；目录 = 在其中找
     *       {@code main.py} / {@code ibd2sql.py}）；</li>
     *   <li>{@code python -m ibd2sql --help} 可用（v1.x 单文件布局 / 用户自行做了
     *       {@code __main__} 垫片）；</li>
     *   <li>自动定位：{@code python -c "import ibd2sql,os;print(...)"} 拿到包所在目录，
     *       若其父目录有 {@code main.py} 就用它（用户只要把仓库根目录放进
     *       {@code PYTHONPATH} 即可）。</li>
     * </ol>
     *
     * @param ibdFile 待解析的 IBD 文件
     * @param config  还原配置（读取 {@code ibd2sql.path}）
     * @return 生成的 SQL 字符串
     * @throws Exception 前置条件缺失或执行失败时抛出
     */
    private String executeIbd2Sql(File ibdFile, DataRestoreConfig config) throws Exception {
        String python = findPython();
        if (python == null) {
            throw new IllegalStateException("IBD 还原需要 Python 运行时：请安装 Python 并确保 "
                    + "python / python3 / py 之一在 PATH 上（本模块通过外部 ibd2sql 解析 .ibd）");
        }
        List<String> invocation = resolveIbd2SqlInvocation(python, config);
        if (invocation == null) {
            throw new IllegalStateException("IBD 还原缺少 ibd2sql：该工具**没有发布到 PyPI**，"
                    + "需从 https://github.com/ddcw/ibd2sql 获取（纯 Python3、无第三方依赖，下载即用）。"
                    + "它没有 __main__.py，`" + python + " -m ibd2sql` 是跑不通的，两种接法任选其一："
                    + "① 把仓库根目录放进 PYTHONPATH（本实现会自动定位其中的 main.py）；"
                    + "② 通过 options['" + OPTION_IBD2SQL_PATH + "'] 直接指定 main.py 或其所在目录");
        }
        List<String> command = new ArrayList<>(invocation);
        command.add(ibdFile.getAbsolutePath());
        command.add("--ddl");
        command.add("--sql");
        log.debug("执行 ibd2sql: {}", command);
        CmdResult result = CmdExecutors.execute(command.toArray(new String[0]),
                COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!result.isSuccess()) {
            String stderr = result.getStderr() == null ? "" : result.getStderr();
            throw new RuntimeException("ibd2sql 执行失败, 命令: " + String.join(" ", command)
                    + ", exit=" + result.getExitCode() + ", error: " + stderr);
        }
        return result.getStdout();
    }

    /**
     * 探测 ibd2sql 的实际调用方式。
     *
     * @param python Python 命令名
     * @param config 还原配置（读取 {@code ibd2sql.path}）
     * @return 命令前缀（如 {@code [python, -m, ibd2sql]} 或 {@code [python, /path/main.py]}）；
     *         都探测不到时返回 {@code null}
     */
    private static List<String> resolveIbd2SqlInvocation(String python, DataRestoreConfig config) {
        // 1) 显式配置优先
        Object option = config.getOptions().get(OPTION_IBD2SQL_PATH);
        if (option != null && !String.valueOf(option).isBlank()) {
            File entry = new File(String.valueOf(option));
            if (entry.isFile()) {
                return List.of(python, entry.getAbsolutePath());
            }
            if (entry.isDirectory()) {
                for (String name : new String[]{IBD2SQL_MAIN, IBD2SQL_MODULE}) {
                    File candidate = new File(entry, name);
                    if (candidate.isFile()) {
                        return List.of(python, candidate.getAbsolutePath());
                    }
                }
                // 目录本身可能就是一个包目录（内含 ibd2sql/），退到自动定位
            } else {
                log.warn("options['{}'] 指向的路径不存在: {}", OPTION_IBD2SQL_PATH, entry.getAbsolutePath());
            }
        }
        // 2) v1.x 单文件布局 / 用户自行做了 __main__ 垫片
        if (isIbd2SqlModuleAvailable(python)) {
            return List.of(python, "-m", "ibd2sql");
        }
        // 3) 自动定位包旁的 main.py
        String main = locateIbd2SqlMain(python);
        if (main != null) {
            return List.of(python, main);
        }
        return null;
    }

    /**
     * 探测 {@code python -m ibd2sql} 是否可用。
     *
     * @param python Python 命令名
     * @return 可用返回 true
     */
    private static boolean isIbd2SqlModuleAvailable(String python) {
        try {
            CmdResult result = CmdExecutors.execute(
                    new String[]{python, "-m", "ibd2sql", "--help"}, 15, TimeUnit.SECONDS);
            return result.isSuccess();
        } catch (Exception e) {
            log.debug("探测 `-m ibd2sql` 失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 自动定位 ibd2sql 仓库根目录下的 {@code main.py}。
     *
     * <p>只依赖 {@code import ibd2sql} —— 用户把仓库根目录放进 {@code PYTHONPATH} 就够，
     * 不需要再配任何选项。</p>
     *
     * @param python Python 命令名
     * @return main.py 的绝对路径；定位不到返回 {@code null}
     */
    private static String locateIbd2SqlMain(String python) {
        String probe = "import ibd2sql,os,sys;p=os.path.dirname(os.path.dirname(ibd2sql.__file__));"
                + "m=os.path.join(p,'" + IBD2SQL_MAIN + "');sys.stdout.write(m if os.path.isfile(m) else '')";
        try {
            CmdResult result = CmdExecutors.execute(
                    new String[]{python, "-c", probe}, 15, TimeUnit.SECONDS);
            if (!result.isSuccess()) {
                return null;
            }
            String path = result.getStdout() == null ? "" : result.getStdout().trim();
            return path.isEmpty() ? null : path;
        } catch (Exception e) {
            log.debug("自动定位 ibd2sql main.py 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 在系统中查找可用的 Python 可执行文件。
     *
     * @return 找到的 Python 命令名称；一个都不可用时返回 {@code null}
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
        // 不再兜底返回 "python"：一个候选都不可用时返回 null，
        // 由调用方给出「请安装 Python」的明确提示，而不是让下游报一个看不懂的进程启动错误
        return null;
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
