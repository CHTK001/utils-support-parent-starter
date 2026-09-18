package com.chua.ibd.support.restore;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.task.restore.AbstractDataRestore;
import com.chua.common.support.task.restore.DataRestoreConfig;
import com.chua.common.support.task.restore.DataRestoreResult;
import com.chua.common.support.task.restore.ExportFormat;
import com.chua.ibd.support.innodb.IbdTableDefinition;
import com.chua.ibd.support.innodb.IbdTableReader;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IBD 数据还原器。
 *
 * <p>把 MySQL InnoDB 表空间文件（{@code .ibd}）还原成可读数据文件（CSV / SQL / Excel）。</p>
 *
 * <h3>输出格式</h3>
 * <ul>
 *   <li>CSV — 逗号分隔文本，每行一条数据，首行为表头</li>
 *   <li>SQL — 完整 SQL 脚本，含 DDL（建库建表）和 INSERT 数据</li>
 *   <li>EXCEL — Excel 工作簿，每条数据一行，表头加粗冻结</li>
 * </ul>
 *
 * <h3>两种解析引擎</h3>
 * <p>{@code options['engine']} 控制走哪条路：</p>
 * <ol>
 *   <li><b>{@code native}（默认）</b> —— 本模块自带的纯 Java 解析器
 *       （{@code com.chua.ibd.support.innodb}），直接读页：第 0 页拿页大小、
 *       SDI 页拿表结构 JSON、叶子页拿行数据。表结构来自 SDI，
 *       所以<b>不需要 Python、不需要 ibd2sql、也不需要连 MySQL Server</b>。
 *       结构信息足够时还能自己生成 DDL。</li>
 *   <li><b>{@code ibd2sql}</b> —— 调外部 Python 工具
 *       <a href="https://github.com/ddcw/ibd2sql">ibd2sql</a> 拿它生成的 SQL 文本再解析。
 *       注意该工具<b>没有发布到 PyPI</b>，需要手动获取并把仓库根目录放进
 *       {@code PYTHONPATH}（或用 {@code options['ibd2sql.path']} 指定入口）。</li>
 *   <li><b>{@code auto}</b> —— 先试 {@code native}，失败再退到 {@code ibd2sql}，
 *       两条都失败时抛出同时包含两个原因的错误。</li>
 * </ol>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 一行链式还原为 CSV（零外部依赖）
 * DataRestoreResult result = DataRestore.create("ibd").restore(new File("users.ibd"));
 *
 * // 还原为 SQL，指定目标库表名
 * DataRestoreConfig config = DataRestoreConfig.builder()
 *         .format(ExportFormat.SQL)
 *         .targetSchema("mydb")
 *         .targetTable("users")
 *         .outputDir(new File("out"))
 *         .build();
 * DataRestoreResult result = DataRestore.create("ibd", config).restore(new File("users.ibd"));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"ibd", "ibd2sql"})
public class IbdDataRestore extends AbstractDataRestore {

    /**
    * IBD 还原器 SPI 类型名。
    */
    private static final String SPI_TYPE = "ibd";

    /**
    * options 键：解析引擎，取值 {@code native} / {@code ibd2sql} / {@code auto}。
    */
    public static final String OPTION_ENGINE = "engine";

    /**
    * options 键：渲染 {@code TIMESTAMP} 用的时区 id（如 {@code Asia/Shanghai}），默认取本机时区。
    */
    public static final String OPTION_TIME_ZONE = "time.zone";

    /**
    * 引擎名：纯 Java 解析（默认）。
    */
    public static final String ENGINE_NATIVE = "native";

    /**
    * 引擎名：外部 Python ibd2sql。
    */
    public static final String ENGINE_IBD2SQL = "ibd2sql";

    /**
    * 引擎名：先 native 后 ibd2sql。
    */
    public static final String ENGINE_AUTO = "auto";

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
    * 命令执行超时时间（秒）。
    */
    private static final long COMMAND_TIMEOUT_SECONDS = 300L;

    /**
    * ibd2sql 仓库入口脚本名（v2.x 布局：仓库根目录下的 main.py）。
    */
    private static final String IBD2SQL_MAIN = "main.py";

