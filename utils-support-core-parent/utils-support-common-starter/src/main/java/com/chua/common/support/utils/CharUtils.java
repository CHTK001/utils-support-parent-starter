package com.chua.common.support.utils;


import static com.chua.common.support.constant.CharConstant.*;
import static com.chua.common.support.constant.CommonConstant.SYMBOL_LEFT_SLASH_CHAR;
import static com.chua.common.support.constant.CommonConstant.SYMBOL_RIGHT_SLASH_CHAR;
import org.jspecify.annotations.NullUnmarked;

/**
 * 字符工具类，提供常见字符类型判断、ASCII/Unicode 相关判断、
 * 文件分隔符判断以及字符映射转换等辅助方法。
 *
 * @author CH
 */
@NullUnmarked
public class CharUtils {
    /**
     * 输入结束标记，常用于表示文本或流读取结束。
     */
    public static final byte EOI = 0x1A;

    /**
     * 判断指定字符是否为普通空白字符。
     *
     * @param ch 待判断的字符
     * @return 如果字符为普通空格则返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isWhitespace(final char ch) {
        return ch == ' ';
    }

    /**
     * 判断指定字符是否为输入结束标记 {@code EOI}。
     *
     * @param ch 待判断的字符
     * @return 如果字符等于输入结束标记则返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isEndOfInput(final char ch) {
        return ch == 0x1A;
    }

    /**
     * 判断指定字符是否为字母字符或下划线。
     *
     * @param ch 待判断的字符
     * @return 如果字符为 {@code A-Z}、{@code a-z} 或下划线 {@code _}，则返回 {@code true}
     */
    public static boolean isAlphabet(final char ch) {
        return ch >= 'A' && ch <= 'Z' || ch >= 'a' && ch <= 'z' || ch == '_';
    }

    /**
     * 判断指定字符是否为十进制数字字符 {@code 0-9}。
     *
     * @param ch 待判断的字符
     * @return 如果字符为数字则返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isDigital(final char ch) {
        return ch >= '0' && ch <= '9';
    }

    /**
     * 判断指定字符是否为常见运算符字符。
     *
     * @param ch 待判断的字符
     * @return 如果字符属于常见运算符集合则返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isOperator(final char ch) {
        return '+' == ch
                || '-' == ch
                || '*' == ch
                || '/' == ch
                || '%' == ch
                || '=' == ch
                || '>' == ch
                || '<' == ch
                || '!' == ch
                || '&' == ch
                || '|' == ch
                || '?' == ch
                || ':' == ch
                ;
    }

    /**
     *                      ASCII         0-127
     *
     * <pre>
     *   CharUtil.isAscii('a')  = true
     *   CharUtil.isAscii('A')  = true
     *   CharUtil.isAscii('3')  = true
     *   CharUtil.isAscii('-')  = true
     *   CharUtil.isAscii('\n') = true
     *   CharUtil.isAscii('&copy;') = false
     * </pre>
     *
     * @param ch
     * @return          ASCII            true               false
     */
    public static boolean isAscii(char ch) {
        return ch < 128;
    }

    /**
     *                            ASCII         32-126
     *
     * <pre>
     *   CharUtil.isAsciiPrintable('a')  = true
     *   CharUtil.isAsciiPrintable('A')  = true
     *   CharUtil.isAsciiPrintable('3')  = true
     *   CharUtil.isAsciiPrintable('-')  = true
     *   CharUtil.isAsciiPrintable('\n') = false
     *   CharUtil.isAsciiPrintable('&copy;') = false
     * </pre>
     *
     * @param ch
     * @return                ASCII            true               false
     */
    public static boolean isAsciiPrintable(char ch) {
        return ch >= 32 && ch < 127;
    }

    /**
     *                      ASCII               0-31   127
     *
     * <pre>
     *   CharUtil.isAsciiControl('a')  = false
     *   CharUtil.isAsciiControl('A')  = false
     *   CharUtil.isAsciiControl('3')  = false
     *   CharUtil.isAsciiControl('-')  = false
     *   CharUtil.isAsciiControl('\n') = true
     *   CharUtil.isAsciiControl('&copy;') = false
     * </pre>
     *
     * @param ch
     * @return          ASCII                  true               false
     */
    public static boolean isAsciiControl(final char ch) {
        return ch < 32 || ch == 127;
    }

    /**
     *
     *             A~Z   a~z
     *
     * <pre>
     *   CharUtil.isLetter('a')  = true
     *   CharUtil.isLetter('A')  = true
     *   CharUtil.isLetter('3')  = false
     *   CharUtil.isLetter('-')  = false
     *   CharUtil.isLetter('\n') = false
     *   CharUtil.isLetter('&copy;') = false
     * </pre>
     *
     * @param ch
     * @return                      true               false
     */
    public static boolean isLetter(char ch) {
        return isLetterUpper(ch) || isLetterLower(ch);
    }

