// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.lang.format;

import com.chua.common.support.collection.wrap.Wraps;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import com.chua.common.support.utils.StringUtils;

/**
 * SQL关键词高亮工具类。
 *
 * <p><b>功能：</b>
 * <ul>
 *   <li>将SQL中的关键词替换为带颜色标签的版本</li>
 *   <li>支持ANSI控制台颜色和HTML颜色两种模式</li>
 *   <li>自动识别关键词类别并分配不同颜色</li>
 * </ul>
 *
 * <p><b>使用示例：</b>
 * <pre>
 * // ANSI高亮（控制台）
 * HighlightConfig config = new HighlightConfig();
 * config.setType(HighlightConfig.HighlightType.ANSI);
 * String highlighted = SqlHighlighter.highlight("SELECT * FROM users", config);
// [P3C E1 豁免] 场景化输出，非业务日志
 * System.out.println(highlighted); // 控制台输出带颜色的SQL
 *
 * // HTML高亮（网页）
 * config.setType(HighlightConfig.HighlightType.HTML);
 * String html = SqlHighlighter.highlight("SELECT * FROM users", config);
 * // 输出: <span style="color:#42b983;">SELECT</span> * FROM users
 * </pre>
 *
 * @author CH
 * @since 1.0.0
 */
public class SqlHighlighter {

    // ==================== 关键词分类 ====================

    /**
     * DDL关键词（数据定义语言）
     */
    private static final Set<String> DDL_KEYWORDS = Wraps.set(Arrays.asList(
            "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME",
            "TABLE", "VIEW", "INDEX", "SEQUENCE", "DATABASE", "SCHEMA"
    ));

    /**
     * DML关键词（数据操作语言）
     */
    private static final Set<String> DML_KEYWORDS = Wraps.set(Arrays.asList(
            "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE"
    ));

    /**
     * 查询子句关键词
     */
    private static final Set<String> CLAUSE_KEYWORDS = Wraps.set(Arrays.asList(
            "FROM", "WHERE", "AND", "OR", "NOT", "NULL",
            "ORDER", "BY", "GROUP", "HAVING", "LIMIT", "OFFSET",
            "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS",
            "ON", "USING", "NATURAL",
            "UNION", "INTERSECT", "EXCEPT", "MINUS",
            "WITH", "AS", "INTO", "VALUES", "SET"
    ));

    /**
     * 数据类型关键词
     */
    private static final Set<String> DATA_TYPE_KEYWORDS = Wraps.set(Arrays.asList(
            "INT", "INTEGER", "BIGINT", "SMALLINT", "TINYINT",
            "VARCHAR", "CHAR", "TEXT", "CLOB", "BLOB",
            "DATE", "TIME", "DATETIME", "TIMESTAMP",
            "DECIMAL", "NUMERIC", "FLOAT", "DOUBLE",
            "BOOLEAN", "BIT",
            "JSON", "XML", "UUID"
    ));

    /**
     * 函数关键词
     */
    private static final Set<String> FUNCTION_KEYWORDS = Wraps.set(Arrays.asList(
            "COUNT", "SUM", "AVG", "MAX", "MIN",
            "LENGTH", "UPPER", "LOWER", "TRIM", "SUBSTR", "SUBSTRING",
            "NOW", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP",
            "DATE_ADD", "DATE_SUB", "DATEDIFF",
            "IF", "CASE", "WHEN", "THEN", "ELSE", "END"
    ));

    /**
     * 操作符关键词
     */
    private static final Set<String> OPERATOR_KEYWORDS = Wraps.set(Arrays.asList(
            "IN", "EXISTS", "BETWEEN", "LIKE", "ESCAPE",
            "IS", "DISTINCT", "ALL", "ANY", "SOME"
    ));

    /**
     * 约束关键词
     */
    private static final Set<String> CONSTRAINT_KEYWORDS = Wraps.set(Arrays.asList(
            "PRIMARY", "KEY", "FOREIGN", "REFERENCES",
            "UNIQUE", "CHECK", "DEFAULT", "CONSTRAINT",
            "CASCADE", "RESTRICT", "NO ACTION", "SET NULL"
    ));

    // ==================== 合并所有关键词 ====================

    /**
     * All_keywords
     */
    private static final Set<String> ALL_KEYWORDS;
    /**
     * KEYWORD_CATEGORY_MAP
     */
    private static final Map<String, HighlightConfig.KeywordCategory> KEYWORD_CATEGORY_MAP;

    static {
        ALL_KEYWORDS = Wraps.set();
        ALL_KEYWORDS.addAll(DDL_KEYWORDS);
        ALL_KEYWORDS.addAll(DML_KEYWORDS);
        ALL_KEYWORDS.addAll(CLAUSE_KEYWORDS);
        ALL_KEYWORDS.addAll(DATA_TYPE_KEYWORDS);
        ALL_KEYWORDS.addAll(FUNCTION_KEYWORDS);
        ALL_KEYWORDS.addAll(OPERATOR_KEYWORDS);
        ALL_KEYWORDS.addAll(CONSTRAINT_KEYWORDS);

        KEYWORD_CATEGORY_MAP = Wraps.map();
        for (String kw : DDL_KEYWORDS) {
            KEYWORD_CATEGORY_MAP.put(kw, HighlightConfig.KeywordCategory.DDL);
        }
        for (String kw : DML_KEYWORDS) {
            KEYWORD_CATEGORY_MAP.put(kw, HighlightConfig.KeywordCategory.DML);
        }
        for (String kw : CLAUSE_KEYWORDS) {
            KEYWORD_CATEGORY_MAP.put(kw, HighlightConfig.KeywordCategory.CLAUSE);
        }
        for (String kw : DATA_TYPE_KEYWORDS) {
            KEYWORD_CATEGORY_MAP.put(kw, HighlightConfig.KeywordCategory.DATA_TYPE);
        }
        for (String kw : FUNCTION_KEYWORDS) {
            KEYWORD_CATEGORY_MAP.put(kw, HighlightConfig.KeywordCategory.FUNCTION);
        }
        for (String kw : OPERATOR_KEYWORDS) {
            KEYWORD_CATEGORY_MAP.put(kw, HighlightConfig.KeywordCategory.OPERATOR);
        }
        // 约束关键词归类为DDL
        for (String kw : CONSTRAINT_KEYWORDS) {
            KEYWORD_CATEGORY_MAP.put(kw, HighlightConfig.KeywordCategory.DDL);
        }
    }

