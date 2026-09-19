package com.chua.enhance.support.emoji;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Emoji 工具类，提供 emoji 的查找、解析、转换和统计功能。
 *
 * <p>主要功能：</p>
 * <ul>
 *   <li>{@link #getEmoji(String)} / {@link #getForAlias(String)} — 按编码或别名查找 emoji</li>
 *   <li>{@link #isEmoji(String)} — 判断是否为有效 emoji</li>
 *   <li>{@link #emojify(String)} — 将短码/HTML 实体还原为 emoji 字符</li>
 *   <li>{@link #htmlify(String)} — 将 emoji 转换为 HTML 实体</li>
 *   <li>{@link #removeAllEmojis(String)} — 移除文本中的所有 emoji</li>
 * </ul>
 *
 * @author CH
 * @since 1.0.0
 */
public class EmojiUtils extends AbstractEmoji {

    /**
     * 按编码查找 emoji（支持 unicode、shortcode、HTML 实体）
     *
     * @param code 编码字符串
     * @return Emoji 对象，未找到返回 空
     */
    public static Emoji getEmoji(String code) {
        Matcher m = SHORT_CODE_PATTERN.matcher(code);
        if (m.find()) {
            code = m.group(1);
        }

        String fCode = code;
        Optional<Emoji> first = EmojiManager.data()
                .stream()
                .filter(it -> {
                    boolean b = fCode.equals(it.getEmojiChar())
                            || fCode.equalsIgnoreCase(it.getHexHtml())
                            || fCode.equalsIgnoreCase(it.getDecimalHtml())
                            || fCode.equalsIgnoreCase(it.getDecimalSurrogateHtml())
                            || fCode.equalsIgnoreCase(it.getHexHtmlShort())
                            || fCode.equalsIgnoreCase(it.getDecimalHtmlShort())
                            || null != it.getAliases() && it.getAliases().contains(fCode)
                            || null != it.getEmoticons() && it.getEmoticons().contains(fCode);

                    return b;
                }).findFirst();

        return first.isPresent() ? first.get() : null;
    }

    /**
     * 按别名查找 emoji
     *
     * @param alias 别名
     * @return Emoji 对象，未找到返回 空
     */
    public static Emoji getForAlias(String alias) {
        if (alias == null || alias.isEmpty()) {
            return null;
        }
        return EmojiManager.EMOJIS_BY_ALIAS.get(trimAlias(alias));
    }

    /**
     * 去空格别名
     *
     * @param alias 别名
     * @return 修剪别名的结果
     */
    private static String trimAlias(String alias) {
        int len = alias.length();
        return alias.substring(
                alias.charAt(0) == ':' ? 1 : 0,
                alias.charAt(len - 1) == ':' ? len - 1 : len);
    }

    /**
     * 判断是否为有效 emoji
     *
     * @param code 编码字符串
     * @return 是否为 emoji
     */
    public static boolean isEmoji(String code) {
        return getEmoji(code) != null;
    }

    /**
     * 将短码/HTML 实体还原为 emoji 字符
     *
     * @param text 待解析文本
     * @return 还原后的文本
     */
    public static String emojify(String text) {
        return emojify(text, 0);
    }

    /**
     * Emojify
     *
     * @param text 文本
     * @param startIndex 启动索引
     * @return emojify的结果
     */
    private static String emojify(String text, int startIndex) {
        text = processStringWithRegex(text, SHORT_CODE_OR_HTML_ENTITY_PATTERN, startIndex, true);
        text = processStringWithRegex(text, EmojiManager.getEmoticonRegexPattern(), startIndex, true);
        return text;
    }

    /**
     * 处理字符串withregex
     *
     * @param text 文本
     * @param pattern 模式
     * @param startIndex 启动索引
     * @param recurseEmojify recurseemojify
     * @return 处理字符串withregex的结果
     */
    private static String processStringWithRegex(String text, Pattern pattern, int startIndex, boolean recurseEmojify) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        int resetIndex = 0;

        if (startIndex > 0) {
            matcher.region(startIndex, text.length());
        }

        while (matcher.find()) {
            String emojiCode = matcher.group();
            Emoji emoji = getEmoji(emojiCode);

            if (emoji != null) {
                matcher.appendReplacement(sb, emoji.getEmojiChar());
            } else {
                if (HTML_SURROGATE_ENTITY_PATTERN_2.matcher(emojiCode).matches()) {
                    String highSurrogate1 = matcher.group("H1");
                    String highSurrogate2 = matcher.group("H2");
                    String lowSurrogate1 = matcher.group("L1");
                    String lowSurrogate2 = matcher.group("L2");
                    matcher.appendReplacement(sb, processStringWithRegex(highSurrogate1 + highSurrogate2, SHORT_CODE_OR_HTML_ENTITY_PATTERN, 0, false));

                    if (sb.toString().endsWith(highSurrogate2)) {
                        resetIndex = sb.length() - highSurrogate2.length();
                    } else {
                        resetIndex = sb.length();
                    }
                    sb.append(lowSurrogate1);
                    sb.append(lowSurrogate2);
                    break;
                } else if (HTML_SURROGATE_ENTITY_PATTERN.matcher(emojiCode).matches()) {
                    String highSurrogate = matcher.group("H");
                    String lowSurrogate = matcher.group("L");
                    matcher.appendReplacement(sb, processStringWithRegex(highSurrogate, HTML_ENTITY_PATTERN, 0, true));
                    resetIndex = sb.length();
                    sb.append(lowSurrogate);
                    break;
                } else {
                    matcher.appendReplacement(sb, emojiCode);
                }
            }
        }
        matcher.appendTail(sb);

        if (recurseEmojify && resetIndex > 0) {
            return emojify(sb.toString(), resetIndex);
        }
        return sb.toString();
    }

    /**
     * 统计文本中 emoji 数量
     *
     * @param text 待统计文本
     * @return emoji 数量
     */
    public static int countEmojis(String text) {
        String htmlifiedText = htmlify(text);
        Matcher matcher = HTML_ENTITY_PATTERN.matcher(htmlifiedText);

        int counter = 0;
        while (matcher.find()) {
            String emojiCode = matcher.group();
            if (isEmoji(emojiCode)) {
                counter++;
            }
        }
        return counter;
    }

    /**
     * 将 emoji 转换为 HTML 十进制实体
     *
     * @param text 输入文本
     * @return HTML 实体文本
     */
    public static String htmlify(String text) {
        String emojifiedStr = emojify(text);
        return htmlHelper(emojifiedStr, false, false);
    }

    /**
     * 将 emoji 转换为 HTML 十进制实体（支持代理对）
     *
     * @param text        输入文本
     * @param asSurrogate 是否使用代理对
     * @return HTML 实体文本
     */
    public static String htmlify(String text, boolean asSurrogate) {
        String emojifiedStr = emojify(text);
        return htmlHelper(emojifiedStr, false, asSurrogate);
    }

    /**
     * 将 emoji 转换为 HTML 十六进制实体
     *
     * @param text 输入文本
     * @return 十六进制 HTML 实体文本
     */
    public static String hexHtmlify(String text) {
        String emojifiedStr = emojify(text);
        return htmlHelper(emojifiedStr, true, false);
    }

    /**
     * 将 emoji 转换为短码
     *
     * @param text 输入文本
     * @return 短码文本
     */
    public static String shortCodify(String text) {
        String emojifiedText = emojify(text);

        for (Emoji emoji : EmojiManager.data()) {
            StringBuilder shortCodeBuilder = new StringBuilder();
            shortCodeBuilder.append(":").append(emoji.getAliases().getFirst()).append(":");

            emojifiedText = emojifiedText.replace(emoji.getEmojiChar(), shortCodeBuilder.toString());
        }
        return emojifiedText;
    }

    /**
     * 移除文本中的所有 emoji 字符
     *
     * @param emojiText 输入文本
     * @return 移除 emoji 后的文本
     */
    public static String removeAllEmojis(String emojiText) {
        for (Emoji emoji : EmojiManager.data()) {
            emojiText = emojiText.replace(emoji.getEmojiChar(), "");
        }
        return emojiText;
    }
}