    /**
     *                                     A-Z
     *
     * <pre>
     *   CharUtil.isLetterUpper('a')  = false
     *   CharUtil.isLetterUpper('A')  = true
     *   CharUtil.isLetterUpper('3')  = false
     *   CharUtil.isLetterUpper('-')  = false
     *   CharUtil.isLetterUpper('\n') = false
     *   CharUtil.isLetterUpper('&copy;') = false
     * </pre>
     *
     * @param ch
     * @return                            true               false
     */
    public static boolean isLetterUpper(final char ch) {
        return ch >= 'A' && ch <= 'Z';
    }

    /**
     *                                     a-z
     *
     * <pre>
     *   CharUtil.isLetterLower('a')  = true
     *   CharUtil.isLetterLower('A')  = false
     *   CharUtil.isLetterLower('3')  = false
     *   CharUtil.isLetterLower('-')  = false
     *   CharUtil.isLetterLower('\n') = false
     *   CharUtil.isLetterLower('&copy;') = false
     * </pre>
     *
     * @param ch
     * @return                            true               false
     */
    public static boolean isLetterLower(final char ch) {
        return ch >= 'a' && ch <= 'z';
    }

    /**
     *                                     0-9
     *
     * <pre>
     *   CharUtil.isNumber('a')  = false
     *   CharUtil.isNumber('A')  = false
     *   CharUtil.isNumber('3')  = true
     *   CharUtil.isNumber('-')  = false
     *   CharUtil.isNumber('\n') = false
     *   CharUtil.isNumber('&copy;') = false
     * </pre>
     *
     * @param ch
     * @return                            true               false
     */
    public static boolean isNumber(char ch) {
        return ch >= '0' && ch <= '9';
    }

