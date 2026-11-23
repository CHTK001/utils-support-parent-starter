package com.chua.common.support.lang.format;

/**
 * SQL 高亮渲染 sink（可插拔着色出口）。
 *
 * <p>格式化器/高亮器负责<strong>词法切分</strong>（关键字、数字、字面量、注释），
 * sink 只负责<strong>渲染</strong>：把每段 token 变成最终展示文本。这样同一套
 * 分词逻辑可以对接任意输出目标——控制台 ANSI、网页 HTML、日志纯文本，或调用方
 * 自定义的任意着色方案（如按 CSS class 输出）。</p>
 *
 * <p><b>内置实现：</b>
 * <ul>
 *   <li>{@link #plain()}：纯文本，原样返回（日志、文件、非终端场景推荐）</li>
 *   <li>{@link #ansi(HighlightConfig)}：控制台 ANSI 颜色</li>
 *   <li>{@link #html(HighlightConfig)}：网页 span 标签（内容做 HTML 转义）</li>
 *   <li>{@link #defaultSink()}：终端环境用 ANSI，否则纯文本</li>
 * </ul>
 *
 * <p><b>使用示例：</b>
 * <pre>
 * DmlFormatter formatter = new DmlFormatter()
 *         .withMaxLineLength(120)
 *         .withHighlightSink(HighlightSink.plain());
 * String text = formatter.format("select * from t where id = 1");
 * </pre>
 *
 * @author CH
 * @since 4.0.0.51
 */
public interface HighlightSink {

    /**
     * 渲染一个词法 token（关键字或数字字面量）。
     *
     * @param token    token 原文（如 {@code SELECT}、{@code 123}）
     * @param category 关键字类别（数字字面量固定为 {@code LITERAL}）
     * @return 渲染后的文本
     */
    String styleToken(String token, HighlightConfig.KeywordCategory category);

    /**
     * 渲染一个字符串字面量区域（含首尾引号，双写转义保持在区域内）。
     * 默认按 {@code LITERAL} 类别着色。
     *
     * @param literalText 字面量区域原文
     * @return 渲染后的文本
     */
    default String styleLiteral(String literalText) {
        return styleToken(literalText, HighlightConfig.KeywordCategory.LITERAL);
    }

    /**
     * 渲染一个注释区域（行注释不含换行符，块注释含 {@code *}{@code /} 定界符）。
     * 默认按 {@code COMMENT} 类别着色。
     *
     * @param commentText 注释区域原文
     * @return 渲染后的文本
     */
    default String styleComment(String commentText) {
        return styleToken(commentText, HighlightConfig.KeywordCategory.COMMENT);
    }

    /**
     * 纯文本 sink：所有 token 原样返回，不做任何着色。
     *
     * @return 纯文本 sink
     */
    static HighlightSink plain() {
        return (token, category) -> token;
    }

    /**
     * ANSI 控制台 sink：按配置的颜色映射为每个 token 包裹 ANSI 转义序列。
     *
     * @param config 高亮配置（颜色映射来源），不允许为 null
     * @return ANSI sink
     */
    static HighlightSink ansi(HighlightConfig config) {
        return (token, category) -> config.getAnsiColor(category) + token + config.getAnsiReset();
    }

    /**
     * HTML sink：按配置的颜色映射为每个 token 包裹 {@code <span>} 标签，
     * token 内容做 HTML 转义（关键字/数字为字母数字，转义是恒等操作）。
     *
     * @param config 高亮配置（颜色映射来源），不允许为 null
     * @return HTML sink
     */
    static HighlightSink html(HighlightConfig config) {
        return (token, category) -> config.getHtmlColor(category) + escapeHtml(token) + config.getHtmlEnd();
    }

    /**
     * 默认 sink：标准输入/输出连接到真实控制台（TTY）时用 ANSI，否则纯文本。
     *
     * <p>这样格式化结果写入日志文件、被测试断言或被下游程序消费时不会混入
     * 转义序列；只有在人眼直视的终端上才着色。</p>
     *
     * @return 默认 sink
     */
    static HighlightSink defaultSink() {
        return System.console() != null ? ansi(new HighlightConfig()) : plain();
    }

    /**
     * HTML 转义（{@code & < > " '}）。
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
