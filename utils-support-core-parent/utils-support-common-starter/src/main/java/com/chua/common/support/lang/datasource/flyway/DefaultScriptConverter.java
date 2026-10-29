package com.chua.common.support.lang.datasource.flyway;

import com.chua.common.support.spi.annotations.Spi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.chua.common.support.spi.annotations.SpiDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 默认脚本方言转换器（兜底实现，支持全部协议）。
 *
 * <p>提供 MySQL 风格脚本 → 各目标数据库的通用转换规则。
 * 目标库为 MySQL/MariaDB/TiDB/OceanBase 时原样返回（脚本本身按 MySQL 风格编写）；
 * H2/PostgreSQL/Oracle/SQL Server/SQLite 时剥离 MySQL 专属语法，
 * 使同一套脚本多库可运行。</p>
 *
 * <h3>转换规则</h3>
 * <ol>
 *   <li>整语句跳过：{@code SET NAMES} / {@code SET FOREIGN_KEY_CHECKS} /
 *       MySQL 动态 SQL 段（{@code SET @var}、{@code PREPARE}、{@code EXECUTE}、{@code DEALLOCATE}）</li>
 *   <li>CREATE TABLE 尾部属性剥离：{@code ENGINE=}、{@code AUTO_INCREMENT=}、
 *       {@code CHARACTER SET=/CHARSET=}、{@code COLLATE=}、{@code ROW_FORMAT=}、表级 {@code COMMENT='...'}（MySQL 专属）</li>
 *   <li>行内索引声明剥离：{@code KEY idx (...)} / {@code INDEX idx (...)} / {@code UNIQUE KEY uk (...)} /
 *       带 {@code USING BTREE}/{@code USING HASH} 及尾随 {@code COMMENT} 的变体（SHOW CREATE TABLE 导出风格）；
 *       独立的 {@code CREATE INDEX} 语句保留</li>
 *   <li>列内联 {@code COMMENT 'xxx'} 剥离（PG/Oracle/H2 不支持内联注释；独立 COMMENT ON 语句可替代，
 *       不影响表结构正确性）；剥离时保留列分隔逗号与前面的类型/默认值/约束</li>
 *   <li>列级 {@code CHARACTER SET}/{@code COLLATE} 子句剥离（SHOW CREATE TABLE 导出风格：
 *       {@code varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci}）</li>
 *   <li>{@code PRIMARY KEY (...) USING BTREE} 的 USING 子句剥离</li>
 *   <li>列定义中的 {@code ON UPDATE CURRENT_TIMESTAMP} 剥离（MySQL 专属）</li>
 *   <li>类型映射：MEDIUMTEXT/LONGTEXT → CLOB/TEXT/VARCHAR(MAX)；
 *       DATETIME → TIMESTAMP（PG/Oracle）/DATETIME2（SQL Server）；JSON → 目标库文本类型</li>
 *   <li>函数映射：NOW() → CURRENT_TIMESTAMP / SYSDATE；IFNULL → COALESCE（PG）/NVL（Oracle）/ISNULL（SQL Server）</li>
 * </ol>
 *
 * <p><b>前提</b>：CREATE TABLE 按多行对齐格式书写（每列一行，索引各占一行），
 * 与本项目 {@code db/init} 脚本及 {@code SHOW CREATE TABLE} 导出风格一致。
 * 单行紧凑写法的 CREATE TABLE 不在转换范围内。</p>
 *
 * <h3>扩展方式</h3>
 * <p>各数据库专用增强（如自增列 → 序列/IDENTITY 改写、反引号 → 双引号）
 * 可继承本类并注册更高优先级 SPI：</p>
 * <pre>{@code
 * @Spi(value = "postgresql", order = 100)
 * public class MyPgConverter extends DefaultScriptConverter {
 *     public boolean supports(String protocol) { return "postgresql".equals(protocol); }
 * }
 * // META-INF/extensions/com.chua.common.support.lang.datasource.flyway.ScriptConverter
 * // postgresql=com.example.MyPgConverter
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@SpiDefault
@Spi(value = ScriptConverter.SPI_NAME, order = 0)
public class DefaultScriptConverter implements ScriptConverter {

    /**
     * MySQL 系协议：脚本按 MySQL 风格编写，原样执行
     */
    private static final List<String> MYSQL_PROTOCOLS =
            List.of("mysql", "mariadb", "tidb", "oceanbase");

    /** 行内索引声明行（CREATE TABLE 表体内整行）：
     *  {@code KEY idx_x (col)} / {@code UNIQUE KEY uk_x (a,b)} / {@code INDEX idx_x (col) USING BTREE}
     *  / 尾随 {@code COMMENT 'xxx'}（SHOW CREATE TABLE 导出风格）。USING 子句可重复，COMMENT 段可选。
     *  <p>捕获组 1=是否 UNIQUE，2=索引名，3=列清单（可能带前缀长度 {@code col(255)}）。</p> */
    private static final Pattern INLINE_KEY_LINE = Pattern.compile(
            "^(?:\\s*,)?\\s*(UNIQUE\\s+)?(?:KEY|INDEX)\\s+(\\S+)\\s*\\((.*)\\)(?:\\s*USING\\s+\\S+)*"
                    + "(?:\\s+COMMENT\\s+'(?:[^']|'')*')?\\s*,?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /**
     * 不接受反引号作标识符定界的方言。
     *
     * <p>本仓脚本统一用 MySQL 风格的反引号（{@code `ms_monitor`}）写标识符。
     * SQLite / H2 两种定界都接受，而 PostgreSQL / Derby / Oracle / SQL Server 只认双引号
     * （实测 PostgreSQL 报 {@code ERROR: syntax error at or near "`"}，
     * Derby 报 {@code Encountered: "`" (96)}）。</p>
     */
    private static final List<String> NO_BACKTICK_PROTOCOLS =
            List.of("postgresql", "derby", "oracle", "sqlserver");

    /**
     * 标识符大小写折叠成大写的方言（不加定界符的标识符会被折叠成大写）。
     *
     * <p>Oracle 与 SQL Server 都是这样：{@code SELECT * FROM edu_idiom} 里的
     * {@code edu_idiom} 实际是 {@code EDU_IDIOM}。</p>
     */
    private static final List<String> UPPERCASE_FOLD_PROTOCOLS =
            List.of("oracle", "sqlserver");

    /**
     * 标识符大小写折叠成小写的方言（不加定界符的标识符会被折叠成小写）。
     *
     * <p>PostgreSQL 是这样：{@code SELECT * FROM edu_idiom} 里的
     * {@code edu_idiom} 实际是 {@code edu_idiom}。</p>
     */
    private static final List<String> LOWERCASE_FOLD_PROTOCOLS =
            List.of("postgresql");

    /**
     * 把双引号标识符的大小写折叠成目标方言的默认折叠规则。
     *
     * <p><b>要解决的问题</b>：本仓脚本的标识符定界不统一 ——
     * 同一个表名，建表语句里写 {@code `edu_idiom`}（加定界符）、
     * 插入语句里写 {@code edu_idiom}（不加定界符）。反引号改写成双引号之后就变成
     * 「一处加引号、一处不加」：加引号的保持原样大小写、不加引号的折叠成大写，
     * 两者对不上。</p>
     *
     * <p>实测报错：</p>
     * <ul>
     *   <li>Oracle：{@code Table "edu_idiom" not found (candidates are: "EDU_IDIOM")}</li>
     *   <li>Oracle：{@code Column "ms_monitor_alarm_policy_name" not found}</li>
     *   <li>Oracle：{@code Column "GRADE" not found}
     *       （{@code grade} 是保留字，脚本里有的地方加引号有的地方不加）</li>
     * </ul>
     *
     * <p><b>做法</b>：把加引号的标识符也折叠成该方言对裸标识符的折叠规则 ——
     * Oracle / SQL Server 折成大写、PostgreSQL 折成小写。
     * 这样加引号与不加引号的写法落到同一个标识符上，口径自然统一，
     * 不需要逐个判断「这是不是标识符位置」（那种启发式判断误伤面大）。</p>
     *
     * @param sql      已完成反引号改写的 SQL
     * @param protocol 目标方言
     * @return 折叠后的 SQL
     */
    private String foldQuotedIdentifierCase(String sql, String protocol) {
        String p = protocol == null ? "" : protocol.toLowerCase(Locale.ROOT);
        boolean upper = UPPERCASE_FOLD_PROTOCOLS.contains(p);
        boolean lower = LOWERCASE_FOLD_PROTOCOLS.contains(p);
        if (!upper && !lower) {
            return sql;
        }
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'') {
                int end = skipQuoted(sql, i);
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == '"') {
                int end = sql.indexOf('"', i + 1);
                if (end < 0) {
                    out.append(sql, i, n);
                    break;
                }
                String name = sql.substring(i + 1, end);
                // 只折叠「像标识符」的内容：不含空格与标点
                if (name.matches("[A-Za-z_]\\w*")) {
                    out.append('"')
                            .append(upper ? name.toUpperCase(Locale.ROOT) : name.toLowerCase(Locale.ROOT))
                            .append('"');
                } else {
                    out.append(sql, i, end + 1);
                }
                i = end + 1;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * 把反引号标识符改写成双引号。
     *
     * <p>只改写标识符位置的反引号（成对出现），字符串字面量内部的反引号保持原样
     * —— 中文种子数据里可能出现反引号，那属于数据内容而非标识符。</p>
     *
     * @param sql SQL
     * @return 改写后的 SQL
     */
    private String rewriteBackticks(String sql) {
        if (sql == null || sql.indexOf('`') < 0) {
            return sql;
        }
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                int end = skipQuoted(sql, i);
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == '`') {
                out.append('"');
                i++;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
    /** 前缀索引的列长度限定：{@code col(255)}（MySQL 专属，其它方言不支持前缀索引） */
    private static final Pattern INDEX_COLUMN_PREFIX_LENGTH = Pattern.compile(
            "\\(\\s*\\d+\\s*\\)");
    /** PRIMARY KEY 行带 USING BTREE（SHOW CREATE TABLE 导出风格：{@code PRIMARY KEY (`id`) USING BTREE}）。
     *  仅剥离 USING 子句，保留 PRIMARY KEY 声明本身 */
    private static final Pattern PK_USING = Pattern.compile(
            "(?i)\\s+USING\\s+\\S+\\s*,?\\s*$");
    /** 列级 CHARACTER SET / COLLATE 子句（SHOW CREATE TABLE 导出风格：
     *  {@code varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL}），
     *  仅剥离子句本身，保留其后的 NULL/DEFAULT 等 */
    private static final Pattern COLUMN_CHARSET_COLLATE = Pattern.compile(
            "(?i)\\s*(?:CHARACTER\\s+SET\\s*=?\\s*\\w+|COLLATE\\s*=?\\s*\\w+)");
    /** 列内联注释（{@code TINYINT(1) DEFAULT 1 COMMENT 'x'} 中尾随的 COMMENT 段）。
     *  只删除尾随的 {@code COMMENT 'xxx'} 文本本身，保留其前面所有内容（类型、DEFAULT、NOT NULL 等）
     *  以及列定义尾部的逗号（列分隔符，由捕获组 $1 保留，避免误删导致后续列粘连）。
     *  行尾锚定 + 可选尾随逗号；避免误伤 {@code COMMENT ON} 独立语句
     *  与字符串内出现的 "comment" 字样（{@code [^']} 不允许单引号嵌套） */
    private static final Pattern COLUMN_COMMENT = Pattern.compile(
            "(?i)\\s+COMMENT\\s+'(?:[^']|'')*'(,?)\\s*$");
    /**
     * 表级尾部 MySQL 专属属性（CREATE TABLE ... ) 之后；等号两侧允许空格）
     */
    private static final Pattern TAIL_ENGINE = Pattern.compile(
            "\\s*ENGINE\\s*=\\s*\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAIL_AUTO_INCREMENT = Pattern.compile(
            "\\s*AUTO_INCREMENT\\s*=\\s*\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAIL_CHARACTER_SET = Pattern.compile(
            "\\s*(?:CHARACTER\\s+SET\\s*=\\s*\\S+|CHARSET\\s*=\\s*\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAIL_COLLATE = Pattern.compile(
            "\\s*COLLATE\\s*=\\s*\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAIL_ROW_FORMAT = Pattern.compile(
            "\\s*ROW_FORMAT\\s*=\\s*\\S+", Pattern.CASE_INSENSITIVE);
    /**
     * 表级尾部 MySQL 专属属性（CREATE TABLE ... ) 之后；等号两侧允许空格）。
     *
     * <p><b>注意</b>：等号前是 {@code \s*=?} 而非 {@code \s+=?}。后者里的 {@code \s+}
     * 要求「至少一个空白」出现在等号前，而 {@code \s*} 已经把空白吃掉了 —— 于是该正则在
     * {@code COMMENT='x'} 这种写法上<b>永远匹配不上</b>，表级注释会原样送进目标库造成
     * 语法错（实测 H2 报 {@code ) COMMENT='...'} 语法错）。
     * 本条是「表尾属性区」专用；列内联注释由 {@link #COLUMN_COMMENT} 负责。</p>
     */
    private static final Pattern TAIL_COMMENT = Pattern.compile(
            "\\s*COMMENT\\s*=?\\s*'(?:[^']|'')*'", Pattern.CASE_INSENSITIVE);

    /**
     * 列级自增声明（MySQL 常见写法）：{@code `id` BIGINT NOT NULL AUTO_INCREMENT}。
     *
     * <p>与表尾的 {@code AUTO_INCREMENT=} 是两回事：表尾那个只是起始值（可安全剥离），
     * 列级这个声明了「本列自动生成」。非 MySQL 方言必须改写成各自的等价写法
     * （H2/PG/Oracle 用 {@code GENERATED BY DEFAULT AS IDENTITY}、
     * SQL Server 用 {@code IDENTITY(1,1)}），否则该列退化成普通列、主键不再自动赋值。</p>
     *
     * <p>类型与 {@code AUTO_INCREMENT} 之间允许夹 {@code PRIMARY KEY}：
     * {@code `id` BIGINT PRIMARY KEY AUTO_INCREMENT} 是 MySQL 极常见的写法，
     * 不放行这一段会漏判（该列的自增语义与主键声明都要保留）。</p>
     *
     * <p>捕获组 1=列名（含反引号/双引号），2=该列的类型与约束部分（含 PRIMARY KEY）。</p>
     */
    private static final Pattern COLUMN_AUTO_INCREMENT = Pattern.compile(
            "(?i)([`\"]?[A-Za-z_]\\w*[`\"]?)\\s+((?:BIGINT|INT|INTEGER|SMALLINT|TINYINT|MEDIUMINT)"
                    + "(?:\\s*\\(\\s*\\d+\\s*\\))?\\s*(?:UNSIGNED\\s+)?(?:NOT\\s+NULL\\s+)?"
                    + "(?:PRIMARY\\s+KEY\\s+)?)AUTO_INCREMENT\\b");

    /**
     * 列级自增声明（不指定类型前缀的裸写法）：{@code `id` AUTO_INCREMENT}。
     * <p>MySQL 允许省略类型（默认 INT），剥离后须补成 {@code INT} 以免该列无类型。</p>
     * <p>列名部分排除 SQL 关键字：否则 {@code BIGINT NOT NULL AUTO_INCREMENT} 这类
     * 「类型在前、自增在后」的写法里，{@code NULL} 会被当成列名匹配上（{@code NULL AUTO_INCREMENT}），
     * 产出 {@code BIGINT NOT NULL INT} 这种坏列定义。</p>
     */
    private static final Pattern COLUMN_AUTO_INCREMENT_BARE = Pattern.compile(
            "(?i)([`\"]?(?!NULL\\b|NOT\\b|NULL_|PRIMARY\\b|UNIQUE\\b|KEY\\b|DEFAULT\\b|" +
                    "CONSTRAINT\\b)[A-Za-z_]\\w*[`\"]?)"
                    + "\\s+AUTO_INCREMENT\\b");

    /**
     * 整型列的显示宽度：{@code TINYINT(1)} / {@code INT(11)} / {@code BIGINT(20)}。
     *
     * <p>MySQL 允许显式宽度，H2 2.x 严格模式与部分方言直接报语法错误（实测 H2 2.4.240 报 42001），
     * 且宽度本身对存储无影响，故统一剥离。</p>
     */
    private static final Pattern INTEGER_DISPLAY_WIDTH = Pattern.compile(
            "(?i)\\b(TINYINT|SMALLINT|MEDIUMINT|INT|INTEGER|BIGINT)\\s*\\(\\s*\\d+\\s*\\)");    /**
     * 转换告警出口：前缀索引这类「目标方言无等价物」的降级需要留痕，
     * 否则表现为「约束凭空消失」，排查时无从下手。
     */
    private static final Logger log = LoggerFactory.getLogger(DefaultScriptConverter.class);
    /**
     * 批量方言转换：逐条转换，并在建表语句之后补上由行内索引改写生成的 CREATE INDEX。
     *
     * <p>覆写本方法而不是 {@link #convert(String, String)}：单条建表语句会展开成
     * 「建表 + N 条 CREATE INDEX」多条语句，超出了 {@code convert} 的「一进一出」契约。
     * 两个调用方（{@code DataSourceFlyway} 与 {@code DefaultFlyway}）本来就统一走
     * {@code convertAll}，因此覆写它对两者都生效。</p>
     *
     * @param statements 拆分后的语句列表，不允许为 null
     * @param protocol   目标数据库协议名
     * @return 转换后的可执行语句列表
     */
    @Override
    public List<String> convertAll(List<String> statements, String protocol) {
        if (statements == null || statements.isEmpty() || protocol == null) {
            return List.of();
        }
        // MySQL 系：脚本按 MySQL 风格编写，原样执行（不生成任何附加语句）
        if (MYSQL_PROTOCOLS.contains(protocol.toLowerCase(Locale.ROOT))) {
            return statements;
        }
        List<String> result = new ArrayList<>();
        for (String statement : statements) {
            if (statement == null || statement.isBlank()) {
                continue;
            }
            // 先剥块注释再判断语句类型：运行期的 splitStatements 只跳过 "--" 行注释，
            // 块注释会留在语句开头 —— 不先剥掉的话，「/* ... */ CREATE TABLE ...」这种
            // 写法既认不出是建表语句，块注释正文也会被当成 SQL 行逐行处理。
            String cleaned = stripBlockComments(statement);
            // 反引号标识符改写成双引号：Derby / Oracle / SQL Server 不接受反引号
            // （实测 Derby 报 Encountered: "`" (96)），H2 / PG / SQLite 两种都接受
            if (NO_BACKTICK_PROTOCOLS.contains(protocol.toLowerCase(Locale.ROOT))) {
                cleaned = rewriteBackticks(cleaned);
                // 标识符大小写口径统一：加引号的标识符折叠成该方言对裸标识符的折叠规则
                cleaned = foldQuotedIdentifierCase(cleaned, protocol);
            }
            String upper = cleaned.trim().toUpperCase(Locale.ROOT);
            if (upper.startsWith("CREATE TABLE") || upper.startsWith("CREATE UNIQUE TABLE")) {
                // 建表：剥离行内索引的同时收集成独立的 CREATE INDEX
                List<String> indexes = new ArrayList<>();
                String tableSql = convertCreateTable(cleaned, indexes, protocol.toLowerCase(Locale.ROOT));
                // 交回 convert()（虚方法）走各方言子类的规则：
                // H2 的 LONGTEXT→CLOB / DATETIME→TIMESTAMP / INSERT IGNORE→INSERT 等都在那里。
                // 若这里直接调 applyTypeAndFunctionMapping，子类规则会被整体绕过。
                String converted = convert(tableSql, protocol);
                if (converted != null && !converted.isBlank()) {
                    // 生成的 CREATE INDEX 也要改写标识符定界（表名/列名来自原脚本，带反引号）
                    if (NO_BACKTICK_PROTOCOLS.contains(protocol.toLowerCase(Locale.ROOT))) {
                        converted = rewriteBackticks(converted);
                    }
                    result.add(converted);
                }
                for (String indexSql : indexes) {
                    result.add(NO_BACKTICK_PROTOCOLS.contains(protocol.toLowerCase(Locale.ROOT))
                            ? rewriteBackticks(indexSql) : indexSql);
                }
                continue;
            }
            String converted = convert(cleaned, protocol);
            if (converted != null && !converted.isBlank()) {
                // MySQL 风格的反斜杠转义（\' \" \\）改写为标准 SQL 的写法：
                //   - \'  -> ''（标准 SQL 用成对单引号表示内嵌单引号）
                //   - \"  -> ""（同理）
                //   - \\  -> \（标准 SQL 无反斜杠转义）
                // 其它方言（H2 / PG / Oracle / SQL Server）不认反斜杠转义，
                // 原样送进去会直接报语法错。实测 eduai 的 16MB 中文种子数据里
                // 有 88 处 \'（诗词里引用词句），其中 3 条 UPDATE 因此整条失败。
                converted = rewriteBackslashEscapes(converted);
                // 前缀索引（col(191)）是 MySQL 专属语法，其它方言不认：
                //   整列唯一索引语义不同（MySQL 是「前 191 字符唯一」），
                //   且部分方言直接报语法错（实测 H2 报 expected "ASC, DESC, NULLS, ,, )"）。
                // 非 MySQL 方言下这条索引无法等价表达，直接丢弃并记日志，
                // 不静默变成「没有索引」。
                String prefixIndex = dropPrefixIndexForNonMysql(converted, protocol);
                if (prefixIndex != null) {
                    converted = prefixIndex;
                }
                // MySQL 多表删除（DELETE t1 FROM ... JOIN ...）其它方言没有等价语法，改写成子查询
                String rewritten = rewriteMultiTableDelete(converted);
                if (rewritten != null) {
                    converted = rewritten;
                }
                // 「保留每组最小值」的删除：SQL Server 不接受对正在被修改的表做聚合的子查询
                String keepMin = rewriteKeepMinPerGroupDelete(converted);
                if (keepMin != null) {
                    converted = keepMin;
                }
                // 多列 ALTER 在 H2 / PostgreSQL 上不合法（一条 ADD COLUMN 一个语句），
                // MySQL 允许一次加多列。这里按列拆成多条单列 ALTER。
                List<String> splitAlters = splitMultiColumnAlter(converted);
                if (splitAlters.isEmpty()) {
                    result.add(converted);
                } else {
                    // 拆出来的每条单列 ALTER 再按方言处理：
                    // MySQL 系原样保留；其它方言丢弃（列已由建表语句提供，重复 ADD COLUMN 报 Duplicate column）
                    for (String alter : splitAlters) {
                        String guarded = rewriteAddColumnAlter(alter, protocol);
                        if (guarded == null) {
                            result.add(alter);
                        } else if (!guarded.isEmpty()) {
                            result.add(guarded);
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * 把 MySQL 风格的反斜杠转义改写成标准 SQL 写法。
     *
     * <p>MySQL 允许在字符串里用反斜杠转义（{@code \'} 表示内嵌单引号、
     * {@code \"} 表示内嵌双引号、{@code \\} 表示字面反斜杠）；
     * 标准 SQL 只有「成对引号」这一种转义（{@code ''} / {@code ""}），
     * 其它方言（H2 / PostgreSQL / Oracle / SQL Server）遇到反斜杠转义直接报语法错。
     * 实测：{@code INSERT INTO t VALUES (1, 'a\'b')} 在 H2 上报
     * {@code Syntax error ... 'a\'b[*]'}，而 {@code 'a''b'} 正常。</p>
     *
     * <p>只改写字符串字面量内部的反斜杠序列，字符串外的（例如路径里的反斜杠）
     * 保持原样。</p>
     *
     * @param sql SQL
     * @return 改写后的 SQL
     */
    private String rewriteBackslashEscapes(String sql) {
        if (sql == null || sql.indexOf('\\') < 0) {
            return sql;
        }
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                int end = skipQuoted(sql, i);
                out.append(rewriteEscapesInLiteral(sql.substring(i, end)));
                i = end;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * 改写一个字符串字面量内部的反斜杠转义。
     *
     * @param literal 含首尾引号的字面量
     * @return 改写后的字面量
     */
    private String rewriteEscapesInLiteral(String literal) {
        if (literal.indexOf('\\') < 0) {
            return literal;
        }
        StringBuilder sb = new StringBuilder(literal.length());
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (c != '\\' || i + 1 >= literal.length()) {
                sb.append(c);
                continue;
            }
            char next = literal.charAt(i + 1);
            switch (next) {
                case '\'' -> sb.append("''");
                case '"' -> sb.append("\"\"");
                case '\\' -> sb.append('\\');
                // 其它转义（\n \t 等）在标准 SQL 里没有对应写法，
                // 原样保留（去掉反斜杠会让语义悄悄变化，保留至少是显式的）
                default -> sb.append(c).append(next);
            }
            i++;
        }
        return sb.toString();
    }
    /**
     * 处理「给已存在的表补列」的 ALTER。
     *
     * <p>本仓的建表脚本普遍是「建表语句里已含该列 + 末尾再补一条 ALTER ADD COLUMN」
     * 的写法（后者是给存量老库补列用的）。在 MySQL 上重复执行报 1060（重复列），
     * 由 continueOnError 逐语句忽略；但在 H2 / PostgreSQL 上这条 ALTER 是<b>硬失败</b>
     * （Duplicate column name），会把整份脚本判为失败、每次启动重跑。
     * 实测 datasource-hub 的 7 条存量补列语句在 H2 上全部报 Duplicate column。</p>
     *
     * <p>处理方式按目标方言分两种：</p>
     * <ul>
     *   <li><b>MySQL 系</b>：原样保留 —— 它们本来就是给 MySQL 存量库用的补列语句，
     *       重复执行报 1060 由 continueOnError 吞掉，与既有行为一致；</li>
     *   <li><b>其它方言</b>：<b>整条丢弃</b>。这些列已经写在建表语句里，新库一次建全；
     *       至于「列本该由建表语句提供」的旧库，本就在本模块的迁移覆盖范围之外
     *       （旧库补列是 MySQL 专属的历史包袱）。丢弃比改写成动态 DDL 更好：
     *       动态 DDL（SET @var + PREPARE）本身也是 MySQL 语法，H2 / PG 同样不认
     *       （实测 H2 报 {@code SET @ddl = (SELECT [*]IF(...)} 语法错）。</li>
     * </ul>
     *
     * @param sql      转换后的单条语句
     * @param protocol 目标方言协议名
     * @return 改写后的语句；该方言下应丢弃时返回空串（调用方跳过）；不是补列 ALTER 时返回 null
     */
    private String rewriteAddColumnAlter(String sql, String protocol) {
        String trimmed = sql.trim();
        // 标识符定界要同时认反引号与双引号：rewriteBackticks 在本方法之前执行，
        // 反引号此时已变成双引号，只认反引号会匹配不上
        java.util.regex.Matcher m = Pattern.compile(
                "(?is)^ALTER\\s+TABLE\\s+[`\"]?([A-Za-z_]\\w*)[`\"]?\\s+ADD\\s+COLUMN\\s+[`\"]?([A-Za-z_]\\w*)[`\"]" +
                        "?\\s+(.+)$")
                .matcher(trimmed);
        if (!m.find()) {
            return null;
        }
        if (!MYSQL_PROTOCOLS.contains(protocol == null ? "" : protocol.toLowerCase(Locale.ROOT))) {
            // 其它方言：列已在建表语句里，这条存量补列语句直接丢弃
            log.info("[ui] 表 {} 的存量补列语句（列 {}）在方言 {} 下丢弃："
                    + "该列已由建表语句提供，重复 ADD COLUMN 会报 Duplicate column",
                    m.group(1), m.group(2), protocol);
            return "";
        }
        String table = m.group(1);
        String column = m.group(2);
        String definition = m.group(3).trim();
        // 去掉列定义尾部的逗号（拆列后遗留）
        definition = definition.replaceAll(",\\s*$", "").trim();
        return "SET @ddl = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "' AND COLUMN_NAME = '"
                + column + "'), 'SELECT 1', 'ALTER TABLE " + table + " ADD COLUMN " + column + " "
                + definition.replace("'", "''") + "'))); "
                + "PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;";
    }
    /**
     * 找到与给定左括号配平的右括号位置。
     *
     * <p>字符串字面量内的括号不参与配平。</p>
     *
     * @param sql  SQL 文本
     * @param open 左括号位置
     * @return 右括号位置；找不到时返回 -1
     */
    private int matchParen(String sql, int open) {
        int depth = 0;
        for (int i = open; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                int end = skipQuoted(sql, i);
                if (end <= i) {
                    return -1;
                }
                i = end - 1;
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
     * 「删除时引用自身」的子查询 → 加一层派生表。
     *
     * <p>MySQL 不允许 DELETE 的子查询里出现被删的表，报
     * {@code You can't specify target table 'xxx' for update in FROM clause}。
     * 其它方言（PG / SQLite / H2 / Oracle / SQL Server）都允许。</p>
     *
     * <p>改写办法是给子查询套一层派生表，让子查询结果先物化，
     * 语义与原句一致（每组保留一行）：</p>
     * <pre>
     * -- 原（MySQL 报错）
     * DELETE FROM t WHERE id NOT IN (SELECT MIN(x.id) FROM t x GROUP BY x.g);
     * -- 改写后
     * DELETE FROM t WHERE id NOT IN (SELECT d.m FROM (SELECT MIN(x.id) AS m FROM t x GROUP BY x.g) d);
     * </pre>
     *
     * <p>识别方式：DELETE 的目标表出现在自身的子查询里。</p>
     *
     * @param sql 转换后的单条语句
     * @return 改写后的语句；不是这种形态时返回 null
     */
    private String rewriteSelfReferencingDelete(String sql) {
        String trimmed = sql.trim();
        java.util.regex.Matcher head = Pattern.compile(
                "(?is)^DELETE\\s+FROM\\s+`?([A-Za-z_]\\w*)`?\\s+(.*)$").matcher(trimmed);
        if (!head.find()) {
            return null;
        }
        String table = head.group(1);
        String rest = head.group(2);
        // 子查询里出现同名的表 → 自我引用
        if (!Pattern.compile("(?i)\\bFROM\\s+`?" + table + "`?\\b").matcher(rest).find()) {
            return null;
        }
        // 找第一个子查询：IN ( ... ) / NOT IN ( ... ) / EXISTS ( ... )
        java.util.regex.Matcher sub = Pattern.compile("(?is)\\b(IN\\s*\\(|EXISTS\\s*\\()").matcher(rest);
        if (!sub.find()) {
            return null;
        }
        int open = sub.end() - 1;
        int close = matchParen(rest, open);
        if (close < 0) {
            return null;
        }
        String inner = rest.substring(open + 1, close).trim();
        // 已经是派生表（外面套了 FROM ( ... ) alias）就不改
        if (inner.toUpperCase(Locale.ROOT).startsWith("SELECT")
                && Pattern.compile("(?is)^SELECT\\s+.*\\bFROM\\s*\\(").matcher(inner).find()) {
            return null;
        }
        // 只改写「子查询里直接引用目标表」的那一种
        if (!Pattern.compile("(?i)\\bFROM\\s+`?" + table + "`?\\b").matcher(inner).find()) {
            return null;
        }
        String alias = "_selfref";
        // 内层的 MIN(...) 起个别名 m；外层引用 m。
        // 只改内层那份：先把内层整段替换掉，再改外层 —— 顺序反了会把内层也替成 m。
        String inner2 = inner.replaceAll("(?i)\\bMIN\\s*\\(([^()]*)\\)", "MIN($1) AS m");
        boolean renamed = !inner2.equals(inner);
        String derived = "SELECT " + (renamed ? alias + ".m" : "*") + " FROM (" + inner2 + ") " + alias;
        // 外层那段（子查询括号之外）里若还有 MIN(...)，一并改成引用派生表的 m
        String before = rest.substring(0, open);
        String after = rest.substring(close);
        if (renamed) {
            before = before.replaceAll("(?i)\\bMIN\\s*\\([^()]*\\)", alias + ".m");
            after = after.replaceAll("(?i)\\bMIN\\s*\\([^()]*\\)", alias + ".m");
        }
        // 括号要留着：IN ( ... ) / EXISTS ( ... ) 的括号是语法的一部分。
        // before 不含左括号、after 含右括号，故左括号要补、右括号原样保留。
        String rebuilt = before + "(" + derived + after;
        log.info("[ui] 表 {} 的删除语句里子查询引用了自身，已加派生表："
                + "MySQL 不允许对正在被修改的表做 FROM 子查询", table);
        return "DELETE FROM " + table + " " + rebuilt;
    }

    /**
     * 非 MySQL 方言下丢弃「前缀索引」语句。
     *
     * <p>前缀索引（{@code CREATE INDEX ix ON t (col(191))}）是 MySQL 专属语法，
     * 用来绕开 InnoDB 单个索引键 3072 字节的上限。其它方言：</p>
     * <ul>
     *   <li>不认这个语法 —— 实测 H2 报
     *       {@code Syntax error ... expected "ASC, DESC, NULLS, ,, )"}；</li>
     *   <li>即便去掉长度限定、建成整列索引，语义也变了
     *       （MySQL 是「前 191 字符唯一」，整列是「整列唯一」）。</li>
     * </ul>
     *
     * <p>故非 MySQL 方言下整条丢弃并记日志 —— 与 {@code buildCreateIndex} 对
     * 「表体内行内前缀索引」的处理保持一致（都是「该写法无等价物，跳过」）。</p>
     *
     * @param sql      转换后的单条语句
     * @param protocol 目标方言
     * @return 丢弃时返回空串（调用方跳过）；不是前缀索引语句时返回 null
     */
    private String dropPrefixIndexForNonMysql(String sql, String protocol) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        if (MYSQL_PROTOCOLS.contains(protocol == null ? "" : protocol.toLowerCase(Locale.ROOT))) {
            return null;
        }
        String trimmed = sql.trim();
        if (!trimmed.toUpperCase(Locale.ROOT).startsWith("CREATE INDEX")
                && !trimmed.toUpperCase(Locale.ROOT).startsWith("CREATE UNIQUE INDEX")) {
            return null;
        }
        // 列清单里带 (191) 这类长度限定。
        // 列清单要用「括号配平」取 —— 前缀索引的列名自带括号（col(191)），
        // 用 [^)]* 会在第一个右括号处截断，恰好把长度限定丢掉。
        java.util.regex.Matcher m = Pattern.compile(
                "(?is)^CREATE\\s+(UNIQUE\\s+)?INDEX\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                        + "[`\"]?\\w+[`\"]?\\s+ON\\s+[`\"]?\\w+[`\"]?\\s*\\(").matcher(trimmed);
        if (!m.find()) {
            return null;
        }
        int open = m.end() - 1;
        int close = matchParen(trimmed, open);
        if (close < 0) {
            return null;
        }
        String columnList = trimmed.substring(open + 1, close);
        if (!INDEX_COLUMN_PREFIX_LENGTH.matcher(columnList).find()) {
            return null;
        }
        log.info("[ui] 前缀索引语句在方言 {} 下丢弃：前缀索引是 MySQL 专属语法，"
                + "其它方言无法等价表达", protocol);
        return "";
    }

    /**
     * 「保留每组最小值」的 DELETE → 不用聚合函数的相关子查询写法。
     *
     * <p>脚本里的写法：</p>
     * <pre>{@code
     * DELETE FROM edu_idiom_grade
     * WHERE map_id NOT IN (
     *   SELECT MIN(map_id) FROM edu_idiom_grade GROUP BY idiom_id, grade_level, COALESCE(grade, '')
     * );
     * }</pre>
     *
     * <p>SQL Server 不接受「对正在被修改的表做聚合的子查询」，
     * 实测报 {@code Invalid use of aggregate function "MIN(MAP_ID)"}。</p>
     *
     * <p>改写成「相关子查询里逐行比较、不用聚合函数」的写法，语义等价
     * （每组只保留一行），各数据库通用：</p>
     * <pre>{@code
     * DELETE FROM t
     * WHERE NOT EXISTS (
     *   SELECT 1 FROM t x
     *   WHERE x.g1 = t.g1 AND x.g2 = t.g2 AND x.id < t.id
     * );
     * }</pre>
     *
     * @param sql 转换后的单条语句
     * @return 改写后的语句；不是这种形态时返回 null
     */
    private String rewriteKeepMinPerGroupDelete(String sql) {
        String trimmed = sql.trim();
        java.util.regex.Matcher m = Pattern.compile(
                "(?is)^DELETE\\s+FROM\\s+(\\w+)\\s+WHERE\\s+(\\w+)\\s+NOT\\s+IN\\s*\\(\\s*"
                        + "SELECT\\s+MIN\\s*\\(\\s*(\\w+)\\s*\\)\\s+FROM\\s+(\\w+)\\s+"
                        + "GROUP\\s+BY\\s+(.+?)\\s*\\)\\s*;?\\s*$").matcher(trimmed);
        if (!m.find()) {
            return null;
        }
        String table = m.group(1);
        String idCol = m.group(2);
        String minCol = m.group(3);
        String groupCols = m.group(5);
        // 分组列：拆出「列名 或 表达式」，逐个与外层表做等值比较。
        // 拆分要按括号配平 —— 表达式里的逗号（如 COALESCE(grade, '')）不是分隔符，
        // 直接按逗号切会把表达式切坏。
        List<String> cols = splitTopLevel(groupCols, ',');
        if (cols.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        // 别名要用 AS 引导：SQL Server 模式下「表名 别名」会被当成「schema.表」
        // （实测报 Schema "X" not found）
        sb.append("DELETE FROM ").append(table).append(" WHERE NOT EXISTS (SELECT 1 FROM ")
                .append(table).append(" AS x WHERE ");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                sb.append(" AND ");
            }
            String c = cols.get(i);
            if (c.indexOf('(') >= 0) {
                // 表达式（如 COALESCE(grade, '')）：表达式里的列属于本表的，
                // 直接比较「x 上的表达式」与「外层表上的表达式」——
                // 但表达式里的列名要指向 x，故把表达式里的裸列名换成 x.<列名>。
                sb.append(qualifyColumns(c, "x")).append(" = ").append(qualifyColumns(c, table));
            } else {
                sb.append("x.").append(c).append(" = ").append(table).append(".").append(c);
            }
        }
        sb.append(" AND x.").append(minCol).append(" < ").append(table).append(".").append(minCol);
        sb.append(")");
        log.info("[ui] 表 {} 的「保留每组最小值」删除已改写为不用聚合函数的相关子查询写法", table);
        return sb.toString();
    }

    /**
     * 按顶层分隔符切分（括号内与字符串内的分隔符不算）。
     *
     * @param text  待切分的文本
     * @param sep   分隔符
     * @return 切分后的各段（已去首尾空白，去掉空段）
     */
    private List<String> splitTopLevel(String text, char sep) {
        List<String> parts = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return parts;
        }
        int depth = 0;
        boolean inStr = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                if (inStr && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                inStr = !inStr;
                continue;
            }
            if (inStr) {
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == sep && depth == 0) {
                String part = text.substring(start, i).trim();
                if (!part.isEmpty()) {
                    parts.add(part);
                }
                start = i + 1;
            }
        }
        String last = text.substring(start).trim();
        if (!last.isEmpty()) {
            parts.add(last);
        }
        return parts;
    }

    /**
     * 把表达式里的裸列名加上表别名前缀。
     *
     * <p>表达式里除了列名还有函数名与字符串常量，不能一律加前缀 ——
     * 只给「紧跟在括号后、且后面不是 {@code (}」的标识符加前缀（函数调用不加）。</p>
     *
     * @param expr    表达式
     * @param qualifier 表别名前缀
     * @return 加了前缀的表达式
     */
    private String qualifyColumns(String expr, String qualifier) {
        StringBuilder out = new StringBuilder(expr.length());
        int i = 0;
        int n = expr.length();
        while (i < n) {
            char c = expr.charAt(i);
            if (c == '\'') {
                int end = expr.indexOf('\'', i + 1);
                if (end < 0) {
                    end = n;
                }
                out.append(expr, i, end + 1);
                i = end + 1;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(expr.charAt(i)) || expr.charAt(i) == '_')) {
                    i++;
                }
                String word = expr.substring(start, i);
                int j = i;
                while (j < n && Character.isWhitespace(expr.charAt(j))) {
                    j++;
                }
                boolean function = j < n && expr.charAt(j) == '(';
                out.append(function ? word : qualifier + "." + word);
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * MySQL 多表删除 → 通用子查询写法。
     *
     * <p>MySQL 独有写法：{@code DELETE t1 FROM tbl t1 INNER JOIN tbl t2 WHERE ...}
     * （同一个表自连接去重，本仓 device 模块的「任务名去重后建唯一索引」用它）。
     * 其它方言没有等价语法，H2 / PostgreSQL 直接报语法错。</p>
     *
     * <p>改写成 {@code DELETE FROM tbl WHERE id IN (SELECT ... 同款自连接 ...)}：
     * 语义与原句一致（删掉自连接中「id 更大」的那些行），且各数据库通用。</p>
     *
     * @param sql 转换后的单条语句
     * @return 改写后的语句；不是多表删除时返回 null     */
    private String rewriteMultiTableDelete(String sql) {
        String trimmed = sql.trim();
        java.util.regex.Matcher m = Pattern.compile(
                "(?is)^DELETE\\s+(\\w+)\\s+FROM\\s+(\\w+)\\s+(\\w+)\\s+(.*)$").matcher(trimmed);
        if (!m.find()) {
            return null;
        }
        // MySQL 写法：DELETE t1 FROM tbl t1 INNER JOIN tbl t2 WHERE t1.c1 = t2.c1 AND t1.c2 > t2.c2
        // 语义：删掉「同一 c1 分组里 c2 不是最小值」的那些行（每组保留一行）。
        // 通用等价写法：DELETE FROM tbl WHERE c2 NOT IN (SELECT MIN(c2) FROM tbl GROUP BY c1)
        String deleteAlias = m.group(1);
        String table = m.group(2);
        String tableAlias = m.group(3);
        String rest = m.group(4);
        // 分组列：形如 "<别名>.c1 = <别名2>.c1" 的等值条件
        java.util.regex.Matcher eq = Pattern.compile(
                "(?i)\\b\\w+\\.(\\w+)\\s*=\\s*\\w+\\.(\\w+)\\b").matcher(rest);
        if (!eq.find()) {
            return null;
        }
        String groupCol = eq.group(1);
        // 比较列：形如 "<别名>.c2 > <别名2>.c2" 的不等条件
        java.util.regex.Matcher gt = Pattern.compile(
                "(?i)\\b\\w+\\.(\\w+)\\s*>\\s*\\w+\\.(\\w+)\\b").matcher(rest);
        if (!gt.find()) {
            return null;
        }
        String keepCol = gt.group(1);
        log.info("[ui] 表 {} 的多表删除已改写为分组保留最小值写法（分组列 {}，保留列 {}）",
                table, groupCol, keepCol);
        return "DELETE FROM " + table + " WHERE " + keepCol + " NOT IN (SELECT MIN(" + keepCol
                + ") FROM " + table + " GROUP BY " + groupCol + ")";
    }

    /**
     * 把「一次加多列」的 ALTER 拆成多条单列 ALTER。
     *
     * <p>MySQL 允许 {@code ALTER TABLE t ADD COLUMN a ..., ADD COLUMN b ...} 一次加多列，
     * 但 H2 与 PostgreSQL 只接受一条 ADD COLUMN（本仓脚本里这样的写法有 3 处，
     * 见 datasource-hub 的两段存量补列与 device 的补列）。</p>
     *
     * <p>拆分时保留表名、其余修饰子句（{@code AFTER} 在目标库已被剥掉）与每列自己的定义。</p>
     *
     * @param sql 转换后的单条语句
     * @return 拆分后的语句列表；不是多列 ALTER 时返回空列表
     */
    private List<String> splitMultiColumnAlter(String sql) {
        String upper = sql.trim().toUpperCase(Locale.ROOT);
        if (!upper.startsWith("ALTER TABLE")) {
            return List.of();
        }
        // 找 ADD COLUMN 的出现次数与位置
        java.util.regex.Matcher m = Pattern.compile("(?i)\\bADD\\s+COLUMN\\b").matcher(sql);
        List<int[]> positions = new ArrayList<>();
        while (m.find()) {
            positions.add(new int[]{m.start(), m.end()});
        }
        if (positions.size() < 2) {
            return List.of();
        }
        String head = sql.substring(0, positions.get(0)[1]);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            int start = positions.get(i)[1];
            int end = (i + 1 < positions.size()) ? positions.get(i + 1)[0] : sql.length();
            String column = sql.substring(start, end).trim();
            // 去掉尾部逗号（列定义之间的分隔符）
            column = column.replaceAll(",\\s*$", "").trim();
            if (!column.isEmpty()) {
                result.add(head + " " + column);
            }
        }
        return result;
    }
    @Override
    public boolean supports(String protocol) {
        // 兜底实现支持全部协议（MySQL 系原样返回）
        return protocol != null;
    }

    @Override
    public String convert(String statement, String protocol) {
        if (statement == null || statement.isBlank() || protocol == null) {
            return statement;
        }
        String p = protocol.toLowerCase();
        // 「删除时子查询引用自身」→ 加派生表。
        // MySQL 不允许对正在被修改的表做 FROM 子查询，报
        // "You can't specify target table 'xxx' for update in FROM clause"；
        // 其它方言都允许，但加派生表后语义一致、处处可用，故统一改写。
        // 放在 MySQL 早退之前 —— MySQL 恰恰是最需要它的方言。
        String selfRef = rewriteSelfReferencingDelete(statement);
        if (selfRef != null) {
            statement = selfRef;
        }
        // MySQL 系数据库：脚本本身按 MySQL 风格编写，原样执行
        if (MYSQL_PROTOCOLS.contains(p)) {
            return statement;
        }

        String trimmed = statement.trim();
        String upper = trimmed.toUpperCase();
        // 1. 整语句跳过（MySQL 专属语句，目标库无等价物）
        if (upper.startsWith("SET NAMES") || upper.startsWith("SET FOREIGN_KEY_CHECKS")
                || upper.startsWith("SET @") || upper.startsWith("PREPARE ")
                || upper.startsWith("DEALLOCATE ") || upper.matches("EXECUTE\\s+\\S+")) {
            return null;
        }

        // 2. 表结构方言转换
        String result = statement;
        if (upper.startsWith("CREATE TABLE") || upper.startsWith("CREATE UNIQUE TABLE")) {
            result = convertCreateTable(result, null, p);
        } else if (upper.startsWith("ALTER TABLE")) {
            result = convertAlterTable(result);
        }

        // 3. 类型与函数方言映射
        result = applyTypeAndFunctionMapping(result, p);
        return result;
    }

    /**
     * CREATE TABLE 方言转换：剥离 MySQL 专属行内索引、尾属性、列内联注释。
     * <p>注意：剥离列内联注释时保留列类型（正则锚定在类型名上），
     * 仅删除尾随的 {@code COMMENT 'xxx'} 段。</p>
     * @param sql SQL，不允许为 null
     * @return 结果字符串
     */
    private String convertCreateTable(String sql) {
        return convertCreateTable(sql, null, null);
    }

    /**
     * CREATE TABLE 转换 + 副产品收集（索引语句、自增列）。
     *
     * @param sql   SQL，不允许为 null
     * @param index 收集到的 CREATE INDEX 语句（可为空），不允许为 null
     * @return 转换后的建表语句
     */
    private String convertCreateTable(String sql, List<String> index, String protocol) {
        // 先剥掉块注释：运行期的 FlywayScripts.splitStatements 只跳过 "--" 行注释，
        // 块注释会原样留在语句里（本仓多个模块的脚本头部就是块注释）。
        // 不剥的话，块注释里的正文行会被当成 SQL 行逐行处理，表体结构与尾属性都会被切错。
        sql = stripBlockComments(sql);
        // 找表体的收尾括号：必须按括号配平定位，不能用 lastIndexOf(')')。
        // 原因：PRIMARY KEY (`id`)、KEY idx (`col`) 这些行内声明自带括号，
        // lastIndexOf 会命中它们中的最后一个，把「表体末尾」与「表尾属性」切错位置 ——
        // 实测 `PRIMARY KEY (`id`)) ENGINE=InnoDB ...` 这种写法下，
        // 表尾的 ENGINE/COMMENT 会被留在表体里，剥不掉（目标库直接语法错）。
        int lastParen = findTableBodyEnd(sql);
        String body = lastParen > 0 ? sql.substring(0, lastParen) : sql;
        String tail = lastParen > 0 ? sql.substring(lastParen) : "";

        // 表名（用于生成 CREATE INDEX）：CREATE TABLE [IF NOT EXISTS] <name>
        String tableName = extractTableName(sql);

        // 建表语句里各列的「声明写法」（含定界符）：生成的 CREATE INDEX 必须用
        // 与建表完全一致的写法引用列，否则 Oracle / SQL Server 这类库会当成另一个列 ——
        // 未加引号的标识符会折叠成大写，加了引号的保持原样大小写。
        // 实测：建表写 device_info_org_id（无引号）而索引写 "device_info_org_id"（有引号），
        // Oracle 兼容模式下报 Column "device_info_org_id" not found。
        java.util.Map<String, String> declaredColumns = collectDeclaredColumns(body);

        // 表体：逐行剥离行内索引声明（并收集成独立 CREATE INDEX）
        String[] lines = body.split("\n", -1);
        StringBuilder cleanedBody = new StringBuilder();
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            if (line.trim().isEmpty()) {
                cleanedBody.append(line).append('\n');
                continue;
            }
            // 行内索引声明可能跨多行（列清单逐列换行，如
            // "UNIQUE KEY uk_x (\n  col_a,\n  col_b\n)"）。先把整块拼出来再匹配，
            // 否则多行形式匹配不上、整块留在表体里，目标库直接语法错。
            if (isIndexDeclarationStart(line)) {
                int end = li;
                StringBuilder block = new StringBuilder();
                while (end < lines.length) {
                    block.append(lines[end]).append('\n');
                    if (indexDeclarationComplete(lines[end])) {
                        break;
                    }
                    end++;
                }
                java.util.regex.Matcher keyMatcher = INLINE_KEY_LINE.matcher(block.toString().trim());
                if (keyMatcher.matches()) {
                    if (index != null && tableName != null) {
                        String generated = buildCreateIndex(tableName, keyMatcher, declaredColumns);
                        if (generated != null) {
                            index.add(generated);
                        }
                    }
                    li = end;
                    continue;
                }
                // 括号没配平（不是索引声明）：按普通行继续处理
                block.setLength(0);
                block.append(lines[li]).append('\n');
            }
            java.util.regex.Matcher keyMatcher = INLINE_KEY_LINE.matcher(line);
            if (keyMatcher.matches()) {
                // 整行是行内索引声明：连同该行自身的分隔逗号一起整行删除。
                // 这里绝不能改为删除「上一行」的逗号：上一行的逗号是它与更早列之间的分隔符，
                // 删掉会让相邻两列粘连成 "... VARCHAR(64) col_id BIGINT ..." 而报语法错误。
                // KEY 行位于表体末尾时残留的悬挂逗号，由本方法末尾的悬挂逗号清理统一兜底
                if (index != null && tableName != null) {
                    String generated = buildCreateIndex(tableName, keyMatcher, declaredColumns);
                    if (generated != null) {
                        index.add(generated);
                    }
                }
                continue;
            }
            // 列内联注释剥离（保留类型/默认值等前缀 + 列分隔逗号，仅删 COMMENT 'xxx' 文本）
            String cleaned = COLUMN_COMMENT.matcher(line).replaceAll(m -> m.group(1));
            // 列级 CHARACTER SET / COLLATE 子句剥离（SHOW CREATE TABLE 导出风格）
            cleaned = COLUMN_CHARSET_COLLATE.matcher(cleaned).replaceAll("");
            // PRIMARY KEY 行尾 USING BTREE 子句剥离（SHOW CREATE TABLE 导出风格）
            cleaned = PK_USING.matcher(cleaned).replaceAll("");
            // 列级 AUTO_INCREMENT 剥离（MySQL 专属）：非 MySQL 方言的等价写法由
            // buildIdentityColumn 产出；这里先把 MySQL 关键字去掉，避免残留语法错
            cleaned = stripAutoIncrement(cleaned, protocol);
            cleanedBody.append(cleaned).append('\n');
        }

        // 尾属性剥离
        tail = TAIL_ENGINE.matcher(tail).replaceAll("");
        tail = TAIL_AUTO_INCREMENT.matcher(tail).replaceAll("");
        tail = TAIL_CHARACTER_SET.matcher(tail).replaceAll("");
        tail = TAIL_COLLATE.matcher(tail).replaceAll("");
        tail = TAIL_ROW_FORMAT.matcher(tail).replaceAll("");
        tail = TAIL_COMMENT.matcher(tail).replaceAll("");
        // 剥离孤儿尾属性 DEFAULT（"DEFAULT CHARSET=utf8mb4" 被 TAIL_CHARACTER_SET 剥掉 CHARSET 后遗留的
        // 无对象 DEFAULT 关键字；仅在表尾属性区生效，避免误伤列定义中的 "TINYINT(1) DEFAULT 1"）
        tail = tail.replaceAll("\\)\\s*DEFAULT\\b", ")");

        String result = cleanedBody.toString() + tail;
        // 修正可能遗留的悬挂逗号（中间行逗号 + 末行逗号）
        result = result.replaceAll(",\\s*\\)", ")");
        result = result.replaceAll(",\\s*$", "");
        // 剥离列定义中的 ON UPDATE CURRENT_TIMESTAMP（MySQL 专属，PG/Oracle/H2/SQLite 不支持）
        result = result.replaceAll("(?i)\\s+ON\\s+UPDATE\\s+CURRENT_TIMESTAMP", "");
        // 剥离列定义中的 MySQL AFTER 定位子句（列位置声明，其它方言无等价语法）
        // 例：`col` VARCHAR(32) NULL COMMENT 'x' AFTER `other_col`
        // 实测 PostgreSQL 报 ERROR: syntax error at or near "AFTER"
        // 标识符定界要同时认反引号与双引号（rewriteBackticks 在本方法之前执行）
        result = result.replaceAll("(?i)\\s+AFTER\\s+[`\"]?\\w+[`\"]?", "");
        // 整型列显示宽度剥离（TINYINT(1)/INT(11)：H2 2.x 严格模式报 42001）
        result = INTEGER_DISPLAY_WIDTH.matcher(result).replaceAll("$1");
        return result;
    }

    /**
     * 该行是否是行内索引声明的<b>起始行</b>（可能跨多行）。
     *
     * @param line 表体中的一行
     * @return true 表示该行以索引声明开头
     */
    private boolean isIndexDeclarationStart(String line) {
        String t = line.trim();
        return t.matches("(?i)^(UNIQUE\\s+)?(KEY|INDEX)\\s+\\S+\\s*\\(.*$")
                && !t.matches("(?i)^(UNIQUE\\s+)?(KEY|INDEX)\\s+\\S+\\s*\\([^)]*\\)\\s*,?\\s*$");
    }

    /**
     * 该行是否是行内索引声明的<b>结束行</b>（括号已配平）。
     *
     * @param line 表体中的一行
     * @return true 表示索引声明到此结束
     */
    private boolean indexDeclarationComplete(String line) {
        String t = line.trim();
        return t.matches("(?i)^.*\\)\\s*(USING\\s+\\S+)*(\\s+COMMENT\\s+'(?:[^']|'')*')?\\s*,?\\s*$");
    }

    /**
     * 剥掉块注释。
     *
     * <p>运行期的 {@code FlywayScripts.splitStatements} 只跳过 {@code --} 行注释，
     * 块注释会原样留在语句里；本转换器按行处理建表语句，块注释的正文行会被当成 SQL 行，
     * 导致表体与表尾属性切错位置。字符串字面量内的注释起始符不算注释。</p>
     *
     * @param sql SQL
     * @return 剥掉块注释后的 SQL
     */
    private String stripBlockComments(String sql) {
        if (sql == null || sql.indexOf("/*") < 0) {
            return sql;
        }
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                int end = skipQuoted(sql, i);
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    // 未闭合的块注释：去掉剩余内容
                    break;
                }
                // 用空白替换，保持语句长度与行结构不变（便于日志定位）
                out.append(' ');
                i = end + 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * 定位建表语句「表体」的收尾括号位置（即与表体开括号配平的那个 {@code )}）。
     *
     * <p>不能用 {@code lastIndexOf(')')}：表体内的行内声明自带括号
     * （{@code PRIMARY KEY (`id`)}、{@code KEY idx (`col`)}），
     * lastIndexOf 会命中其中最后一个，把表体末尾与表尾属性切错位置。
     * 正确做法是从表体开括号起做括号配平。</p>
     *
     * <p>字符串字面量内的括号不参与配平（注释与引号内的括号都跳过），
     * 否则 {@code COMMENT '备注(说明)'} 这类文本会把配平带偏。</p>
     *
     * @param sql CREATE TABLE 语句
     * @return 表体收尾括号的下标；解析不出时返回 -1
     */
    private int findTableBodyEnd(String sql) {
        // 找表体开括号：CREATE TABLE [IF NOT EXISTS] name (
        int open = -1;
        int idx = 0;
        while (idx < sql.length()) {
            char c = sql.charAt(idx);
            if (c == '\'' || c == '"') {
                idx = skipQuoted(sql, idx);
                continue;
            }
            if (c == '(') {
                open = idx;
                break;
            }
            idx++;
        }
        if (open < 0) {
            return -1;
        }
        int depth = 0;
        for (int i = open; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipQuoted(sql, i) - 1;
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
     * 跳过字符串字面量（含转义的成对引号与反斜杠转义），返回字面量结束后的下标。
     *
     * <p>字符串内的 {@code \'} 不结束字面量（MySQL 风格转义）—— 中文诗词种子里
     * 引用词句很常见，不处理会让字面量提前闭合，后续内容的括号配平全被带偏。</p>
     *
     * @param sql  SQL
     * @param from 引号所在下标
     * @return 字面量结束后的下标
     */
    private int skipQuoted(String sql, int from) {
        char quote = sql.charAt(from);
        int i = from + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\\' && i + 1 < sql.length()) {
                // 反斜杠转义：\' 与 \\ 都不结束字面量
                i += 2;
                continue;
            }
            if (c == quote) {
                // 成对引号是转义，不算结束
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return i;
    }

    /**
     * 提取 CREATE TABLE 语句的表名。
     *
     * <p><b>保留标识符的定界符</b>：生成的 CREATE INDEX 必须用与建表语句<b>完全相同</b>的
     * 定界方式引用表名。Derby 等库对带引号的标识符是大小写敏感的 ——
     * 建表写的是 {@code "sys_task_store"}，而索引里若写成不带引号的
     * {@code sys_task_store}，Derby 会当成另一个大写标识符，报
     * {@code 'CREATE INDEX' cannot be performed on 'SYS_TASK_STORE' because it does not exist}。</p>
     *
     * @param sql CREATE TABLE 语句，不允许为 null
     * @return 表名（保留原定界符）；解析不出时返回 null
     */
    private String extractTableName(String sql) {
        java.util.regex.Matcher m = Pattern.compile(
                "(?i)^\\s*CREATE\\s+(?:UNIQUE\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                        + "([`\"]?[A-Za-z_]\\w*[`\"]?)")
                .matcher(sql.trim());
        if (!m.find()) {
            return null;
        }
        String raw = m.group(1);
        // 定界符成对时保留，否则去掉（原本就未加定界的表名）
        if (raw.length() >= 2) {
            char q = raw.charAt(0);
            if ((q == '`' || q == '"') && raw.charAt(raw.length() - 1) == q) {
                return raw;
            }
        }
        return raw.replace("`", "").replace("\"", "");
    }

    /**
     * 由行内索引声明生成独立的 CREATE INDEX 语句。
     *
     * <p>前缀索引（{@code col(255)}，MySQL 专属）在其它方言没有等价物：改写为整列唯一索引会
     * 改变语义（MySQL 的前缀唯一是「前 255 字符唯一」，整列唯一是「整列唯一」），
     * 而 {@code TEXT} 列在多数方言上根本不能建整列唯一索引。因此前缀唯一索引
     * <b>不生成</b>，并在日志里留痕，避免静默变成「没有约束」。</p>
     *
     * @param tableName      表名
     * @param matcher        已匹配到 {@link #INLINE_KEY_LINE} 的 matcher
     * @param declaredColumns 建表语句里各列的声明写法（列名 → 含定界符的列名）
     * @return CREATE INDEX 语句；无法生成时返回 null
     */
    private String buildCreateIndex(String tableName, java.util.regex.Matcher matcher,
                                    java.util.Map<String, String> declaredColumns) {
        boolean unique = matcher.group(1) != null;
        String indexName = matcher.group(2);
        String columns = matcher.group(3);
        if (columns == null || columns.isBlank() || indexName == null) {
            return null;
        }
        // 前缀索引：列清单里带 (255) 这类长度限定
        if (INDEX_COLUMN_PREFIX_LENGTH.matcher(columns).find()) {
            log.warn("[ui] 表 {} 的索引 {} 使用了前缀索引（列长度限定），"
                    + "该写法在非 MySQL 方言无等价物，已跳过不生成索引", tableName, indexName);
            return null;
        }
        String cols = alignColumnQuoting(columns.trim(), declaredColumns);
        return "CREATE " + (unique ? "UNIQUE " : "") + "INDEX " + indexName + " ON " + tableName + " (" + cols + ")";
    }

    /**
     * 把索引的列清单改成与建表语句一致的定界写法。
     *
     * <p>索引声明里的列名通常带反引号（{@code `col`}），而建表语句里的列可能没带
     * （{@code col}）。两者在 Oracle / SQL Server 这类库上是<b>不同的标识符</b>：
     * 未加引号的折叠成大写，加了引号的保持原样，于是索引会找不到列 ——
     * 实测报 {@code Column "device_info_org_id" not found}。</p>
     *
     * <p>故按建表语句里的声明写法来引用列。建表里找不到的列保持原样
     * （让目标库自己报错，好过静默改错）。</p>
     *
     * @param columns         索引的列清单
     * @param declaredColumns 建表语句里各列的声明写法
     * @return 定界方式与建表一致的列清单
     */
    private String alignColumnQuoting(String columns, java.util.Map<String, String> declaredColumns) {
        if (declaredColumns == null || declaredColumns.isEmpty()) {
            return columns;
        }
        StringBuilder out = new StringBuilder(columns.length());
        int i = 0;
        while (i < columns.length()) {
            char c = columns.charAt(i);
            if (c == '`' || c == '"') {
                int end = columns.indexOf(c, i + 1);
                if (end < 0) {
                    out.append(columns, i, columns.length());
                    break;
                }
                String name = columns.substring(i + 1, end);
                String declared = declaredColumns.get(name.toUpperCase(Locale.ROOT));
                out.append(declared != null ? declared : columns.substring(i, end + 1));
                i = end + 1;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * 收集建表语句里各列的声明写法。
     *
     * <p>只认「列定义行」：以标识符开头、后面跟类型或约束，不带表级关键字
     * （{@code PRIMARY KEY} / {@code KEY} / {@code UNIQUE} / {@code INDEX} /
     * {@code CONSTRAINT} / {@code CHECK} / {@code FOREIGN}）。</p>
     *
     * @param body 建表语句的表体部分
     * @return 列名（大写） → 含定界符的列名
     */
    private java.util.Map<String, String> collectDeclaredColumns(String body) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        for (String line : body.split("\n", -1)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("--") || t.startsWith("/*")) {
                continue;
            }
            java.util.regex.Matcher m = Pattern.compile(
                    "^([`\"]?[A-Za-z_]\\w*[`\"]?)\\s+\\S").matcher(t);
            if (!m.find()) {
                continue;
            }
            String raw = m.group(1);
            String bare = raw.replace("`", "").replace("\"", "");
            // 排除表级声明行
            String upper = bare.toUpperCase(Locale.ROOT);
            if ("PRIMARY".equals(upper) || "KEY".equals(upper) || "UNIQUE".equals(upper)
                    || "INDEX".equals(upper) || "CONSTRAINT".equals(upper) || "CHECK".equals(upper)
                    || "FOREIGN".equals(upper) || "FULLTEXT".equals(upper) || "SPATIAL".equals(upper)) {
                continue;
            }
            map.putIfAbsent(upper, raw);
        }
        return map;
    }

    /**
     * 剥离列级 AUTO_INCREMENT 关键字（MySQL 专属），并按目标方言补上等价的自增声明。
     *
     * <p><b>为什么要补而不是只剥</b>：{@code AUTO_INCREMENT} 声明的是「本列自动生成」。
     * 只剥掉关键字（早期实现）会让该列退化成普通列 —— 表建得出来，但主键不再自动赋值，
     * 运行期表现为「插入时主键为 null」。各方言的等价写法不同，故按目标方言补：</p>
     * <ul>
     *   <li>H2 / PostgreSQL：{@code IDENTITY}（PG 10+ 与 H2 2.x 原生支持）</li>
     *   <li>Oracle：{@code GENERATED BY DEFAULT AS IDENTITY}（12c+）</li>
     *   <li>SQL Server：{@code IDENTITY(1,1)}</li>
     *   <li>SQLite / DuckDB / ClickHouse：不支持列级自增 → 退回<b>不补</b>，
     *       并保留提示日志（这两类库的自增通常由应用侧或序列承担）</li>
     * </ul>
     *
     * @param line     列定义行
     * @param protocol 目标方言协议名
     * @return 处理后的列定义行
     */
    private String stripAutoIncrement(String line, String protocol) {
        // 先记下这列是不是自增列：命中即用方言等价写法替换
        java.util.regex.Matcher m = COLUMN_AUTO_INCREMENT.matcher(line);
        if (m.find()) {
            String identity = identityClause(protocol);
            if (identity != null) {
                return m.replaceFirst(java.util.regex.Matcher.quoteReplacement(
                        rebuildIdentityColumn(m.group(1), m.group(2), identity)));
            }
            log.warn("[ui] 目标方言 {} 不支持列级自增，列 {} 的 AUTO_INCREMENT 已剥离（该列不再自动生成值）",
                    protocol, m.group(1));
            return m.replaceFirst(java.util.regex.Matcher.quoteReplacement(
                    m.group(1) + " " + m.group(2).trim()));
        }
        java.util.regex.Matcher bare = COLUMN_AUTO_INCREMENT_BARE.matcher(line);
        if (bare.find()) {
            String identity = identityClause(protocol);
            if (identity != null) {
                return bare.replaceFirst(java.util.regex.Matcher.quoteReplacement(
                        rebuildIdentityColumn(bare.group(1), "INT", identity)));
            }
            return bare.replaceFirst(java.util.regex.Matcher.quoteReplacement(bare.group(1) + " INT"));
        }
        return line;
    }

    /**
     * 重建带自增语义的列定义。
     *
     * <p>方言的自增声明必须排在 {@code PRIMARY KEY} <b>之前</b>：H2 2.x 对
     * {@code id BIGINT PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY} 直接报语法错
     * （期望 DEFAULT/GENERATED/... 但先见到 PRIMARY KEY），正确写法是
     * {@code id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY}。</p>
     *
     * @param columnName 列名
     * @param typeAndConstraint 类型与约束部分（可能含 PRIMARY KEY）
     * @param identity      方言自增声明
     * @return 重建后的列定义
     */
    private String rebuildIdentityColumn(String columnName, String typeAndConstraint, String identity) {
        String prefix = typeAndConstraint == null ? "" : typeAndConstraint.trim();
        // 把 PRIMARY KEY 从约束部分摘出来，挪到自增声明之后
        java.util.regex.Matcher pk = Pattern.compile("(?i)PRIMARY\\s+KEY").matcher(prefix);
        String primaryKey = "";
        if (pk.find()) {
            primaryKey = " PRIMARY KEY";
            prefix = (prefix.substring(0, pk.start()) + prefix.substring(pk.end())).trim();
        }
        StringBuilder sb = new StringBuilder();
        sb.append(columnName).append(' ');
        if (!prefix.isEmpty()) {
            sb.append(prefix).append(' ');
        }
        sb.append(identity);
        sb.append(primaryKey);
        return sb.toString();
    }

    /**
     * 取目标方言的列级自增声明。
     *
     * @param protocol 目标方言协议名
     * @return 自增声明子句；该方言不支持时返回 null
     */
    private String identityClause(String protocol) {
        if (protocol == null) {
            return null;
        }
        return switch (protocol.toLowerCase(Locale.ROOT)) {
            // 标准 SQL 的 identity 列约束：H2 2.x / PG 10+ / Oracle 12c+ 三者通用。
            // 实测 H2 2.x 标准模式只认这一种写法：BIGINT IDENTITY 与
            // BIGINT NOT NULL IDENTITY 均报语法错（期望 DEFAULT/GENERATED/... 但见到 IDENTITY）。
            case "h2", "postgresql", "oracle" -> "GENERATED BY DEFAULT AS IDENTITY";
            case "sqlserver" -> "IDENTITY(1,1)";
            default -> null;
        };
    }


    /**
     * ALTER TABLE 方言转换：剥离 MySQL 专属 AFTER 定位子句、列内联注释。
     * <p>ALTER 语句的 COMMENT 段可能不在行尾（如多列 ADD 子句），使用无行尾锚定的宽松剥离。</p>
     * @param sql SQL，不允许为 null
     * @return 结果字符串
     */
    private String convertAlterTable(String sql) {
        // 剥离内联注释（宽松模式：COMMENT 'xxx' 段后允许跟随逗号/空白）
        sql = sql.replaceAll("(?i)\\s+COMMENT\\s+'(?:[^']|'')*'", "");
        // 剥离 MySQL AFTER 定位子句
        // 标识符定界要同时认反引号与双引号：改写标识符定界（rewriteBackticks）
        // 在本方法之前执行，反引号已变成双引号，只认反引号会漏剥
        sql = sql.replaceAll("(?i)\\s+AFTER\\s+[`\"]?\\w+[`\"]?", "");
        // 修正在注释剥离后可能遗留的悬挂逗号
        sql = sql.replaceAll(",\\s*\\)", ")");
        sql = sql.replaceAll(",\\s*$", "");
        return sql;
    }

    /**
     * 类型与函数方言映射（保守策略：只替换可安全替换的独立类型/函数关键字）。
     * @param sql SQL，不允许为 null
     * @param protocol 方法入参 protocol
     * @return 结果字符串
     */
    private String applyTypeAndFunctionMapping(String sql, String protocol) {
        switch (protocol) {
            case "postgresql" -> {
                sql = sql.replaceAll("(?i)\\bMEDIUMTEXT\\b", "TEXT");
                sql = sql.replaceAll("(?i)\\bLONGTEXT\\b", "TEXT");
                sql = sql.replaceAll("(?i)\\bTINYTEXT\\b", "TEXT");
                sql = sql.replaceAll("(?i)\\bDATETIME\\b", "TIMESTAMP");
                sql = sql.replaceAll("(?i)\\bNOW\\s*\\(\\s*\\)", "CURRENT_TIMESTAMP");
                sql = sql.replaceAll("(?i)\\bIFNULL\\s*\\(", "COALESCE(");
            }
            case "oracle" -> {
                sql = sql.replaceAll("(?i)\\bMEDIUMTEXT\\b", "CLOB");
                sql = sql.replaceAll("(?i)\\bLONGTEXT\\b", "CLOB");
                sql = sql.replaceAll("(?i)\\bTINYTEXT\\b", "CLOB");
                sql = sql.replaceAll("(?i)\\bTEXT\\b", "CLOB");
                sql = sql.replaceAll("(?i)\\bDATETIME\\b", "TIMESTAMP");
                sql = sql.replaceAll("(?i)\\bTINYINT\\b", "NUMBER(3)");
                sql = sql.replaceAll("(?i)\\bNOW\\s*\\(\\s*\\)", "SYSDATE");
                sql = sql.replaceAll("(?i)\\bIFNULL\\s*\\(", "NVL(");
            }
            case "sqlserver" -> {
                sql = sql.replaceAll("(?i)\\bMEDIUMTEXT\\b", "VARCHAR(MAX)");
                sql = sql.replaceAll("(?i)\\bLONGTEXT\\b", "VARCHAR(MAX)");
                sql = sql.replaceAll("(?i)\\bTINYTEXT\\b", "VARCHAR(MAX)");
                sql = sql.replaceAll("(?i)\\bTEXT\\b", "VARCHAR(MAX)");
                sql = sql.replaceAll("(?i)\\bDATETIME\\b", "DATETIME2");
                sql = sql.replaceAll("(?i)\\bIFNULL\\s*\\(", "ISNULL(");
            }
            case "h2" -> {
                // H2（非 MySQL 模式）：JSON 类型映射为 CLOB（H2 1.x 无 JSON 类型）
                sql = sql.replaceAll("(?i)\\bJSON\\b", "CLOB");
            }
            case "sqlite" -> {
                sql = sql.replaceAll("(?i)\\bMEDIUMTEXT\\b", "TEXT");
                sql = sql.replaceAll("(?i)\\bLONGTEXT\\b", "TEXT");
                sql = sql.replaceAll("(?i)\\bTINYTEXT\\b", "TEXT");
                sql = sql.replaceAll("(?i)\\bDATETIME\\b", "TIMESTAMP");
                sql = sql.replaceAll("(?i)\\bJSON\\b", "TEXT");
                // SQLite 无 NOW() 函数，MySQL 风格脚本需映射到标准 CURRENT_TIMESTAMP
                sql = sql.replaceAll("(?i)\\bNOW\\s*\\(\\s*\\)", "CURRENT_TIMESTAMP");
            }
            default -> {
                // duckdb/hive/其他协议：仅通用函数映射
                sql = sql.replaceAll("(?i)\\bNOW\\s*\\(\\s*\\)", "CURRENT_TIMESTAMP");
            }
        }
        return sql;
    }
}
