package com.chua.common.support.lang.process;


/**
 * 字符串显示工具类，提供字符串显示宽度计算和截断功能。
 * <p>
 * 支持 Unicode 字符和 ANSI 转义码的显示宽度计算。
 *
 * @author CH
 * @since 2024-01-01
 * @version 1.0.0
 */
class StringDisplayUtils {

    /**
     * 获取字符的显示宽度（Unicode 字符按双宽度计算）
     *
     * @param c 字符
     * @return 显示宽度
     */
    static int getCharDisplayLength(char c) {
        return 1;
    }

    /**
     * 获取字符串的显示宽度（Unicode 字符按双宽度计算）
     * <p>
     * 自动跳过 ANSI 转义码，不计算其显示宽度。
     *
     * @param s 字符串
     * @return 显示宽度
     */
    static int getStringDisplayLength(String s) {
        int displayWidth = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\033') {
                // 跳过 ANSI 转义码
                while (i < s.length() && s.charAt(i) != 'm') {
                    i++;
                }
            } else {
                displayWidth += getCharDisplayLength(s.charAt(i));
            }
        }
        return displayWidth;
    }

    /**
     * 按指定显示宽度截断字符串
     * <p>
     * 自动处理 ANSI 转义码的显示宽度，确保截断后的显示长度不超过限制。
     *
     * @param s                字符串
     * @param maxDisplayLength 最大显示长度
     * @return 截断后的字符串
     */
    static String trimDisplayLength(String s, int maxDisplayLength) {
        if (maxDisplayLength <= 0) {
            return "";
        }

        int totalLength = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\033') {
                // skip ANSI escape sequence including 'm'
                while (i < s.length() && s.charAt(i) != 'm') {
                    i++;
                }
                // i now points at 'm' or s.length(); advance past it
                if (i < s.length()) {
                    i++;
                }
                continue;
            }
            totalLength += getCharDisplayLength(s.charAt(i));
            if (totalLength > maxDisplayLength) {
                return s.substring(0, i);
            }
        }
        return s;
    }

}
