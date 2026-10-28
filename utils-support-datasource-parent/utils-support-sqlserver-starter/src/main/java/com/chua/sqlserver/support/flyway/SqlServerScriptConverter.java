package com.chua.sqlserver.support.flyway;

import com.chua.common.support.lang.datasource.flyway.DefaultScriptConverter;
import com.chua.common.support.spi.annotations.Spi;

import java.util.regex.Pattern;

/**
 * SQL Server 迁移脚本方言转换器（{@code ScriptConverter} 的 sqlserver 协议实现）。
 *
 * <p>SQL Server 此前没有 ScriptConverter 实现，落到兜底的 {@link DefaultScriptConverter}，
 * 而兜底实现只做了少量类型/函数映射，缺了 SQL Server 的硬性约束。</p>
 *
 * <p>本类补齐的差异（依据 SQL Server 官方文档；标识符定界与自增写法另经
 * H2 的 {@code MODE=MSSQLServer} 兼容模式实测确认）：</p>
 * <ul>
 *   <li><b>标识符定界</b>：SQL Server 支持方括号 {@code [name]} 与双引号，
 *       不认反引号。父类的 {@code NO_BACKTICK_PROTOCOLS} 已含本协议，
 *       由父类把反引号统一改写成双引号（SQL Server 开启
 *       {@code QUOTED_IDENTIFIER} 后双引号即定界符，默认开启）</li>
 *   <li><b>文本类型</b>：{@code TEXT}/{@code LONGTEXT}/{@code MEDIUMTEXT}/{@code TINYTEXT}
 *       在 SQL Server 里都是已废弃的 {@code TEXT}（且不支持比较、不能建索引），
 *       统一改写成 {@code VARCHAR(MAX)}</li>
 *   <li><b>数值类型</b>：{@code TINYINT}/{@code MEDIUMINT} 在 SQL Server 里不存在
 *       （{@code TINYINT} 是 {@code BIT} 的别名，不是数值），统一用 {@code INT}</li>
 *   <li><b>布尔类型</b>：{@code BOOLEAN}/{@code BOOL} → SQL Server 的 {@code BIT}</li>
 *   <li><b>二进制类型</b>：{@code BLOB}/{@code VARBINARY}/{@code BINARY} →
 *       SQL Server 的 {@code VARBINARY(MAX)}</li>
 *   <li><b>时间类型</b>：{@code DATETIME} → {@code DATETIME2}（{@code DATETIME}
 *       的精度只有毫秒且范围窄）</li>
 *   <li><b>函数</b>：{@code NOW()} → {@code GETDATE()}、{@code IFNULL(a,b)} → {@code ISNULL(a,b)}</li>
 *   <li><b>列级自增</b>：父类已改写成 {@code IDENTITY(1,1)}（SQL Server 原生支持）</li>
 *   <li><b>{@code INSERT IGNORE}</b>：SQL Server 没有「忽略冲突」的修饰写法，整条跳过
 *       （等价写法需要 {@code MERGE} 或「先查后插」，需要表结构元数据）</li>
 *   <li><b>{@code ON DUPLICATE KEY UPDATE}</b>：SQL Server 的等价写法是 {@code MERGE}，
 *       需要表结构元数据，整条跳过</li>
 * </ul>
 *
 * <p>扩展方式：扩展键必须等于协议名（{@code @Spi("sqlserver")} + 本模块
 * {@code META-INF/extensions/...ScriptConverter} 中的 {@code sqlserver=} 行），
 * 否则会与兜底默认实现同键互相遮蔽。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = SqlServerScriptConverter.PROTOCOL, order = 100)
public class SqlServerScriptConverter extends DefaultScriptConverter {

    /**
     * 本协议名（同时作为 SPI 扩展键）
     */
    public static final String PROTOCOL = "sqlserver";

    /**
     * {@code INSERT IGNORE INTO} — 整条跳过
     */
    private static final Pattern INSERT_IGNORE = Pattern.compile(
            "(?i)^\\s*INSERT\\s+IGNORE\\b");

    /**
     * {@code ON DUPLICATE KEY UPDATE} — 整条跳过
     */
    private static final Pattern ON_DUPLICATE_KEY_UPDATE = Pattern.compile(
            "(?i)\\bON\\s+DUPLICATE\\s+KEY\\s+UPDATE\\b");

    /**
     * {@code TINYINT} → {@code INT}。
     *
     * <p>SQL Server 没有 {@code TINYINT} 数值类型（{@code TINYINT} 是 {@code BIT}
     * 的同义词，存 0/1，不是 1 字节整数）；{@code MEDIUMINT} 同样不存在。
     * 统一用 4 字节 {@code INT}，避免溢出。</p>
     */
    private static final Pattern TINYINT_TYPE = Pattern.compile(
            "(?i)\\bTINYINT\\b|\\bMEDIUMINT\\b");

    /**
     * {@code BOOLEAN} / {@code BOOL} → SQL Server 的 {@code BIT}。
     */
    private static final Pattern BOOLEAN_TYPE = Pattern.compile("(?i)\\bBOOLEAN\\b|\\bBOOL\\b");

    /**
     * {@code YEAR} → {@code SMALLINT}。
     *
     * <p>SQL Server 没有 {@code YEAR} 类型。</p>
     */
    private static final Pattern YEAR_TYPE = Pattern.compile("(?i)\\bYEAR\\b");

    /**
     * {@code BLOB} / {@code VARBINARY(n)} / {@code BINARY(n)} → {@code VARBINARY(MAX)}。
     */
    private static final Pattern BINARY_TYPE = Pattern.compile(
            "(?i)\\b(?:VAR)?BINARY\\s*\\(\\s*\\d+\\s*\\)|\\bBLOB\\b");

    /**
     * {@code CREATE TABLE IF NOT EXISTS} —— 真 SQL Server **不支持**该语法。
     *
     * <p>实测 SQL Server 2022 报「关键字 'IF' 附近有语法错误」。
     * H2 的 {@code MODE=MSSQLServer} 兼容模式接受它，所以此前在兼容模式下测不出来。</p>
     *
     * <p>T-SQL 的标准等同写法是先判对象存在再建表：</p>
     * <pre>{@code
     * IF OBJECT_ID(N't', N'U') IS NULL
     *     CREATE TABLE t (...);
     * }</pre>
     *
     * <p>注意 {@code IF ... CREATE TABLE} 必须是<b>同一条语句</b>（T-SQL 的 IF 只管辖紧跟的一句），
     * 故此处不拆成两条。</p>
     */
    private static final Pattern CREATE_TABLE_IF_NOT_EXISTS = Pattern.compile(
            "(?is)^(\\s*)CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+([\"\\[]?[A-Za-z_]\\w*[\"\\]]?)");

    /**
     * {@code DROP TABLE IF EXISTS} —— 真 SQL Server 2016+ 支持（{@code DROP TABLE IF EXISTS}），
     * 但更早版本不支持，统一改写成 {@code IF OBJECT_ID(...) IS NOT NULL DROP TABLE ...}。
     */
    private static final Pattern DROP_TABLE_IF_EXISTS = Pattern.compile(
            "(?is)^(\\s*)DROP\\s+TABLE\\s+IF\\s+EXISTS\\s+([\"\\[]?[A-Za-z_]\\w*[\"\\]]?)");

    /**
     * {@code CREATE [UNIQUE] INDEX IF NOT EXISTS ix ON t (...)} —— 真 SQL Server 不支持。
     *
     * <p>实测报「关键字 'NOT' 附近有语法错误」。T-SQL 的等同写法是查 {@code sys.indexes}：</p>
     * <pre>{@code
     * IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix' AND object_id = OBJECT_ID('t'))
     *     CREATE INDEX ix ON t (...);
     * }</pre>
     */
    private static final Pattern CREATE_INDEX_IF_NOT_EXISTS = Pattern.compile(
            "(?is)^(\\s*)CREATE\\s+(UNIQUE\\s+)?INDEX\\s+IF\\s+NOT\\s+EXISTS\\s+"
                    + "([\"\\[]?[A-Za-z_]\\w*[\"\\]]?)\\s+ON\\s+([\"\\[]?[A-Za-z_]\\w*[\"\\]]?)\\s*(\\(.*\\))\\s*;?\\s*$");

    /**
     * {@code DROP INDEX IF EXISTS ix ON t} —— 真 SQL Server 不支持该写法。
     *
     * <p>SQL Server 的 DROP INDEX 语法是 {@code DROP INDEX ix ON t}，
     * 且没有 {@code IF EXISTS} 修饰（2016+ 也没有）。改用 sys.indexes 判存。</p>
     */
    private static final Pattern DROP_INDEX_IF_EXISTS = Pattern.compile(
            "(?is)^(\\s*)DROP\\s+INDEX\\s+IF\\s+EXISTS\\s+([\"\\[]?[A-Za-z_]\\w*[\"\\]]?)"
                    + "\\s+ON\\s+([\"\\[]?[A-Za-z_]\\w*[\"\\]]?)\\s*;?\\s*$");

    /**
     * 布尔字面量：{@code TRUE} / {@code FALSE} → {@code 1} / {@code 0}。
     *
     * <p>SQL Server 没有 TRUE/FALSE 关键字，BIT 列只接受 0/1。
     * 实测报「在此上下文中不允许使用名称 "TRUE"」。</p>
     *
     * <p>只在<b>不是字符串字面量</b>的位置替换 —— 用负向断言避开 {@code 'TRUE'}。</p>
     */
    private static final Pattern TRUE_LITERAL = Pattern.compile(
            "(?i)(?<!['\"\\w])TRUE(?!['\"\\w])");
    private static final Pattern FALSE_LITERAL = Pattern.compile(
            "(?i)(?<!['\"\\w])FALSE(?!['\"\\w])");

    /**
     * {@code JSON} 数据类型 → {@code NVARCHAR(MAX)}。
     *
     * <p>SQL Server 没有 {@code JSON} 类型（只有 JSON 函数），实测报
     * 「'json' 附近有语法错误。找不到数据类型 json」。</p>
     *
     * <p>lookahead 允许<b>零个或多个</b>空白：剥掉 {@code COMMENT} 后，
     * {@code JSON} 后面可能直接跟逗号（{@code question_options JSON,}），
     * 若要求 {@code \s+} 会漏掉这种。</p>
     */
    private static final Pattern JSON_TYPE = Pattern.compile(
            "(?i)(?<=\\s)JSON(?=\\s*(?:NULL|NOT\\s+NULL|DEFAULT|COMMENT|,|\\)))");

    /**
     * {@code TIMESTAMP} 列类型 → {@code DATETIME2}。
     *
     * <p><b>SQL Server 的 {@code TIMESTAMP} 是 {@code ROWVERSION} 的同义词</b>
     * —— 8 字节二进制版本号，不是时间类型，而且：</p>
     * <ul>
     *   <li><b>不能带 DEFAULT</b>（实测「不能在数据类型为 timestamp 的列上创建默认值」）</li>
     *   <li><b>一个表只能有一个</b>（实测「一个表只能有一个时间戳列」）</li>
     * </ul>
     *
     * <p>迁移脚本里的 {@code TIMESTAMP} 显然都是当时间类型用的，
     * 故一律改成 {@code DATETIME2}（SQL Server 2008+ 的标准时间类型，支持 DEFAULT）。</p>
     *
     * <p>只匹配<b>列定义位置</b>的 TIMESTAMP（后面跟 NULL / NOT NULL / DEFAULT / 逗号 / 右括号），
     * 避免误伤 {@code CURRENT_TIMESTAMP} 这类函数名。
     * lookahead 允许<b>零个或多个</b>空白：{@code create_time TIMESTAMP,} 里逗号前没有空格。</p>
     */
    private static final Pattern TIMESTAMP_TYPE = Pattern.compile(
            "(?i)\\bTIMESTAMP\\b(?=\\s*(?:\\(\\s*\\d+\\s*\\))?\\s*(?:NULL|NOT\\s+NULL|DEFAULT|,|\\)))");

    /**
     * {@code DOUBLE} / {@code DOUBLE PRECISION} → {@code FLOAT}。
     *
     * <p><b>SQL Server 没有 {@code DOUBLE} 类型</b>，双精度浮点是 {@code FLOAT}。
     * 实测报「关键字 'NOT' 附近有语法错误」—— 因为 {@code DOUBLE} 被当成列名、
     * {@code NOT} 就落在非法位置，错误信息很有迷惑性。</p>
     */
    private static final Pattern DOUBLE_TYPE = Pattern.compile(
            "(?i)\\bDOUBLE(?:\\s+PRECISION)?\\b(?=\\s*(?:\\(\\s*\\d+\\s*\\))?\\s*(?:NULL|NOT\\s+NULL|DEFAULT|,|\\)))");

    /**
     * 列内联 {@code COMMENT '...'} —— SQL Server 没有这个语法，整段删掉。
     *
     * <p>父类本应已剥离注释，但实测在「{@code JSON COMMENT '...'}」这种
     * <b>类型后面直接跟 COMMENT</b>的写法上会漏 —— 一旦漏掉，
     * {@link #JSON_TYPE} 的 lookahead 就匹配不上，{@code JSON} 原样进库，
     * 报「找不到数据类型 json」。</p>
     *
     * <p>故这里再做一遍兜底剥离（幂等：已无 COMMENT 时无操作）。</p>
     */
    private static final Pattern INLINE_COMMENT = Pattern.compile(
            "(?i)\\s+COMMENT\\s+(?:'[^']*(?:''[^']*)*'|\"[^\"]*\")");

    /**
     * 索引列的长度限定（前缀索引）：{@code (col(191))} → {@code (col)}。
     *
     * <p><b>SQL Server 不支持索引前缀长度</b>（MySQL 专属语法，实测报「'(' 附近有语法错误」）。</p>
     *
     * <p>去掉长度限定后建的是整列索引 —— 语义比 MySQL 的前缀索引<b>更严格</b>
     * （MySQL 只保证前 191 字符唯一，这里是整列唯一），不会放宽约束，
     * 因此是安全的方向。若列本身是 {@code VARCHAR(MAX)} 等无法建索引的类型，
     * 会由数据库自己报错，不会被静默吞掉。</p>
     */
    private static final Pattern INDEX_PREFIX_LENGTH = Pattern.compile(
            "(?i)([\"\\[]?[A-Za-z_]\\w*[\"\\]]?\\s*(?:ASC|DESC)?\\s*)\\(\\s*\\d+\\s*\\)");

    /**
     * 把索引列清单里的 {@code col(191)} 改写成 {@code col}。
     *
     * <p>只在 {@code CREATE ... INDEX ... ON t (...)} 的列清单上动手，
     * 避免误伤 {@code VARCHAR(191)} 这类类型声明。</p>
     *
     * @param sql 转换后的语句
     * @return 改写后的语句
     */
    private String stripIndexPrefixLength(String sql) {
        String upper = sql.toUpperCase(java.util.Locale.ROOT);
        if (!upper.contains("CREATE INDEX") && !upper.contains("CREATE UNIQUE INDEX")) {
            return sql;
        }
        // 定位 ON 之后的列清单（最后一个括号组），只在该片段内替换
        int onIdx = upper.lastIndexOf(" ON ");
        if (onIdx < 0) {
            return sql;
        }
        int open = sql.indexOf('(', onIdx);
        if (open < 0) {
            return sql;
        }
        int depth = 0;
        int close = -1;
        for (int i = open; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    close = i;
                    break;
                }
            }
        }
        if (close < 0) {
            return sql;
        }
        String head = sql.substring(0, open + 1);
        String cols = sql.substring(open + 1, close);
        String tail = sql.substring(close);
        return head + INDEX_PREFIX_LENGTH.matcher(cols).replaceAll("$1") + tail;
    }

    @Override
    public boolean supports(String protocol) {
        return PROTOCOL.equalsIgnoreCase(protocol);
    }

    @Override
    public String convert(String statement, String protocol) {
        // 先走父类：剥离表尾 MySQL 属性、行内 KEY/UNIQUE KEY、列内联 COMMENT、列级 CHARSET/COLLATE、
        // 标识符定界改写，以及 TEXT/DATETIME/IFNULL() 的基础映射
        String converted = super.convert(statement, protocol);
        if (converted == null || converted.isBlank()) {
            return converted;
        }
        // SQL Server 没有「忽略冲突」与 upsert 的 MySQL 语法，整条跳过
        if (INSERT_IGNORE.matcher(converted).find()) {
            return null;
        }
        if (ON_DUPLICATE_KEY_UPDATE.matcher(converted).find()) {
            return null;
        }
        // 类型映射
        converted = TINYINT_TYPE.matcher(converted).replaceAll("INT");
        converted = BOOLEAN_TYPE.matcher(converted).replaceAll("BIT");
        converted = YEAR_TYPE.matcher(converted).replaceAll("SMALLINT");
        converted = BINARY_TYPE.matcher(converted).replaceAll("VARBINARY(MAX)");
        // 表别名补 AS：SQL Server 把「表名 别名」解析成「schema.表」
        // （实测报 Schema "X" not found），加 AS 才是表的别名
        converted = TABLE_ALIAS.matcher(converted).replaceAll("$1 AS $2");
        // CREATE TABLE IF NOT EXISTS / DROP TABLE IF EXISTS：真 SQL Server 不支持
        // （实测 SQL Server 2022 报「关键字 'IF' 附近有语法错误」；
        //   H2 的 MODE=MSSQLServer 兼容模式接受它，故此前在兼容模式下测不出来）
        converted = rewriteCreateTableIfNotExists(converted);
        // 前缀索引（col(191)）是 MySQL 专属语法，SQL Server 不认 → 去掉长度限定建整列索引
        converted = stripIndexPrefixLength(converted);
        // CREATE/DROP INDEX IF NOT EXISTS：同样不是 T-SQL（实测报「关键字 'NOT' 附近有语法错误」）
        converted = rewriteCreateIndexIfNotExists(converted);
        converted = rewriteDropIndexIfExists(converted);
        // JSON 类型不存在 → NVARCHAR(MAX)
        // 必须先剥 COMMENT：否则「JSON COMMENT '...'」的 lookahead 匹配不上，
        // JSON 会原样进库（实测报「找不到数据类型 json」）
        converted = INLINE_COMMENT.matcher(converted).replaceAll("");
        converted = JSON_TYPE.matcher(converted).replaceAll("NVARCHAR(MAX)");
        // TIMESTAMP 在 SQL Server 里是 ROWVERSION（二进制、不能带 DEFAULT、全表只能一个）
        // → DATETIME2。必须在布尔字面量替换之前做（顺序无关，但这里保持类型映射集中）
        converted = TIMESTAMP_TYPE.matcher(converted).replaceAll("DATETIME2");
        // DOUBLE 不是 SQL Server 类型 → FLOAT
        // （实测报「关键字 'NOT' 附近有语法错误」，因为 DOUBLE 被当列名、NOT 落到非法位置）
        converted = DOUBLE_TYPE.matcher(converted).replaceAll("FLOAT");
        // 布尔字面量 TRUE/FALSE → 1/0（SQL Server 的 BIT 只接受 0/1）
        converted = TRUE_LITERAL.matcher(converted).replaceAll("1");
        converted = FALSE_LITERAL.matcher(converted).replaceAll("0");
        return converted;
    }

    /**
     * {@code CREATE TABLE IF NOT EXISTS t (...)} → 用 {@code EXEC} 包一层动态 DDL。
     *
     * <p><b>为什么不能直接 {@code IF OBJECT_ID(...) IS NULL CREATE TABLE ...}</b>：
     * T-SQL 的 {@code IF} 只管辖紧跟的<b>单条语句</b>，而 {@code CREATE TABLE} 里的
     * {@code NOT NULL} 会被解析器当成 IF 语句的延续，实测报
     * 「关键字 'NOT' 附近有语法错误」/「关键字 'NULL' 附近有语法错误」。
     * 把整条 DDL 塞进 {@code EXEC(N'...')} 里，IF 就只管辖这一条 EXEC，不再有二义。</p>
     *
     * @param sql 转换后的语句
     * @return 改写后的语句；不是该形态时原样返回
     */
    private String rewriteCreateTableIfNotExists(String sql) {
        java.util.regex.Matcher m = CREATE_TABLE_IF_NOT_EXISTS.matcher(sql);
        if (!m.find()) {
            return sql;
        }
        String indent = m.group(1);
        String table = m.group(2);
        // 去掉 IF NOT EXISTS，保留 CREATE TABLE <name> 及后续表体
        String rest = sql.substring(m.end());
        String createStmt = "CREATE TABLE " + table + rest;
        String bare = stripQuotes(table);
        // 整条 DDL 塞进 EXEC：IF 只管辖这一条 EXEC，不与表体里的 NOT NULL 冲突。
        // EXEC 内的单引号要翻倍转义（T-SQL 字符串字面量规则）。
        String escaped = createStmt.replace("'", "''");
        return indent + "IF OBJECT_ID(N'" + bare + "', N'U') IS NULL EXEC(N'" + escaped + "')";
    }

    /**
     * {@code CREATE [UNIQUE] INDEX IF NOT EXISTS ix ON t (...)}
     * → {@code IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name='ix' AND object_id=OBJECT_ID('t')) CREATE INDEX ix
     * ON t (...)}。
     *
     * @param sql 转换后的语句
     * @return 改写后的语句；不是该形态时原样返回
     */
    private String rewriteCreateIndexIfNotExists(String sql) {
        java.util.regex.Matcher m = CREATE_INDEX_IF_NOT_EXISTS.matcher(sql);
        if (!m.find()) {
            return sql;
        }
        String indent = m.group(1);
        String unique = m.group(2) == null ? "" : "UNIQUE ";
        String index = m.group(3);
        String table = m.group(4);
        String columns = m.group(5);
        String bareIndex = stripQuotes(index);
        String bareTable = stripQuotes(table);
        String ddl = "CREATE " + unique + "INDEX " + index + " ON " + table + " " + columns;
        // 与建表同理：整条 DDL 塞进 EXEC，IF 只管辖这一条
        return indent + "IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'" + bareIndex
                + "' AND object_id = OBJECT_ID(N'" + bareTable + "')) EXEC(N'"
                + ddl.replace("'", "''") + "')";
    }

    /**
     * {@code DROP INDEX IF EXISTS ix ON t}
     * → {@code IF EXISTS (SELECT 1 FROM sys.indexes WHERE name='ix' AND object_id=OBJECT_ID('t')) DROP INDEX ix ON t}。
     *
     * @param sql 转换后的语句
     * @return 改写后的语句；不是该形态时原样返回
     */
    private String rewriteDropIndexIfExists(String sql) {
        java.util.regex.Matcher m = DROP_INDEX_IF_EXISTS.matcher(sql);
        if (!m.find()) {
            return sql;
        }
        String indent = m.group(1);
        String index = m.group(2);
        String table = m.group(3);
        String bareIndex = stripQuotes(index);
        String bareTable = stripQuotes(table);
        return indent + "IF EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'" + bareIndex
                + "' AND object_id = OBJECT_ID(N'" + bareTable + "')) "
                + "DROP INDEX " + index + " ON " + table;
    }

    /**
     * 去掉标识符的定界符（方括号或双引号）。
     *
     * @param name 标识符
     * @return 裸名字
     */
    private String stripQuotes(String name) {
        return name.replace("[", "").replace("]", "").replace("\"", "");
    }

    /**
     * {@code FROM 表名 别名} 里的表别名（补 AS）
     *
     * <p>捕获组 1=FROM/JOIN 与空白，2=别名。
     * 不补 AS 的话，SQL Server 会把别名当成 schema 名
     * （实测报 {@code Schema "X" not found}）。</p>
     *
     * <p>别名排除 SQL 关键字：{@code FROM t WHERE ...} 里的 WHERE 是关键字、
     * 不是别名，误加 AS 会变成 {@code FROM t AS WHERE}。</p>
     */
    private static final Pattern TABLE_ALIAS = Pattern.compile(
            "(?i)(\\b(?:FROM|JOIN)\\s+[\"`]?[A-Za-z_]\\w*[\"`]?\\s+)"
                    + "(?!AS\\b|WHERE\\b|SELECT\\b|AND\\b|OR\\b|ON\\b|JOIN\\b|LEFT\\b|RIGHT\\b|INNER\\b"
                    + "|OUTER\\b|CROSS\\b|GROUP\\b|ORDER\\b|HAVING\\b|LIMIT\\b|UNION\\b|VALUES\\b"
                    + "|SET\\b|USING\\b|WHEN\\b|THEN\\b|ELSE\\b|END\\b|CASE\\b)([A-Za-z_]\\w*)");

}
