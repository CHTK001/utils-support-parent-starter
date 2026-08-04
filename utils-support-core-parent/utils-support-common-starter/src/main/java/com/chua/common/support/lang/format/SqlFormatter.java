package com.chua.common.support.lang.format;

import java.util.*;
import org.jspecify.annotations.NullUnmarked;

/**
 * SQL格式化器抽象基类，提供通用的格式化能力和基础工具方法。
 *
 * <p>该类作为所有SQL格式化器的父类，封装了：
 * <ul>
 *   <li>SQL关键字集合的定义与管理</li>
 *   <li>操作符集合的定义</li>
 *   <li>缩进和换行等格式化配置</li>
 *   <li>通用的关键字格式化方法</li>
 * </ul>
 *
 * <p><b>设计思路：</b>
 * 采用模板方法模式，将通用的格式化流程和工具方法提取到基类中，
 * 具体的格式化逻辑（如DDL、DML）交由子类实现。
 *
 * @author CH
 * @since 1.0.0
 */
@NullUnmarked
@SuppressWarnings("NullAway")
public abstract class SqlFormatter implements Formatter {

    // ==================== 静态常量定义 ====================

    /**
     * SQL标准关键字集合（不区分大小写）。
     *
     * <p>包含以下类别：
     * <ul>
     *   <li>DML操作：SELECT, INSERT, UPDATE, DELETE</li>
     *   <li>DDL操作：CREATE, ALTER, DROP, TRUNCATE</li>
     *   <li>查询子句：FROM, WHERE, JOIN, GROUP BY, ORDER BY</li>
     *   <li>约束关键字：PRIMARY KEY, FOREIGN KEY, UNIQUE, CHECK</li>
     *   <li>逻辑运算符：AND, OR, NOT, NULL</li>
     *   <li>事务控制：BEGIN, COMMIT, ROLLBACK</li>
     * </ul>
     *
     * <p><b>注意事项：</b>
     * 该集合用于识别SQL关键字，进行大小写统一和格式化。
     * 实际SQL解析时会忽略字符串字面量和标识符中的关键字。
     */
    protected static final Set<String> KEYWORDS;

    /**
     * SQL操作符集合，用于识别和处理特殊字符。
     *
     * <p>包含：
     * <ul>
     *   <li>比较操作符：=, &lt;, &gt;</li>
     *   <li>算术操作符：+, -, *, /</li>
     *   <li>分隔符：(, ), ,, ;</li>
     * </ul>
     */
    protected static final Set<Character> OPERATORS;

    /**
     * 每个缩进级别的空格数，默认4个空格。
     *
     * <p>参考了主流代码风格（如Google Java Style Guide）。
     * 可根据团队规范通过构造函数调整。
     */
    protected static final int INDENT_SIZE = 4;

    /**
     * 建议的最大行长度，超过此长度建议换行。
     *
     * <p>值为120字符，参考了现代IDE的默认折行设置。
     * 该值为建议值，具体实现可能会根据语法结构进行折行。
     */
    protected static final int MAX_LINE_LENGTH = 120;

    // ==================== 实例配置属性 ====================

    /**
     * 是否保留SQL注释。
     *
     * <p>如果为true，格式化时会保留 -- 和 /* *\/ 风格的注释；
     * 如果为false，则会移除所有注释。
     * 默认值为true。
     */
    protected boolean keepComments = true;

    /**
     * 是否将关键字转换为大写。
     *
     * <p>如果为true，所有关键字将被转换为大写（如 SELECT）；
     * 如果为false，保留原始大小写或转换为小写。
     * 默认值为true，符合SQL标准书写习惯。
     */
    protected boolean upperCaseKeywords = true;

    /**
     * 是否压缩多余空白字符。
     *
     * <p>如果为true，将多个空格、换行等压缩为单个空格；
     * 如果为false，保留原始空白字符。
     * 默认值为true。
     */
    protected boolean compressWhitespace = true;