    // ==================== 核心方法 ====================

    /**
     * 高亮SQL语句。
     *
     * <p><b>处理流程：</b>
     * <ol>
     *   <li>如果高亮未启用，直接返回原SQL</li>
     *   <li>分割SQL为单词和分隔符</li>
     *   <li>对每个单词判断是否为关键词</li>
     *   <li>如果是关键词，根据类型添加颜色标签</li>
     *   <li>重新组装SQL</li>
     * </ol>
     *
     * @param sql SQL语句
     * @param config 高亮配置
     * @return 高亮后的SQL字符串
     */
    public static String highlight(String sql, HighlightConfig config) {
        // 空值检查
        if (StringUtils.isEmpty(sql)) {
            return sql;
        }

        // 如果高亮未启用，直接返回
        if (config == null || !config.isEnabled()) {
            return sql;
        }

        // 根据高亮类型路由到对应的渲染 sink
        switch (config.getType()) {
            case ANSI:
                return highlightWithSink(sql, HighlightSink.ansi(config));
            case HTML:
                return highlightWithSink(sql, HighlightSink.html(config));
            case NONE:
            default:
                return sql;
        }
    }

    /**
     * 用指定 sink 高亮 SQL：分词逻辑固定（关键字/数字/字面量/注释四类），渲染交给 sink。
     *
     * <p>与 {@link #highlight(String, HighlightConfig)} 的区别：渲染出口完全由调用方
     * 决定（控制台 ANSI、网页 HTML、日志纯文本或自定义方案），不与具体输出格式耦合。</p>
     *
     * @param sql  SQL 文本，不允许为 null
     * @param sink 渲染 sink，不允许为 null
     * @return 高亮后的 SQL 字符串
     */
    public static String highlightWithSink(String sql, HighlightSink sink) {
        if (StringUtils.isEmpty(sql) || sink == null) {
            return sql;
        }

        var result = new StringBuilder(sql.length() + 32);
        var currentToken = new StringBuilder();
        int length = sql.length();
        int i = 0;
        while (i < length) {
            char c = sql.charAt(i);

            // 行注释：-- 到行尾（换行符留给下一轮按普通空白输出）
            if (c == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
                flushToken(result, currentToken, sink);
                int end = SqlFormatter.skipLineComment(sql, i);
                result.append(sink.styleComment(sql.substring(i, end)));
                i = end;
                continue;
            }

            // 块注释：/* 到 */（未闭合保护到串尾）
            if (c == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                flushToken(result, currentToken, sink);
                int end = SqlFormatter.skipBlockComment(sql, i);
                result.append(sink.styleComment(sql.substring(i, end)));
                i = end;
                continue;
            }

            // 字符串字面量/双引号标识符：整个引号区域（含引号）交给 sink
            if (c == '\'' || c == '"') {
                flushToken(result, currentToken, sink);
                int end = SqlFormatter.skipQuoted(sql, i, c);
                result.append(sink.styleLiteral(sql.substring(i, end)));
                i = end;
                continue;
            }

            // 空格或分隔符：结束当前 token
            if (Character.isWhitespace(c) || isDelimiter(c)) {
                flushToken(result, currentToken, sink);
                result.append(c);
                i++;
                continue;
            }

            // 普通字符：累积到当前 token
            currentToken.append(c);
            i++;
        }

        // 处理最后一个 token
        flushToken(result, currentToken, sink);
        return result.toString();
    }

    /**
     * 冲刷当前累积 token：关键字按类别着色，数字按字面量着色，其余原样输出。
     */
    private static void flushToken(StringBuilder result, StringBuilder token, HighlightSink sink) {
        if (token.length() == 0) {
            return;
        }
        String word = token.toString();
        token.setLength(0);
        String upperWord = word.toUpperCase();
        if (ALL_KEYWORDS.contains(upperWord)) {
            result.append(sink.styleToken(word,
                    KEYWORD_CATEGORY_MAP.getOrDefault(upperWord, HighlightConfig.KeywordCategory.CLAUSE)));
        } else if (isNumeric(word)) {
            result.append(sink.styleToken(word, HighlightConfig.KeywordCategory.LITERAL));
        } else {
            result.append(word);
        }
    }

    /**
     * 判断是否为分隔符（括号、逗号、分号等）
     * @param c 方法入参 c
     * @return 是否成功（true 表示成功）
     */
    private static boolean isDelimiter(char c) {
        return c == '(' || c == ')' || c == ',' || c == ';' ||
                c == '=' || c == '<' || c == '>' || c == '+' ||
                c == '-' || c == '*' || c == '/' || c == '.';
    }

    /**
     * 判断是否为数字（整数或小数）
     * @param str 字符串，不允许为 null
     * @return 是否成功（true 表示成功）
     */
    static boolean isNumeric(String str) {
        if (StringUtils.isEmpty(str)) {
            return false;
        }
        try {
            Double.parseDouble(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

}