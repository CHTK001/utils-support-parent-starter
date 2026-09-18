package com.chua.common.support.lang.format;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
    private static final Set<String> DDL_KEYWORDS = new HashSet<>(Arrays.asList(
            "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME",
            "TABLE", "VIEW", "INDEX", "SEQUENCE", "DATABASE", "SCHEMA"
    ));

    /**
    * DML关键词（数据操作语言）
    */
    private static final Set<String> DML_KEYWORDS = new HashSet<>(Arrays.asList(
            "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE"
    ));

    /**
    * 查询子句关键词
    */
    private static final Set<String> CLAUSE_KEYWORDS = new HashSet<>(Arrays.asList(
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
    private static final Set<String> DATA_TYPE_KEYWORDS = new HashSet<>(Arrays.asList(
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
    private static final Set<String> FUNCTION_KEYWORDS = new HashSet<>(Arrays.asList(
            "COUNT", "SUM", "AVG", "MAX", "MIN",
            "LENGTH", "UPPER", "LOWER", "TRIM", "SUBSTR", "SUBSTRING",
            "NOW", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP",
            "DATE_ADD", "DATE_SUB", "DATEDIFF",
            "IF", "CASE", "WHEN", "THEN", "ELSE", "END"
    ));

    /**
    * 操作符关键词
    */
    private static final Set<String> OPERATOR_KEYWORDS = new HashSet<>(Arrays.asList(
            "IN", "EXISTS", "BETWEEN", "LIKE", "ESCAPE",
            "IS", "DISTINCT", "ALL", "ANY", "SOME"
    ));

    /**
    * 约束关键词
    */
    private static final Set<String> CONSTRAINT_KEYWORDS = new HashSet<>(Arrays.asList(
            "PRIMARY", "KEY", "FOREIGN", "REFERENCES",
            "UNIQUE", "CHECK", "DEFAULT", "CONSTRAINT",
            "CASCADE", "RESTRICT", "NO ACTION", "SET NULL"
    ));

    // ==================== 合并所有关键词 ====================

    /** All_keywords */
    private static final Set<String> ALL_KEYWORDS;
    /** KEYWORD_CATEGORY_MAP */
    private static final Map<String, HighlightConfig.KeywordCategory> KEYWORD_CATEGORY_MAP;

    static {
        ALL_KEYWORDS = new HashSet<>();
        ALL_KEYWORDS.addAll(DDL_KEYWORDS);
        ALL_KEYWORDS.addAll(DML_KEYWORDS);
        ALL_KEYWORDS.addAll(CLAUSE_KEYWORDS);
        ALL_KEYWORDS.addAll(DATA_TYPE_KEYWORDS);
        ALL_KEYWORDS.addAll(FUNCTION_KEYWORDS);
        ALL_KEYWORDS.addAll(OPERATOR_KEYWORDS);
        ALL_KEYWORDS.addAll(CONSTRAINT_KEYWORDS);

        KEYWORD_CATEGORY_MAP = new HashMap<>();
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
        if (sql == null || sql.isEmpty()) {
            return sql;
        }

        // 如果高亮未启用，直接返回
        if (config == null || !config.isEnabled()) {
            return sql;
        }

        // 根据高亮类型执行不同的高亮策略
        switch (config.getType()) {
            case ANSI:
                return highlightAnsi(sql, config);
            case HTML:
                return highlightHtml(sql, config);
            case NONE:
            default:
                return sql;
        }
    }

    /**
    * 使用ANSI颜色高亮（控制台）
    * @param sql SQL，不允许为 null
    * @param config 配置，不允许为 null
    * @return 结果字符串
    */
    private static String highlightAnsi(String sql, HighlightConfig config) {
        return highlightInternal(sql, config, false);
    }

    /**
    * 使用HTML颜色高亮（网页）
    * @param sql SQL，不允许为 null
    * @param config 配置，不允许为 null
    * @return 结果字符串
    */
    private static String highlightHtml(String sql, HighlightConfig config) {
        return highlightInternal(sql, config, true);
    }

    /**
    * 内部高亮实现
    * @param sql SQL，不允许为 null
    * @param config 配置，不允许为 null
    * @param isHtml 是否Html（布尔开关）
    * @return 结果字符串
    */
    private static String highlightInternal(String sql, HighlightConfig config, boolean isHtml) {
        StringBuilder result = new StringBuilder();
        StringBuilder currentToken = new StringBuilder();

        // 状态变量
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        // 标识符状态（如 "table_name"）
        boolean inIdentifier = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            // ----- 处理注释（必须最先处理） -----

            // 行注释 --
            if (!inSingleQuote && !inDoubleQuote && !inBlockComment) {
                if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                    // 遇到 --，进入行注释模式
                    if (currentToken.length() > 0) {
                        result.append(applyHighlight(currentToken.toString(), config, isHtml));
                        currentToken = new StringBuilder();
                    }
                    inLineComment = true;
                    result.append("--");
                    // 跳过第二个 '-'
                    i++;
                    continue;
                }
            }

            // 块注释 /* */
            if (!inSingleQuote && !inDoubleQuote && !inLineComment) {
                if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                    if (currentToken.length() > 0) {
                        result.append(applyHighlight(currentToken.toString(), config, isHtml));
                        currentToken = new StringBuilder();
                    }
                    inBlockComment = true;
                    result.append("/*");
                    // 跳过 '*'
                    i++;
                    continue;
                }
            }

            // 处理注释内容
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    result.append(c);
                    continue;
                }
                // 注释内容使用注释颜色
                if (isHtml) {
                    result.append(escapeHtml(String.valueOf(c)));
                } else {
                    result.append(c);
                }
                continue;
            }

            if (inBlockComment) {
                if (c == '*' && i + 1 < sql.length() && sql.charAt(i + 1) == '/') {
                    result.append("*/");
                    // 跳过 '/'
                    i++;
                    inBlockComment = false;
                    continue;
                }
                if (isHtml) {
                    result.append(escapeHtml(String.valueOf(c)));
                } else {
                    result.append(c);
                }
                continue;
            }

            // ----- 处理字符串 -----

            // 单引号字符串
            if (c == '\'' && !inDoubleQuote && !inLineComment && !inBlockComment) {
                if (!inSingleQuote) {
                    // 进入字符串
                    if (currentToken.length() > 0) {
                        result.append(applyHighlight(currentToken.toString(), config, isHtml));
                        currentToken = new StringBuilder();
                    }
                }
                inSingleQuote = !inSingleQuote;
                // 字符串内容使用字面量颜色
                if (isHtml) {
                    result.append(config.getHtmlColor(HighlightConfig.KeywordCategory.LITERAL))
                            .append("'")
                            .append(config.getHtmlEnd());
                } else {
                    result.append(config.getAnsiColor(HighlightConfig.KeywordCategory.LITERAL))
                            .append("'")
                            .append(config.getAnsiReset());
                }
                continue;
            }

            // 双引号标识符（某些数据库用来引用表名/列名）
            if (c == '"' && !inSingleQuote && !inLineComment && !inBlockComment) {
                if (!inDoubleQuote) {
                    if (currentToken.length() > 0) {
                        result.append(applyHighlight(currentToken.toString(), config, isHtml));
                        currentToken = new StringBuilder();
                    }
                }
                inDoubleQuote = !inDoubleQuote;
                // 标识符使用普通颜色
                result.append(c);
                continue;
            }

            // 如果在字符串内，直接追加
            if (inSingleQuote || inDoubleQuote) {
                // 字符串内容使用字面量颜色
                if (isHtml) {
                    result.append(config.getHtmlColor(HighlightConfig.KeywordCategory.LITERAL))
                            .append(escapeHtml(String.valueOf(c)))
                            .append(config.getHtmlEnd());
                } else {
                    result.append(config.getAnsiColor(HighlightConfig.KeywordCategory.LITERAL))
                            .append(c)
                            .append(config.getAnsiReset());
                }
                continue;
            }

            // ----- 处理普通字符 -----

            // 空格或分隔符：结束当前token
            if (Character.isWhitespace(c) || isDelimiter(c)) {
                if (currentToken.length() > 0) {
                    result.append(applyHighlight(currentToken.toString(), config, isHtml));
                    currentToken = new StringBuilder();
                }
                result.append(c);
                continue;
            }

            // 普通字符：累积到当前token
            currentToken.append(c);
        }

        // 处理最后一个token
        if (!currentToken.isEmpty()) {
            result.append(applyHighlight(currentToken.toString(), config, isHtml));
        }

        return result.toString();
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
    * 对单个Token应用高亮
    * @param token 令牌，不允许为 null
    * @param config 配置，不允许为 null
    * @param isHtml 是否Html（布尔开关）
    * @return 结果字符串
    */
    private static String applyHighlight(String token, HighlightConfig config, boolean isHtml) {
        if (token == null || token.isEmpty()) {
            return token;
        }

        // 判断是否为数字
        if (isNumeric(token)) {
            if (isHtml) {
                return config.getHtmlColor(HighlightConfig.KeywordCategory.LITERAL) +
                        token + config.getHtmlEnd();
            } else {
                return config.getAnsiColor(HighlightConfig.KeywordCategory.LITERAL) +
                        token + config.getAnsiReset();
            }
        }

        // 判断是否为关键词（不区分大小写）
        String upperToken = token.toUpperCase();
        if (ALL_KEYWORDS.contains(upperToken)) {
            HighlightConfig.KeywordCategory category = KEYWORD_CATEGORY_MAP.getOrDefault(
                    upperToken, HighlightConfig.KeywordCategory.CLAUSE
            );

            if (isHtml) {
                return config.getHtmlColor(category) + token + config.getHtmlEnd();
            } else {
                return config.getAnsiColor(category) + token + config.getAnsiReset();
            }
        }

        // 非关键词，直接返回
        return token;
    }

    /**
    * 判断是否为数字（整数或小数）
    * @param str 字符串，不允许为 null
    * @return 是否成功（true 表示成功）
    */
    private static boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        try {
            Double.parseDouble(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
    * HTML转义
    * @param text 文本，不允许为 null
    * @return 结果字符串
    */
    private static String escapeHtml(String text) {
        if (text == null) {
            return null;
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