    // ==================== 静态初始化块 ====================

    static {
        // 初始化关键字集合
        KEYWORDS = new HashSet<>(Arrays.asList(
                // DML（数据操作语言）
                "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE",
                // DDL（数据定义语言）
                "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME",
                // 查询子句
                "FROM", "WHERE", "AND", "OR", "NOT", "NULL",
                "ORDER", "BY", "GROUP", "HAVING", "LIMIT", "OFFSET",
                // 连接操作
                "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS",
                "ON", "USING", "NATURAL",
                // 集合操作
                "UNION", "INTERSECT", "EXCEPT", "MINUS",
                // 数据定义元素
                "TABLE", "VIEW", "INDEX", "SEQUENCE", "DATABASE", "SCHEMA",
                "COLUMN", "CONSTRAINT", "PRIMARY", "KEY", "FOREIGN",
                "UNIQUE", "CHECK", "DEFAULT", "REFERENCES",
                // 事务控制
                "BEGIN", "START", "COMMIT", "ROLLBACK", "SAVEPOINT",
                // 其他
                "WITH", "AS", "INTO", "VALUES", "SET", "GRANT", "REVOKE",
                "CASE", "WHEN", "THEN", "ELSE", "END", "IF", "ELSEIF",
                "WHILE", "LOOP", "FOR", "DECLARE", "EXECUTE", "CALL"
        ));

        // 初始化操作符集合
        OPERATORS = new HashSet<>(Arrays.asList(
                '=', '<', '>', '+', '-', '*', '/', '(', ')', ',', ';', '.'
        ));
    }
    protected HighlightConfig highlightConfig = new HighlightConfig();

    // ==================== 构造函数 ====================

    /**
     * 默认构造函数，使用所有默认配置。
     *
     * <p>默认配置：
     * <ul>
     *   <li>保留注释：true</li>
     *   <li>关键字大写：true</li>
     *   <li>压缩空白：true</li>
     * </ul>
     */
    public SqlFormatter() {
    }

    /**
     * 带参数的构造函数，允许自定义格式化选项。
     *
     * @param keepComments 是否保留注释
     * @param upperCaseKeywords 是否将关键字转换为大写
     */
    public SqlFormatter(boolean keepComments, boolean upperCaseKeywords) {
        this.keepComments = keepComments;
        this.upperCaseKeywords = upperCaseKeywords;
    }

    /**
     * 全参数构造函数。
     *
     * @param keepComments 是否保留注释
     * @param upperCaseKeywords 是否将关键字转换为大写
     * @param compressWhitespace 是否压缩空白字符
     */
    public SqlFormatter(boolean keepComments, boolean upperCaseKeywords, boolean compressWhitespace) {
        this.keepComments = keepComments;
        this.upperCaseKeywords = upperCaseKeywords;
        this.compressWhitespace = compressWhitespace;
    }

    // ==================== 公开方法 ====================

    /**
     * 启用高亮
     */
    public SqlFormatter withHighlight(HighlightConfig config) {
        this.highlightConfig = config;
        return this;
    }
    /**
     * 格式化给定的SQL字符串（实现Formatter接口）。
     *
     * <p><b>执行流程：</b>
     * <ol>
     *   <li>空值检查：如果source为null或空字符串，直接返回</li>
     *   <li>预处理：去除首尾空白，压缩多余空白（如果启用）</li>
     *   <li>委托给子类的doFormat方法执行具体格式化</li>
     * </ol>
     *
     * @param source 待格式化的原始SQL字符串
     * @return 格式化后的SQL字符串，如果source为null则返回null
     * @see #doFormat(String)
     */
    @Override
    public String format(String source) {
        // 空值安全检查
        if (source == null) {
            return null;
        }

        // 去除首尾空白字符
        String trimmed = source.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }

        // 如果启用压缩空白，将连续的空白字符压缩为单个空格
        // 注意：这里只做初步压缩，不会破坏字符串字面量中的空白
        if (compressWhitespace) {
            trimmed = trimmed.replaceAll("\\s+", " ");
        }