    /**
     *                                           0-9, a-f, A-F
     *
     * @param c
     * @return                                  true               false
     * @since 4.1.5
     */
    public static boolean isHexChar(char c) {
        return isNumber(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     *                                        A-Z, a-z, 0-9
     *
     * <pre>
     *   CharUtil.isLetterOrNumber('a')  = true
     *   CharUtil.isLetterOrNumber('A')  = true
     *   CharUtil.isLetterOrNumber('3')  = true
     *   CharUtil.isLetterOrNumber('-')  = false
     *   CharUtil.isLetterOrNumber('\n') = false
     *   CharUtil.isLetterOrNumber('&copy;') = false
     * </pre>
     *
     * @param ch
     * @return                               true               false
     */
    public static boolean isLetterOrNumber(final char ch) {
        return isLetter(ch) || isNumber(ch);
    }


    /**
     * 判断给定类型是否为字符类型或原始字符类型。
     *
     * @param clazz 待判断的类型
     * @return 如果类型为 {@link Character} 或 {@code char}，则返回 {@code true}
     */
    public static boolean isCharClass(Class<?> clazz) {
        return clazz == Character.class || clazz == char.class;
    }

    /**
     * 判断给定对象是否表示字符值。
     *
     * @param value 待判断的对象
     * @return 如果对象为 {@link Character} 或原始字符类型，则返回 {@code true}
     */
    public static boolean isChar(Object value) {
        //noinspection ConstantConditions
        return value instanceof Character || value.getClass() == char.class;
    }

    /**
     *
     *
     * @param c
     * @return                            true               false
     * @see Character#isWhitespace(int)
     * @see Character#isSpaceChar(int)
     * @since 4.0.10
     */
    public static boolean isBlankChar(char c) {
        return isBlankChar((int) c);
    }

    /**
     *
     *
     * @param c
     * @return                            true               false
     * @see Character#isWhitespace(int)
     * @see Character#isSpaceChar(int)
     * @since 4.0.10
     */
    public static boolean isBlankChar(int c) {
        return Character.isWhitespace(c)
                || Character.isSpaceChar(c)
                || c == '\ufeff'
                || c == '\u202a'
                || c == '\u0000';
    }

    /**
     *                      emoji
     *
     * @param c
     * @return          emoji                  true               false
     * @since 4.0.8
     */
    public static boolean isEmoji(char c) {
        //noinspection ConstantConditions
        boolean b = (c == 0x0) ||
                (c == 0x9) ||
                (c == 0xA) ||
                (c == 0xD) ||
                ((c >= 0x20) && (c <= 0xD7FF)) ||
                ((c >= 0xE000) && (c <= 0xFFFD)) ||
                ((c >= 0x100000) && (c <= 0x10FFFF));

        return !b;
    }

    /**
     *                                              Windows: \, Unix/Linux: /
     *
     * @param c
     * @return                                     true               false
     * @since 4.1.11
     */
    public static boolean isFileSeparator(char c) {
        return SYMBOL_LEFT_SLASH_CHAR == c || SYMBOL_RIGHT_SLASH_CHAR == c;
    }

    /**
     *
     *
     * @param c1
     * @param c2
     * @param caseInsensitive
     * @return                               true               false
     * @since 4.0.3
     */
    public static boolean equals(char c1, char c2, boolean caseInsensitive) {
        if (caseInsensitive) {
            return Character.toLowerCase(c1) == Character.toLowerCase(c2);
        }
        return c1 == c2;
    }

    /**
     *                Unicode
     *
     * @param c
     * @return          Unicode
     * @since 5.2.3
     */
    public static int getType(int c) {
        return Character.getType(c);
    }

    /**
     *
     *
     * @param b
     * @return
     * @since 5.3.1
     */
    public static int digit16(int b) {
        return Character.digit(b, 16);
    }

    /**
     *
     * <pre>
     *     '1' -    ' '
     *     'A' -    ' '
     *     'a' -    ' '
     * </pre>
     *
     * @param c
     * @return
     * @see <a href="https://en.wikipedia.org/wiki/List_of_Unicode_characters#Unicode_symbols">Unicode_symbols</a>
     * @see <a href="https://en.wikipedia.org/wiki/Enclosed_Alphanumerics">Alphanumerics</a>
     * @since 5.6.2
     */
    public static char toCloseChar(char c) {
        int result = c;
        if (c >= SYMBOL_ONE && c <= SYMBOL_NINE) {
            result = '\u2460' + c - '1';
        } else if (c >= SYMBOL_UPPER_A && c <= SYMBOL_UPPER_Z) {
            result = ' ' + c - 'A';
        } else if (c >= SYMBOL_LOWER_A && c <= SYMBOL_LOWER_Z) {
            result = ' ' + c - 'a';
        }
        return (char) result;
    }

    /**
     *    1-20
     * <pre>
     *     1 -    ' '
     *     12 -    ' '
     *     20 -    ' '
     * </pre>
     *
     * @param number                               1-20
     * @return
     * @throws IllegalArgumentException                   1-20
     * @author CH
     * @see <a href="https://en.wikipedia.org/wiki/List_of_Unicode_characters#Unicode_symbols">            wikipedia-Unicode_symbols</a>
     * @see <a href="https://zh.wikipedia.org/wiki/Unicode%E5%AD%97%E7%AC%A6%E5%88%97%E8%A1%A8">            wikipedia-Unicode            </a>
     * @see <a href="https://coolsymbol.com/">coolsymbol</a>
     * @see <a href="https://baike.baidu.com/item/%E7%89%B9%E6%AE%8A%E5%AD%97%E7%AC%A6/112715?fr=aladdin">                         </a>
     * @since 5.6.2
     */
    public static char toCloseByNumber(int number) {
        int v20 = 20;
        if (number > v20) {
            throw new IllegalArgumentException("Number must be [1-20]");
        }
        return (char) (' ' + number - 1);
    }


    /**
     * 模拟 {@link String#regionMatches(boolean, int, String, int, int)} 的区域匹配逻辑，
     * 适用于字符序列的区间比较，支持大小写忽略模式。
     *
     * @param cs 源字符序列
     * @param ignoreCase 是否忽略大小写
     * @param thisStart 源序列开始位置
     * @param substring 待匹配的字符序列
     * @param start 待匹配序列开始位置
     * @param length 匹配长度
     * @return 如果指定区间内容匹配则返回 {@code true}，否则返回 {@code false}
     */
    static boolean regionMatches(final CharSequence cs, final boolean ignoreCase, final int thisStart,
                                 final CharSequence substring, final int start, final int length) {
        if (cs instanceof String && substring instanceof String) {
            return ((String) cs).regionMatches(ignoreCase, thisStart, (String) substring, start, length);
        }
        int index1 = thisStart;
        int index2 = start;
        int tmpLen = length;

        //                                  java.lang.String                  NPE
        final int srcLen = cs.length() - thisStart;
        final int otherLen = substring.length() - start;

        //
        if (thisStart < 0 || start < 0 || length < 0) {
            return false;
        }

        //
        if (srcLen < length || otherLen < length) {
            return false;
        }

        while (tmpLen-- > 0) {
            final char c1 = cs.charAt(index1++);
            final char c2 = substring.charAt(index2++);

            if (c1 == c2) {
                continue;
            }

            if (!ignoreCase) {
                return false;
            }

            //    String.regionMatches()
            final char u1 = Character.toUpperCase(c1);
            final char u2 = Character.toUpperCase(c2);
            if (u1 != u2 && Character.toLowerCase(u1) != Character.toLowerCase(u2)) {
                return false;
            }
        }

        return true;
    }
}