    /**
    * ibd2sql 单文件模块名（v1.x 布局，可直接 {@code -m ibd2sql}）。
    */
    private static final String IBD2SQL_MODULE = "ibd2sql.py";

    /**
    * 表名引用（可带库名限定）：{@code `db`.`tbl`} 或 {@code `tbl`} 或裸名 {@code tbl}。
    */
    private static final String TABLE_REFERENCE =
            "(`[^`]+`(?:\\s*\\.\\s*`[^`]+`)?|[A-Za-z0-9_$]+)";

    /**
    * {@code CREATE TABLE [IF NOT EXISTS] <表名引用>}。
    *
    * <p>{@code IF NOT EXISTS} 必须留在捕获组 1 里原样保留 —— 早先的实现用
    * {@code \S+} 匹配表名，会把 {@code IF} 当成表名吃掉，产出
    * {@code CREATE TABLE `t` NOT EXISTS ...} 这种语法错误的 DDL。</p>
    */
    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile(
            "(?i)(CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?)" + TABLE_REFERENCE);

    /**
    * {@code INSERT INTO <表名引用>}。
    *
    * <p>必须<b>全局</b>替换：ibd2sql 每条记录输出一条 INSERT，一张 200 行的表就有
    * 200 条语句，早先用 {@code replaceFirst} 只会改中第一条。</p>
    */
    private static final Pattern INSERT_INTO_PATTERN = Pattern.compile(
            "(?i)(INSERT\\s+INTO\\s+)" + TABLE_REFERENCE);

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
        TableData data = extract(source, config);
        String tableName = resolveTableName(source, config, data);

        File outputDir = config.getOutputDir() != null ? config.getOutputDir() : source.getParentFile();
        File csvFile = new File(outputDir, tableName + ".csv");
        ensureDir(csvFile.getParentFile());