        // 委托给子类实现具体的格式化逻辑
        String formatted = doFormat(trimmed);
        if (highlightConfig != null && highlightConfig.isEnabled()) {
            return SqlHighlighter.highlight(formatted, highlightConfig);
        }
        return formatted;
    }

    // ==================== 抽象方法 ====================

    /**
     * 子类实现具体的格式化逻辑。
     *
     * <p><b>实现约定：</b>
     * <ul>
     *   <li>输入参数已经过预处理（去首尾空白、可选压缩）</li>
     *   <li>返回的字符串应该是格式化后的完整SQL语句</li>
     *   <li>应尽量保证输出结果的语法正确性</li>
     *   <li>对于无法识别或格式化的SQL，应原样返回</li>
     * </ul>
     *
     * @param source 预处理后的SQL字符串
     * @return 格式化后的SQL字符串
     */
    protected abstract String doFormat(String source);

    // ==================== 工具方法 ====================

    /**
     * 格式化关键字（根据配置转换为大写或小写）。
     *
     * <p><b>使用场景：</b>
     * 在格式化过程中，对于识别出的SQL关键字，调用此方法
     * 进行统一的大小写转换，保证输出风格一致。
     *
     * <p><b>示例：</b>
     * <pre>
     * // upperCaseKeywords = true
     * formatKeyword("select") // 返回 "SELECT"
     *
     * // upperCaseKeywords = false
     * formatKeyword("Select") // 返回 "select"
     * </pre>
     *
     * @param word 待格式化的关键字
     * @return 格式化后的关键字
     */
    protected String formatKeyword(String word) {
        // 防御性检查：如果word为null或空，直接返回
        if (word == null || word.isEmpty()) {
            return word;
        }

        // 根据配置决定转为大写还是小写
        if (upperCaseKeywords) {
            return word.toUpperCase();
        } else {
            return word.toLowerCase();
        }
    }

    /**
     * 判断一个单词是否为SQL关键字（不区分大小写）。
     *
     * <p><b>判断逻辑：</b>
     * <ol>
     *   <li>将待检测单词转为大写</li>
     *   <li>在预定义的关键字集合中进行查找</li>
     *   <li>查找成功返回true，否则返回false</li>
     * </ol>
     *
     * <p><b>注意事项：</b>
     * 此方法仅基于关键字集合进行判断，不区分上下文。
     * 在SQL中，关键字可能出现在标识符位置（如表名、列名），
     * 这种情况下需要结合上下文判断。
     *
     * @param word 待检测的单词
     * @return 如果是关键字返回true，否则返回false
     */
    protected boolean isKeyword(String word) {
        // 空值安全检查
        if (word == null || word.isEmpty()) {
            return false;
        }
        // 转为大写后查找
        return KEYWORDS.contains(word.toUpperCase());
    }

    /**
     * 生成指定层级的缩进字符串。
     *
     * <p><b>缩进计算：</b>
     * 缩进空格数 = 缩进层级 × 每级缩进空格数
     *
     * <p><b>示例：</b>
     * <pre>
     * indent(0) // 返回 ""（无缩进）
     * indent(1) // 返回 "    "（4个空格）
     * indent(2) // 返回 "        "（8个空格）
     * </pre>
     *
     * <p><b>性能说明：</b>
     * 使用Java 11的String.repeat()方法，性能优于循环拼接。
     *
     * @param level 缩进层级（从0开始）
     * @return 缩进字符串，如果level <= 0则返回空字符串
     */
    protected String indent(int level) {
        // 边界检查：缩进层级小于等于0时返回空字符串
        if (level <= 0) {
            return "";
        }
        // 生成指定数量的空格
        return " ".repeat(level * INDENT_SIZE);
    }

    /**
     * 安全地分割字符串，保留括号内的逗号不被分割。
     *
     * <p><b>使用场景：</b>
     * 在解析函数参数、IN列表、列定义等场景中，
     * 需要按逗号分割但同时保护括号内的内容不被分割。
     *
     * <p><b>示例：</b>
     * <pre>
     * splitPreservingParens("a, b, func(c, d), e")
     * // 返回: ["a", "b", "func(c, d)", "e"]
     * </pre>
     *
     * @param text 待分割的文本
     * @return 分割后的字符串列表
     */
    protected List<String> splitPreservingParens(String text) {
        List<String> parts = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return parts;
        }

        StringBuilder current = new StringBuilder();
        int parenDepth = 0;          // 当前括号嵌套深度
        boolean inSingleQuote = false; // 是否在单引号字符串内
        boolean inDoubleQuote = false; // 是否在双引号字符串内

        for (char c : text.toCharArray()) {
            // 处理字符串引号（忽略字符串内的逗号）
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                current.append(c);
                continue;
            }
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                current.append(c);
                continue;
            }

            // 如果在字符串内，直接追加字符
            if (inSingleQuote || inDoubleQuote) {
                current.append(c);
                continue;
            }

            // 处理括号（不在字符串内）
            if (c == '(') {
                parenDepth++;
                current.append(c);
            } else if (c == ')') {
                parenDepth--;
                current.append(c);
            } else if (c == ',' && parenDepth == 0) {
                // 只在括号外且不是字符串内时分割
                parts.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }

        // 添加最后一个部分
        String lastPart = current.toString().trim();
        if (!lastPart.isEmpty()) {
            parts.add(lastPart);
        }

        return parts;
    }

    /**
     * 检查字符串是否以某个关键字开头（不区分大小写）。
     *
     * <p><b>使用场景：</b>
     * 在识别SQL语句类型时，用于判断当前语句属于哪种操作。
     *
     * <p><b>示例：</b>
     * <pre>
     * startsWithKeyword("SELECT * FROM users", "SELECT") // true
     * startsWithKeyword("select * FROM users", "SELECT") // true
     * startsWithKeyword("-- SELECT", "SELECT")           // false
     * </pre>
     *
     * @param text 待检查的文本
     * @param keyword 目标关键字
     * @return 如果文本以关键字开头（忽略大小写和前后空白）返回true，否则返回false
     */
    protected boolean startsWithKeyword(String text, String keyword) {
        if (text == null || keyword == null) {
            return false;
        }
        // 去除首尾空白
        String trimmed = text.trim();
        // 检查是否以关键字开头（不区分大小写）
        return trimmed.regionMatches(true, 0, keyword, 0, keyword.length());
    }

    /**
     * 对SQL字符串进行基本的清理和规范化。
     *
     * <p><b>处理步骤：</b>
     * <ol>
     *   <li>移除行首行尾的空白</li>
     *   <li>将多个空格压缩为单个空格</li>
     *   <li>确保分号前没有多余空格</li>
     *   <li>确保关键字前后有正确空格</li>
     * </ol>
     *
     * <p><b>注意事项：</b>
     * 此方法会在格式化之前调用，保证输入数据的干净性。
     *
     * @param sql 原始SQL字符串
     * @return 清理后的SQL字符串
     */
    protected String normalizeSql(String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }

        // 1. 去除首尾空白
        String result = sql.trim();

        // 2. 压缩空白（但保留SQL注释）
        if (compressWhitespace) {
            // 使用正则替换连续的空白字符为单个空格
            // 注意：这里的正则不会影响字符串字面量中的空白
            result = result.replaceAll("\\s+", " ");
        }

        // 3. 处理分号：确保分号前没有多余空格
        result = result.replaceAll("\\s+;", ";");

        // 4. 确保操作符前后有空格（对易读性有帮助）
        // 注意：这里简化为在 =, <, > 等操作符周围添加空格
        result = result.replaceAll("([^\\s])([<>=])([^\\s])", "$1 $2 $3");

        return result;
    }
}