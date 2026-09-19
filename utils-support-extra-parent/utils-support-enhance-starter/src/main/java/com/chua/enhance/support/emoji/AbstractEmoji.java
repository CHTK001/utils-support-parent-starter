package com.chua.enhance.support.emoji;

import java.util.regex.Pattern;

/**
 * Emoji 抽象基类，提供 short编码 和 HTML 实体的正则模式以及 HTML 转换辅助方法。
 *
 * @author CH
 * @since 1.0.0
 */
public abstract class AbstractEmoji {

    /** Short_编码_模式 */
    protected static final Pattern SHORT_CODE_PATTERN = Pattern.compile(":(\\w+):");
    /** Short_编码_或_HTML_实体_模式 */
    protected static final Pattern SHORT_CODE_OR_HTML_ENTITY_PATTERN = Pattern.compile(":?(\\w+):?|&#?\\w+;");
    /** HTML_surrogate_实体_模式 */
    protected static final Pattern HTML_SURROGATE_ENTITY_PATTERN = Pattern.compile("(?<H>&#x?\\w+;)(?<L>&#x?\\w+;)");
    /** HTML_surrogate_实体_模式_2 */
    protected static final Pattern HTML_SURROGATE_ENTITY_PATTERN_2 = Pattern.compile("(?<H1>&#x?\\w+;)(?<H2>&#x?\\w+;)(?<L1>&#x?\\w+;)(?<L2>&#x?\\w+;)");
    /** HTML_实体_模式 */
    protected static final Pattern HTML_ENTITY_PATTERN = Pattern.compile("&#x?\\w+;");

    /**
    * html助手
    *
    * @param text 文本
    * @param hex hex
    * @param asSurrogate assurrogate
    * @return html助手的结果
    */
    protected static String htmlHelper(String text, boolean hex, boolean asSurrogate) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (asSurrogate) {
                sb.append("&#").append((int) c).append(";");
            } else if (hex) {
                sb.append("&#x").append(Integer.toHexString(c)).append(";");
            } else {
                sb.append("&#").append((int) c).append(";");
            }
        }
        return sb.toString();
    }
}