        String charset = config.getCharset();
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(csvFile), Charset.forName(charset))) {
            if (config.isIncludeStructure()) {
                writer.write(String.join(",", data.columns) + "\n");
            }
            for (Map<String, Object> row : data.rows) {
                List<String> values = new ArrayList<>(data.columns.size());
                for (String col : data.columns) {
                    values.add(escapeCsvField(row.get(col)));
                }
                writer.write(String.join(",", values) + "\n");
            }
        }

        log.info("IBD 还原为 CSV 完成: {} -> {} ({} 行, 引擎={})",
                source.getName(), csvFile.getName(), data.rows.size(), data.engine);
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
        TableData data = extract(source, config);
        String tableName = resolveTableName(source, config, data);
        String schemaName = config.getTargetSchema();

        String script = data.rawSql != null
                ? schemaPrologue(schemaName) + replaceTableNames(data.rawSql, schemaName, config.getTargetTable())
                : buildSqlScript(data, schemaName, tableName, config.isIncludeStructure());

        File outputDir = config.getOutputDir() != null ? config.getOutputDir() : source.getParentFile();
        File sqlFile = new File(outputDir, tableName + ".sql");
        ensureDir(sqlFile.getParentFile());

        String charset = config.getCharset();
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(sqlFile), Charset.forName(charset))) {
            writer.write(script);
        }

        log.info("IBD 还原为 SQL 完成: {} -> {} ({} 行, 引擎={})",
                source.getName(), sqlFile.getName(), data.rows.size(), data.engine);
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
        TableData data = extract(source, config);
        String tableName = resolveTableName(source, config, data);

        File outputDir = config.getOutputDir() != null ? config.getOutputDir() : source.getParentFile();
        File excelFile = new File(outputDir, tableName + ".xlsx");
        ensureDir(excelFile.getParentFile());

        // 注意 FileSystem.create 对未知类型**不抛异常而是返回 null**（见本仓库踩坑记录），
        // 所以必须自己判空，否则用户只会看到一句无从下手的 NullPointerException
        FileSystem excelFs = FileSystem.create("excel");
        if (excelFs == null) {
            throw new IllegalStateException("Excel 导出不可用：类路径上找不到 SPI 名称为 'excel' 的 FileSystem 实现"
                    + "（需要 utils-support-excel-starter 及其 POI 依赖）。"
                    + "请补上该依赖，或改用 format=CSV / format=SQL 导出");
        }
        excelFs.write(excelFile)
                .withHeaders(data.columns)
                .write(data.rows)
                .finish();

        log.info("IBD 还原为 Excel 完成: {} -> {} ({} 行, 引擎={})",
                source.getName(), excelFile.getName(), data.rows.size(), data.engine);
        return DataRestoreResult.success(List.of(excelFile), excelFile.length(), 0);
    }

    // ==================== 数据提取（两种引擎） ====================

    /**
    * 一次还原过程中提取出来的表格数据。
    */
    private static final class TableData {

        /**
        * 实际使用的引擎名。
        */
        private final String engine;

        /**
        * 输出列名。
        */
        private final List<String> columns;

        /**
        * 行数据。
        */
        private final List<Map<String, Object>> rows;

        /**
        * 表定义（仅 native 引擎有）。
        */
        private final IbdTableDefinition definition;

        /**
        * ibd2sql 原始 SQL（仅 ibd2sql 引擎有）。
        */
        private final String rawSql;

        /**
        * 构造。
        *
        * @param engine     引擎名
        * @param columns    输出列名
        * @param rows       行数据
        * @param definition 表定义
        * @param rawSql     ibd2sql 原始 SQL
        */
        private TableData(String engine, List<String> columns, List<Map<String, Object>> rows,
                          IbdTableDefinition definition, String rawSql) {
            this.engine = engine;
            this.columns = columns;
            this.rows = rows;
            this.definition = definition;
            this.rawSql = rawSql;
        }
    }

    /**
    * 按配置选择引擎并提取数据。
    *
    * @param source 源 IBD 文件
    * @param config 还原配置
    * @return 提取结果
    * @throws Exception 两条路都失败时抛出
    */
    private TableData extract(File source, DataRestoreConfig config) throws Exception {
        String engine = engineOf(config);
        if (ENGINE_IBD2SQL.equals(engine)) {
            return extractViaIbd2Sql(source, config);
        }
        try {
            return extractNative(source, config);
        } catch (Exception e) {
            if (!ENGINE_AUTO.equals(engine)) {
                throw e;
            }
            log.warn("纯 Java 解析失败，回退 ibd2sql: {}", e.getMessage());
            try {
                return extractViaIbd2Sql(source, config);
            } catch (Exception fallback) {
                throw new IllegalStateException("两种解析引擎都失败。纯 Java: " + e.getMessage()
                        + "；ibd2sql: " + fallback.getMessage(), fallback);
            }
        }
    }

    /**
    * 取配置里的引擎名。
    *
    * @param config 还原配置
    * @return 引擎名（默认 {@code native}）
    */
    private static String engineOf(DataRestoreConfig config) {
        Object option = config.getOptions().get(OPTION_ENGINE);
        if (option == null) {
            return ENGINE_NATIVE;
        }
        String engine = String.valueOf(option).trim().toLowerCase(Locale.ROOT);
        return engine.isEmpty() ? ENGINE_NATIVE : engine;
    }

    /**
    * 用纯 Java 解析器提取数据。
    *
    * @param source 源 IBD 文件
    * @param config 还原配置
    * @return 提取结果
    * @throws Exception 解析失败
    */
    private TableData extractNative(File source, DataRestoreConfig config) throws Exception {
        ZoneId zone = resolveZone(config);
        try (IbdTableReader reader = IbdTableReader.open(source, zone)) {
            List<Map<String, Object>> rows = reader.readAll();
            IbdTableDefinition definition = reader.definition();
            log.debug("纯 Java 解析: {} -> {}.{} 共 {} 行（删除标记 {} 条）",
                    source.getName(), definition.schema(), definition.name(),
                    rows.size(), reader.deletedRows());
            return new TableData(ENGINE_NATIVE, reader.columnNames(), rows, definition, null);
        }
    }

    /**
    * 取渲染时区。
    *
    * @param config 还原配置
    * @return 时区（默认本机时区）
    */
    private static ZoneId resolveZone(DataRestoreConfig config) {
        Object option = config.getOptions().get(OPTION_TIME_ZONE);
        if (option == null || String.valueOf(option).isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(String.valueOf(option).trim());
        } catch (Exception e) {
            log.warn("options['{}'] 不是合法时区 id: {}，改用本机时区", OPTION_TIME_ZONE, option);
            return ZoneId.systemDefault();
        }
    }

    /**
    * 用外部 ibd2sql 提取数据。
    *
    * @param source 源 IBD 文件
    * @param config 还原配置
    * @return 提取结果
    * @throws Exception 执行失败
    */
    private TableData extractViaIbd2Sql(File source, DataRestoreConfig config) throws Exception {
        String rawSql = executeIbd2Sql(source, config);
        List<Map<String, Object>> rows = parseSqlToRows(rawSql);
        List<String> columns = parseCreateTableColumns(rawSql);
        if (columns.isEmpty()) {
            columns = extractColumns(rows);
        }
        return new TableData(ENGINE_IBD2SQL, columns, rows, null, rawSql);
    }

    /**
    * 取输出用的表名：优先用户显式指定，其次 SDI 里的真实表名，最后退回文件名。
    *
    * @param source 源文件
    * @param config 还原配置
    * @param data   提取结果
    * @return 表名
    */
    private static String resolveTableName(File source, DataRestoreConfig config, TableData data) {
        String tableName = config.getTargetTable();
        if (tableName != null && !tableName.isBlank()) {
            return tableName;
        }
        if (data.definition != null && data.definition.name() != null && !data.definition.name().isBlank()) {
            return data.definition.name();
        }
        tableName = source.getName().replace(".ibd", "").replaceFirst("^\\d+", "");
        return tableName.isBlank() ? source.getName() : tableName;
    }

    /**
    * 用表定义 + 行数据拼出完整 SQL 脚本（native 引擎专用）。
    *
    * @param data             提取结果
    * @param schemaName       目标库名
    * @param tableName        目标表名
    * @param includeStructure 是否包含建表语句
    * @return SQL 脚本
    */
    private static String buildSqlScript(TableData data, String schemaName,
                                         String tableName, boolean includeStructure) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append(schemaPrologue(schemaName));
        if (includeStructure && data.definition != null) {
            sb.append(com.chua.ibd.support.innodb.IbdSqlWriter
                    .createTable(data.definition, schemaName, tableName)).append('\n');
        }
        for (String statement : com.chua.ibd.support.innodb.IbdSqlWriter
                .insertStatements(data.definition, data.rows, schemaName, tableName, data.columns)) {
            sb.append(statement).append('\n');
        }
        return sb.toString();
    }

    // ==================== ibd2sql 调用 ====================

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
            } else {
                log.warn("options['{}'] 指向的路径不存在: {}", OPTION_IBD2SQL_PATH, entry.getAbsolutePath());
            }
        }
        if (isIbd2SqlModuleAvailable(python)) {
            return List.of(python, "-m", "ibd2sql");
        }
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
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
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

    // ==================== ibd2sql 输出解析 ====================

    /**
    * 将 ibd2sql 输出的 SQL 解析为行数据列表。
    *
    * <p>ibd2sql 每条记录输出一行 {@code INSERT INTO `库`.`表` VALUES (...);}，
    * 因此按行扫描 VALUES 子句即可。列名取自 CREATE TABLE 的列定义，
    * 与值一一对应；列定义缺失时才退化为下标列名。</p>
    *
    * <p><b>两个必须处理的坑</b>（都是实测踩出来的）：</p>
    * <ol>
    *   <li>VALUES 子句末尾带 {@code ;}，必须先去掉再判断外层括号，否则
    *       {@code endsWith(")")} 不成立、括号剥不掉，深度恒为 1，整行会被当成<b>一个值</b>；</li>
    *   <li>列名必须真的从 DDL 里取出来，否则表头是 {@code 0,1,2,...} 这种下标，毫无可读性。</li>
    * </ol>
    *
    * @param rawSql 原始 SQL 文本
    * @return 行数据列表
    *
    * <p>包级可见，便于单测直接喂 SQL 文本校验解析结果（不需要装 ibd2sql）。</p>
    */
    List<Map<String, Object>> parseSqlToRows(String rawSql) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> columns = parseCreateTableColumns(rawSql);
        for (String line : rawSql.split("\n")) {
            int valuesIdx = indexOfIgnoreCase(line, "VALUES");
            if (valuesIdx < 0) {
                continue;
            }
            String clause = line.substring(valuesIdx + "VALUES".length()).trim();
            for (List<String> values : splitValueGroups(clause)) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < values.size(); i++) {
                    String key = i < columns.size() ? columns.get(i) : String.valueOf(i);
                    row.put(key, values.get(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /**
    * 从 CREATE TABLE 语句中提取列名。
    *
    * <p>跳过 PRIMARY KEY / KEY / INDEX / CONSTRAINT / FOREIGN KEY / CHECK 等表级约束行。</p>
    *
    * @param rawSql 原始 SQL 文本
    * @return 列名列表；解析不到返回空列表
    *
    * <p>包级可见，便于单测直接喂 SQL 文本校验解析结果（不需要装 ibd2sql）。</p>
    */
    List<String> parseCreateTableColumns(String rawSql) {
        List<String> columns = new ArrayList<>();
        int createIdx = indexOfIgnoreCase(rawSql, "CREATE TABLE");
        if (createIdx < 0) {
            return columns;
        }
        int open = rawSql.indexOf('(', createIdx);
        if (open < 0) {
            return columns;
        }
        int end = matchingParen(rawSql, open);
        if (end < 0) {
            return columns;
        }
        for (String rawLine : rawSql.substring(open + 1, end).split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || isTableLevelConstraint(line)) {
                continue;
            }
            String name = columnNameOf(line);
            if (!name.isEmpty()) {
                columns.add(name);
            }
        }
        return columns;
    }

    /**
    * 判断某行列定义是否为表级约束（不是列）。
    *
    * @param line 已 trim 的行
    * @return 是表级约束返回 true
    */
    private static boolean isTableLevelConstraint(String line) {
        String upper = line.toUpperCase(Locale.ROOT);
        for (String prefix : new String[]{"PRIMARY KEY", "UNIQUE", "KEY ", "KEY`", "INDEX ",
                "CONSTRAINT", "FOREIGN KEY", "FULLTEXT", "SPATIAL", "CHECK"}) {
            if (upper.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
    * 取列定义行里的列名（支持反引号与裸列名）。
    *
    * @param line 已 trim 的列定义行
    * @return 列名；识别不出返回空串
    */
    private static String columnNameOf(String line) {
        if (line.startsWith("`")) {
            int close = line.indexOf('`', 1);
            return close < 0 ? "" : line.substring(1, close);
        }
        int space = line.indexOf(' ');
        return space <= 0 ? "" : line.substring(0, space);
    }

    /**
    * 找与指定左括号配对的右括号位置。
    *
    * @param text 文本
    * @param open 左括号下标
    * @return 右括号下标；找不到返回 -1
    */
    private static int matchingParen(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                i = skipQuoted(text, i);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
    * 跳过一段单引号字符串（含 {@code ''} 与 {@code \'} 两种转义），返回收尾引号的下标。
    *
    * @param text  文本
    * @param start 起始引号下标
    * @return 收尾引号下标；未闭合时返回文本末尾
    */
    private static int skipQuoted(String text, int start) {
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '\'') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    return i;
                }
            }
        }
        return text.length() - 1;
    }

    /**
    * 拆分 VALUES 子句里的多个值分组（{@code (...),(...)} 形式）。
    *
    * @param clause VALUES 关键字之后的内容
    * @return 每个分组的取值列表
    */
    private List<List<String>> splitValueGroups(String clause) {
        List<List<String>> groups = new ArrayList<>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < clause.length(); i++) {
            char c = clause.charAt(i);
            if (c == '\'') {
                i = skipQuoted(clause, i);
                continue;
            }
            if (c == '(') {
                if (depth == 0) {
                    start = i + 1;
                }
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0 && start >= 0) {
                    groups.add(splitValues(clause.substring(start, i)));
                    start = -1;
                }
            }
        }
        return groups;
    }

    /**
    * 不区分大小写查找子串。
    *
    * @param text      文本
    * @param searchFor 目标子串
    * @return 下标；找不到返回 -1
    */
    private static int indexOfIgnoreCase(String text, String searchFor) {
        return text.toUpperCase(Locale.ROOT).indexOf(searchFor.toUpperCase(Locale.ROOT));
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
        return new ArrayList<>(rows.getFirst().keySet());
    }

    /**
    * 生成 SQL 脚本开头「建库 + 切库」的引导语句。
    *
    * <p>{@code DataRestoreConfig.targetSchema} 的契约是<b>建库语句</b>，所以这里必须真的建库：
    * 只发 {@code USE `x`;} 的话，目标库不存在时脚本一执行就报
    * {@code ERROR 1049 (42000): Unknown database 'x'}，用户还得手动先建库 —— 那就不是「一键」了。
    * 用 {@code IF NOT EXISTS} 保证重复执行安全；只指定字符集不指定排序规则，
    * 免得写死一个旧版本 MySQL 上不存在的 collation。</p>
    *
    * @param schemaName 目标库名，为空时返回空串
    * @return 引导语句（含结尾换行），或空串
    */
    static String schemaPrologue(String schemaName) {
        if (schemaName == null || schemaName.isBlank()) {
            return "";
        }
        return "CREATE DATABASE IF NOT EXISTS `" + schemaName + "` DEFAULT CHARACTER SET utf8mb4;\n"
                + "USE `" + schemaName + "`;\n";
    }

    /**
    * 替换 SQL 中的表名为指定名称（不改库名）。
    *
    * @param rawSql  原始 SQL
    * @param newName 新表名；为空表示保持原表名
    * @return 替换后的 SQL
    */
    String replaceTableNames(String rawSql, String newName) {
        return replaceTableNames(rawSql, null, newName);
    }

    /**
    * 替换 SQL 中的库名 / 表名。
    *
    * <p>按「只改用户显式指定的那一段」处理，因此四种组合都成立：只给 {@code targetTable}
    * 就只换表名、只给 {@code targetSchema} 就只换库名、两者都给就都换、都不给则原样返回。
    * 原 SQL 里的库名（如 ibd2sql 输出的 {@code `sakila`.`actor`}）在用户未指定时保留。</p>
    *
    * <p>同时替换 {@code CREATE TABLE} 与<b>所有</b> {@code INSERT INTO}；外键定义里的
    * {@code REFERENCES `other_table`} <b>不</b>改（那是另一张表，单表还原时不该动）。</p>
    *
    * @param rawSql     原始 SQL
    * @param schemaName 新库名；为空表示保持原库名
    * @param newName    新表名；为空表示保持原表名
    * @return 替换后的 SQL
    */
    String replaceTableNames(String rawSql, String schemaName, String newName) {
        if (rawSql == null || rawSql.isEmpty()) {
            return rawSql;
        }
        boolean schemaBlank = schemaName == null || schemaName.isBlank();
        boolean tableBlank = newName == null || newName.isBlank();
        if (schemaBlank && tableBlank) {
            return rawSql;
        }
        String result = replaceTableReferences(rawSql, CREATE_TABLE_PATTERN, schemaName, newName);
        return replaceTableReferences(result, INSERT_INTO_PATTERN, schemaName, newName);
    }

    /**
    * 按给定模式全局替换表名引用，保留语句前缀（如 {@code CREATE TABLE IF NOT EXISTS}）。
    *
    * <p>替换是<b>引号感知</b>的：落在单引号字符串字面量里的命中会被跳过。
    * 否则数据本身含 {@code INSERT INTO `x`.`y`} 这类文本时（例如某行的
    * {@code description} 列存了一段 SQL），字面量里的表名会被误改。</p>
    *
    * @param sql        待处理的 SQL
    * @param pattern    语句模式，捕获组 1 = 语句前缀，捕获组 2 = 表名引用
    * @param schemaName 新库名；为空表示保留原库名
    * @param newName    新表名；为空表示保留原表名
    * @return 替换后的 SQL
    */
    private static String replaceTableReferences(String sql, Pattern pattern,
                                                 String schemaName, String newName) {
        boolean[] literal = markStringLiterals(sql);
        Matcher matcher = pattern.matcher(sql);
        StringBuilder sb = new StringBuilder(sql.length());
        int copied = 0;
        while (matcher.find()) {
            if (literal[matcher.start()]) {
                continue;
            }
            String[] parts = splitQualifiedName(matcher.group(2));
            String schema = (schemaName == null || schemaName.isBlank()) ? parts[0] : schemaName;
            String table = (newName == null || newName.isBlank()) ? parts[1] : newName;
            sb.append(sql, copied, matcher.start());
            sb.append(matcher.group(1)).append(qualifyName(schema, table));
            copied = matcher.end();
        }
        if (copied == 0) {
            return sql;
        }
        sb.append(sql, copied, sql.length());
        return sb.toString();
    }

    /**
    * 标记 SQL 中属于单引号字符串字面量的字符位置。
    *
    * @param sql 待扫描的 SQL
    * @return 与 SQL 等长的标记数组，{@code true} 表示该下标位于字符串字面量内（含引号本身）
    */
    private static boolean[] markStringLiterals(String sql) {
        boolean[] marks = new boolean[sql.length()];
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) != '\'') {
                continue;
            }
            int end = skipQuoted(sql, i);
            for (int j = i; j <= end && j < sql.length(); j++) {
                marks[j] = true;
            }
            i = end;
        }
        return marks;
    }

    /**
    * 拆分（可能带反引号的）库名限定引用。
    *
    * @param reference {@code `db`.`tbl`} / {@code `tbl`} / 裸名
    * @return 长度为 2 的数组，[0] = 库名（无库名限定时为 {@code null}），[1] = 表名
    */
    private static String[] splitQualifiedName(String reference) {
        String cleaned = reference.trim();
        boolean inBacktick = false;
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (c == '`') {
                inBacktick = !inBacktick;
            } else if (c == '.' && !inBacktick) {
                return new String[]{
                        stripBackticks(cleaned.substring(0, i)),
                        stripBackticks(cleaned.substring(i + 1))};
            }
        }
        return new String[]{null, stripBackticks(cleaned)};
    }

    /**
    * 去掉外层反引号。
    *
    * @param value 原值
    * @return 去引号后的值
    */
    private static String stripBackticks(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '`' && trimmed.charAt(trimmed.length() - 1) == '`') {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
    * 拼装（可能带库名的）反引号限定名。
    *
    * @param schema 库名，可为空
    * @param table  表名
    * @return {@code `db`.`tbl`} 或 {@code `tbl`}
    */
    private static String qualifyName(String schema, String table) {
        return (schema == null || schema.isBlank()) ? "`" + table + "`" : "`" + schema + "`.`" + table + "`";
    }

    /**
    * 拆分单个值分组里的取值列表。
    *
    * <p>处理引号内的逗号、嵌套括号（如 {@code point(1,2)}）、
    * 以及 {@code ''} / {@code \'} 两种引号转义；字符串值会去掉外层单引号并还原转义。</p>
    *
    * @param valuesStr 分组内容（不含外层括号）
    * @return 拆分后的值列表
    *
    * <p>包级可见，便于单测直接喂 SQL 文本校验解析结果（不需要装 ibd2sql）。</p>
    */
    List<String> splitValues(String valuesStr) {
        List<String> values = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < valuesStr.length(); i++) {
            char c = valuesStr.charAt(i);
            if (c == '\'') {
                i = skipQuoted(valuesStr, i);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                values.add(unquote(valuesStr.substring(start, i).trim()));
                start = i + 1;
            }
        }
        if (start < valuesStr.length()) {
            values.add(unquote(valuesStr.substring(start).trim()));
        }
        return values;
    }

    /**
    * 去掉 SQL 字符串字面量的外层单引号并还原转义。
    *
    * <p>非字符串字面量（数字、{@code NULL}、{@code 0x...}、函数调用等）原样返回。</p>
    *
    * @param value 原始值文本
    * @return 可读值
    */
    private static String unquote(String value) {
        if (value.length() < 2 || value.charAt(0) != '\'' || value.charAt(value.length() - 1) != '\'') {
            return value;
        }
        String body = value.substring(1, value.length() - 1);
        StringBuilder sb = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\'' && i + 1 < body.length() && body.charAt(i + 1) == '\'') {
                sb.append('\'');
                i++;
            } else if (c == '\\' && i + 1 < body.length()) {
                sb.append(body.charAt(++i));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
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
