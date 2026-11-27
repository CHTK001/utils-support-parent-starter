// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.lang.format;

import com.chua.common.support.collection.wrap.Wraps;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import com.chua.common.support.utils.StringUtils;

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

    /**
     * 最大行宽（字符数），格式化后超过该宽度的行会在字面量/注释之外的安全空格处折行。
     * {@code 0} 表示不折行（默认，保持历史行为）；{@link #MAX_LINE_LENGTH} 为建议值。
     */
    protected int maxLineLength = 0;

    // ==================== 静态初始化块 ====================

    static {
        // 初始化关键字集合
        KEYWORDS = Wraps.set(Arrays.asList(
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
        OPERATORS = Wraps.set(Arrays.asList(
                '=', '<', '>', '+', '-', '*', '/', '(', ')', ',', ';', '.'
        ));
    }
    // ==================== 预编译正则 ====================

    /**
     * 连续空白压缩预编译模式，供各格式化器复用，避免每次格式化重复编译正则。
     */
    protected static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    /**
     * 分号前多余空白预编译模式，避免每次格式化重复编译正则。
     */
    protected static final Pattern SEMICOLON_SPACE_PATTERN = Pattern.compile("\\s+;");

    /**
     * 贴词比较操作符预编译模式，用于在 {@code = < >} 前后补空格。
     */
    protected static final Pattern OPERATOR_PATTERN = Pattern.compile("([^\\s])([<>=])([^\\s])");

    /**
     * 关键字正则缓存：键为不含 {@code (?i)} 前缀的正则体（如 {@code WHERE}），
     * 关键字来自代码内固定词表，缓存有界，同一正则进程内只编译一次。
     */
    private static final Map<String, Pattern> KEYWORD_PATTERNS = new ConcurrentHashMap<>();

    /**
     * 取关键字对应的预编译模式（自动追加 {@code (?i)} 大小写不敏感前缀与
     * {@code \b} 词边界，避免误伤含关键字子串的标识符——如 {@code note} 中的
     * {@code NOT}、{@code version} 中的 {@code ON}、{@code task} 中的 {@code AS}）。
     *
     * @param keywordRegex 关键字正则体，如 {@code WHERE}、{@code GROUP\s+BY}
     * @return 预编译模式
     */
    protected static Pattern keywordPattern(String keywordRegex) {
        return KEYWORD_PATTERNS.computeIfAbsent(keywordRegex,
                k -> Pattern.compile("(?i)\\b" + k + "\\b"));
    }

    /**
     * 用预编译关键字模式做大小写不敏感替换，替换值为 {@link #formatKeyword(String)} 规范化结果。
     *
     * @param input        原始文本
     * @param keywordRegex 关键字正则体，如 {@code WHERE}、{@code GROUP\s+BY}
     * @param keyword      规范关键字，如 {@code GROUP BY}
     * @return 替换后的文本
     */
    protected String replaceKeyword(String input, String keywordRegex, String keyword) {
        return replaceWithPattern(input, keywordRegex, formatKeyword(keyword));
    }

    /**
     * 用预编译关键字模式做大小写不敏感替换，替换串由调用方显式给定。
     *
     * <p>与 {@link #replaceKeyword(String, String, String)} 的区别：替换串不经
     * {@link #formatKeyword(String)} 规范化，用于「前后补空格」「换行缩进」等
     * 非纯关键字规范化场景。</p>
     *
     * @param input        原始文本
     * @param keywordRegex 关键字正则体（自动追加 {@code (?i)} 前缀）
     * @param replacement  替换串（可含分组引用 {@code $1} 等）
     * @return 替换后的文本
     */
    protected String replaceWithPattern(String input, String keywordRegex, String replacement) {
        return replaceOutsideLiterals(input, keywordPattern(keywordRegex), replacement);
    }

    /**
     * 词法感知替换：跳过字符串字面量（单引号/双引号/反引号）与注释（行/块）后，
     * 仅在普通 SQL 片段上执行正则替换。
     *
     * <p>避免把 {@code WHERE name = 'where clause'} 中字符串字面量里的关键字
     * 误当作 SQL 关键字改写（会破坏字面量内容甚至产出非法 SQL）。</p>
     *
     * @param input       原始文本
     * @param pattern     预编译模式
     * @param replacement 替换串
     * @return 替换后的文本
     */
    private static String replaceOutsideLiterals(String input, Pattern pattern, String replacement) {
        int length = input.length();
        StringBuilder out = new StringBuilder(length + 16);
        int segStart = 0;
        int i = 0;
        while (i < length) {
            char c = input.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int end = skipQuoted(input, i, c);
                appendReplaced(out, input, segStart, i, pattern, replacement);
                out.append(input, i, end);
                segStart = end;
                i = end;
            } else if (c == '-' && i + 1 < length && input.charAt(i + 1) == '-') {
                int end = skipLineComment(input, i);
                appendReplaced(out, input, segStart, i, pattern, replacement);
                out.append(input, i, end);
                segStart = end;
                i = end;
            } else if (c == '/' && i + 1 < length && input.charAt(i + 1) == '*') {
                int end = skipBlockComment(input, i);
                appendReplaced(out, input, segStart, i, pattern, replacement);
                out.append(input, i, end);
                segStart = end;
                i = end;
            } else {
                i++;
            }
        }
        appendReplaced(out, input, segStart, length, pattern, replacement);
        return out.toString();
    }

    /**
     * 对 {@code [start, end)} 区间执行替换并追加到结果。
     */
    private static void appendReplaced(StringBuilder out, String input, int start, int end,
                                       Pattern pattern, String replacement) {
        if (start >= end) {
            return;
        }
        // 注意：不能对整个 input 设 region(start, end) 后 replaceAll——
        // Matcher.replaceAll 未命中时返回整个 input、命中时 appendTail 追加到全文末尾，
        // 会把区间之外的文本重复带入结果，多段叠加即指数膨胀。这里只对切片做替换。
        out.append(pattern.matcher(input.substring(start, end)).replaceAll(replacement));
    }

    /**
     * 生成词法掩码：标记输入中处于字符串字面量（单引号/双引号/反引号）与
     * 注释（行/块）内部的字符位置，供分词/切分类逻辑跳过这些区域，
     * 避免把字面量或注释中的单词误当 SQL 关键字。
     *
     * @param input 原始文本
     * @return 与输入等长的掩码数组，{@code true} 表示该位置字符位于字面量或注释内
     */
    protected static boolean[] maskLiteralsAndComments(String input) {
        int length = input.length();
        boolean[] mask = new boolean[length];
        int i = 0;
        while (i < length) {
            char c = input.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int end = skipQuoted(input, i, c);
                maskRange(mask, i, end);
                i = end;
            } else if (c == '-' && i + 1 < length && input.charAt(i + 1) == '-') {
                int end = skipLineComment(input, i);
                maskRange(mask, i, end);
                i = end;
            } else if (c == '/' && i + 1 < length && input.charAt(i + 1) == '*') {
                int end = skipBlockComment(input, i);
                maskRange(mask, i, end);
                i = end;
            } else {
                i++;
            }
        }
        return mask;
    }

    /**
     * 将 {@code [start, end)} 区间的掩码位置置真。
     */
    private static void maskRange(boolean[] mask, int start, int end) {
        for (int i = start; i < end; i++) {
            mask[i] = true;
        }
    }

    /**
     * 返回引号区间的结束位置（不含）。支持双写转义（{@code ''}/{@code ""}/反引号）
     * 与反斜杠转义（MySQL 风格）；未闭合引号保守保护到串尾。
     */
    static int skipQuoted(String input, int start, char quote) {
        int i = start + 1;
        int length = input.length();
        while (i < length) {
            char c = input.charAt(i);
            if (c == '\\' && i + 1 < length) {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < length && input.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return length;
    }

    /**
     * 返回行注释的结束位置（不含换行符本身，换行符留给下一段）。
     */
    static int skipLineComment(String input, int start) {
        int i = start + 2;
        int length = input.length();
        while (i < length) {
            char c = input.charAt(i);
            if (c == '\n' || c == '\r') {
                return i;
            }
            i++;
        }
        return length;
    }

    /**
     * 返回块注释的结束位置（含 {@code *}{@code /}）；未闭合则保护到串尾。
     */
    static int skipBlockComment(String input, int start) {
        int end = input.indexOf("*/", start + 2);
        return end < 0 ? input.length() : end + 2;
    }

    /**
     * Highlight配置。默认按终端环境决定是否启用：控制台（TTY）启用 ANSI 着色，
     * 非终端环境（日志/文件/测试）关闭着色，输出纯文本。经 {@link #withHighlight} 显式设置后以显式值为准。
     */
    protected HighlightConfig highlightConfig = defaultHighlightConfig();

    /**
     * 显式注入的高亮 sink（可插拔渲染出口）；非 null 时优先于 {@link #highlightConfig} 生效。
     */
    protected HighlightSink highlightSink;

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
     * @param config 配置，不允许为 null
     * @return SQLFormatter 对象
     */
    public SqlFormatter withHighlight(HighlightConfig config) {
        this.highlightConfig = config;
        this.highlightSink = null;
        return this;
    }

    /**
     * 注入自定义高亮 sink（可插拔渲染出口），优先级高于 {@link #withHighlight(HighlightConfig)}。
     * 传入 {@code null} 恢复为按 HighlightConfig / 终端环境的默认行为。
     *
     * @param sink 高亮 sink
     * @return 当前对象
     */
    public SqlFormatter withHighlightSink(HighlightSink sink) {
        this.highlightSink = sink;
        return this;
    }

    /**
     * 设置最大行宽（字符数）：格式化后超过该宽度的行会在安全空格处折行，
     * 续行在原行缩进基础上追加一级缩进。{@code maxLineLength <= 0} 表示不折行。
     *
     * @param maxLineLength 最大行宽，{@code <=0} 关闭折行
     * @return 当前对象
     */
    public SqlFormatter withMaxLineLength(int maxLineLength) {
        this.maxLineLength = maxLineLength;
        return this;
    }

    /**
     * 默认高亮配置：非终端环境关闭高亮（纯文本输出），终端环境启用 ANSI。
     */
    static HighlightConfig defaultHighlightConfig() {
        return new HighlightConfig().setEnabled(System.console() != null);
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
            trimmed = WHITESPACE_PATTERN.matcher(trimmed).replaceAll(" ");
        }

        // 委托给子类实现具体的格式化逻辑
        String formatted = doFormat(trimmed);
        // 折行在着色之前进行，避免高亮转义序列干扰行宽计算
        formatted = wrapLines(formatted);
        return applyHighlight(formatted);
    }

    /**
     * 应用高亮：显式 sink 优先，其次显式/默认 HighlightConfig（终端感知）。
     */
    private String applyHighlight(String sql) {
        HighlightSink sink = highlightSink != null ? highlightSink : sinkOf(highlightConfig);
        return sink == null ? sql : SqlHighlighter.highlightWithSink(sql, sink);
    }

    /**
     * 把 HighlightConfig 适配为 sink；未启用或类型为 {@code NONE} 时返回 {@code null}（不着色）。
     */
    private static HighlightSink sinkOf(HighlightConfig config) {
        if (config == null || !config.isEnabled()) {
            return null;
        }
        switch (config.getType()) {
            case ANSI:
                return HighlightSink.ansi(config);
            case HTML:
                return HighlightSink.html(config);
            case NONE:
            default:
                return null;
        }
    }

    /**
     * 按 {@link #maxLineLength} 对格式化结果折行。仅在字面量/注释之外的安全空格处断开；
     * 找不到安全断点（如单个超长字面量）时整行保留，绝不截断字面量。
     */
    protected String wrapLines(String formatted) {
        if (maxLineLength <= 0 || StringUtils.isEmpty(formatted)) {
            return formatted;
        }
        String[] lines = formatted.split("\n", -1);
        StringBuilder out = new StringBuilder(formatted.length() + 32);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            if (lines[i].length() <= maxLineLength) {
                out.append(lines[i]);
            } else {
                wrapSingleLine(out, lines[i]);
            }
        }
        return out.toString();
    }

    /**
     * 折行单行：在限宽内最后一个未掩码空格处断开，续行在原缩进上追加一级缩进。
     */
    private void wrapSingleLine(StringBuilder out, String line) {
        int lead = 0;
        while (lead < line.length() && Character.isWhitespace(line.charAt(lead))) {
            lead++;
        }
        String continuationIndent = line.substring(0, lead) + " ".repeat(INDENT_SIZE);
        boolean[] masked = maskLiteralsAndComments(line);
        int start = 0;
        int length = line.length();
        while (length - start > maxLineLength) {
            int limit = Math.min(length, start + maxLineLength);
            int breakAt = -1;
            for (int i = limit; i > start; i--) {
                // 断点必须是段内（非段首）的未掩码空格，避免把缩进或字面量拆开
                if (i - 1 > start && line.charAt(i - 1) == ' ' && !masked[i - 1]) {
                    breakAt = i;
                    break;
                }
            }
            // 限宽内无安全断点（如超长字面量）：整段保留，避免破坏字面量
            if (breakAt <= start) {
                break;
            }
            // 断点处的空格吞掉，不带入下一行
            out.append(line, start, breakAt - 1);
            out.append('\n').append(continuationIndent);
            start = breakAt;
        }
        out.append(line, start, length);
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
        // 防御性检查：如果 {@code word} 为 null 或空，直接返回
        if (StringUtils.isEmpty(word)) {
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
        if (StringUtils.isEmpty(word)) {
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
        List<String> parts = Wraps.list();
        if (StringUtils.isEmpty(text)) {
            return parts;
        }

        var current = new StringBuilder();
        // 当前括号嵌套深度
        int parenDepth = 0;
        // 是否在单引号字符串内
        boolean inSingleQuote = false;
        // 是否在双引号字符串内
        boolean inDoubleQuote = false;

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
        if (StringUtils.isEmpty(sql)) {
            return sql;
        }

        // 1. 去除首尾空白
        String result = sql.trim();

        // 2. 压缩空白（但保留SQL注释）
        if (compressWhitespace) {
            // 使用正则替换连续的空白字符为单个空格
            // 注意：这里的正则不会影响字符串字面量中的空白
            result = WHITESPACE_PATTERN.matcher(result).replaceAll(" ");
        }

        // 3. 处理分号：确保分号前没有多余空格
        result = SEMICOLON_SPACE_PATTERN.matcher(result).replaceAll(";");

        // 4. 确保操作符前后有空格（对易读性有帮助）
        // 注意：这里简化为在 =, <, > 等操作符周围添加空格
        result = OPERATOR_PATTERN.matcher(result).replaceAll("$1 $2 $3");

        return result;
    }
}
