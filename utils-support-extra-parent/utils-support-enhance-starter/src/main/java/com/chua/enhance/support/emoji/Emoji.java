package com.chua.enhance.support.emoji;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Emoji 数据模型，封装单个 emoji 的 Unicode 编码、别名、HTML 实体和表情符号等信息。
 *
 * @author CH
 * @since 1.0.0
 */
@Data
public class Emoji {

    /**
     * Unicode 编码
     */
    private String unicode;

    /**
     * 别名列表
     */
    private List<String> aliases = new ArrayList<>();

    /**
     * HTML 十六进制实体
     */
    private String hexHtml;

    /**
     * HTML 十进制实体
     */
    private String decimalHtml;

    /**
     * 描述
     */
    private String description;

    /**
     * 表情符号列表
     */
    private List<String> emoticons = new ArrayList<>();

    /**
     * 获取emojichar
     *
     * @return 获取emojichar的结果
     */
    public String getEmojiChar() {
        return unicode;
    }

    /**
     * 获取decimalsurrogatehtml
     *
     * @return 获取decimalsurrogatehtml的结果
     */
    public String getDecimalSurrogateHtml() {
        return getDecimalHtml();
    }

    /**
     * 获取hexhtmlshort
     *
     * @return 获取hexhtmlshort的结果
     */
    public String getHexHtmlShort() {
        return getHexHtml();
    }

    /**
     * 获取decimalhtmlshort
     *
     * @return 获取decimalhtmlshort的结果
     */
    public String getDecimalHtmlShort() {
        return getDecimalHtml();
    }

    /**
     * 获取Emoticons
     *
     * @return 获取emoticons的结果
     */
    public List<String> getEmoticons() {
        return emoticons;
    }

    /**
     * 获取 HTML 十六进制实体
     *
     * @return HTML 十六进制实体字符串
     */
    public String getHexHtml() {
        if (hexHtml != null) {
            return hexHtml;
        }
        if (unicode == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : unicode.toCharArray()) {
            sb.append("&#x").append(Integer.toHexString(c)).append(";");
        }
        hexHtml = sb.toString();
        return hexHtml;
    }

    /**
     * 获取 HTML 十进制实体
     *
     * @return HTML 十进制实体字符串
     */
    public String getDecimalHtml() {
        if (decimalHtml != null) {
            return decimalHtml;
        }
        if (unicode == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : unicode.toCharArray()) {
            sb.append("&#").append((int) c).append(";");
        }
        decimalHtml = sb.toString();
        return decimalHtml;
    }
}
