package com.chua.common.support.lang.datasource.flyway;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;

import java.util.List;
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
 * @Spi(value = ScriptConverter.SPI_NAME, order = 100)
 * public class MyPgConverter extends DefaultScriptConverter {
 *     public boolean supports(String protocol) { return "postgresql".equals(protocol); }
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@SpiDefault
@Spi(value = ScriptConverter.SPI_NAME, order = 0)
public class DefaultScriptConverter implements ScriptConverter {

    /** MySQL 系协议：脚本按 MySQL 风格编写，原样执行 */
    private static final List<String> MYSQL_PROTOCOLS =
            List.of("mysql", "mariadb", "tidb", "oceanbase");

    /** 行内索引声明行（CREATE TABLE 表体内整行）：
    *  {@code KEY idx_x (col)} / {@code UNIQUE KEY uk_x (a,b)} / {@code INDEX idx_x (col) USING BTREE}
    *  / 尾随 {@code COMMENT 'xxx'}（SHOW CREATE TABLE 导出风格）。USING 子句可重复，COMMENT 段可选 */
    private static final Pattern INLINE_KEY_LINE = Pattern.compile(
            "^(?:\\s*,)?\\s*(?:UNIQUE\\s+)?(?:KEY|INDEX)\\s+\\S+\\s*\\(.*\\)(?:\\s*USING\\s+\\S+)*"
                    + "(?:\\s+COMMENT\\s+'(?:[^']|'')*')?\\s*,?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
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
    private static final Pattern TAIL_COMMENT = Pattern.compile(
            "\\s*COMMENT\\s*=?\\s*'(?:[^']|'')*'", Pattern.CASE_INSENSITIVE);

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
            result = convertCreateTable(result);
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
        int lastParen = sql.lastIndexOf(')');
        String body = lastParen > 0 ? sql.substring(0, lastParen) : sql;
        String tail = lastParen > 0 ? sql.substring(lastParen) : "";

        // 表体：逐行剥离行内索引声明
        String[] lines = body.split("\n", -1);
        StringBuilder cleanedBody = new StringBuilder();
        String prevLine = "";
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                cleanedBody.append(line).append('\n');
                continue;
            }
            if (INLINE_KEY_LINE.matcher(line).matches()) {
                // 整行是行内索引声明，删除；若上一行以逗号结尾则去掉悬挂逗号
                if (prevLine.endsWith(",")) {
                    int len = cleanedBody.length();
                    int idx = len - 1;
                    while (idx > 0 && Character.isWhitespace(cleanedBody.charAt(idx))) {
                        idx--;
                    }
                    if (idx >= 0 && cleanedBody.charAt(idx) == ',') {
                        cleanedBody.delete(idx, len);
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
            cleanedBody.append(cleaned).append('\n');
            prevLine = cleaned;
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
        return result;
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
        sql = sql.replaceAll("(?i)\\s+AFTER\\s+`?\\w+`?", "");
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
            }
            default -> {
                // duckdb/hive/其他协议：仅通用函数映射
                sql = sql.replaceAll("(?i)\\bNOW\\s*\\(\\s*\\)", "CURRENT_TIMESTAMP");
            }
        }
        return sql;
    }
}
