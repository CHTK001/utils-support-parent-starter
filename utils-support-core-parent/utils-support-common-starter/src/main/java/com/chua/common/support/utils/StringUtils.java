package com.chua.common.support.utils;

import com.chua.common.support.constant.CommonConstant;
import com.chua.common.support.constant.RegexConstant;
import com.chua.common.support.lang.algorithm.crypto.Hex;
import com.chua.common.support.matcher.PathMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Stack;
import java.util.StringJoiner;
import java.util.StringTokenizer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.chua.common.support.constant.CommonConstant.*;
import static com.chua.common.support.constant.FormatConstant.*;
import static com.chua.common.support.constant.RegexConstant.CONTROL_CHARS;
import static com.chua.common.support.constant.ValueConstant.SYMBOL_EMPTY_ARRAY;
import static com.chua.common.support.constant.ValueConstant.SYMBOL_EMPTY_STRING_ARRAY;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * 字符串工具类，提供丰富的字符串操作方法。
 *
 * <p>包括空值检查、空白处理、截取、填充、拆分、合并、替换、转换等常用操作。
 * 部分方法参考 Apache Commons Lang 实现，增强了对中文和空值的处理。</p>
 *
 * @author CH
 * @since 4.0.0
*/
@SuppressWarnings("ALL")
public class StringUtils {

    /**
    * 字符串工具。
    */
    private StringUtils() {
    }

    /**
    * SQL 注入替换正则，匹配 SQL 中有特殊含义的字符。
    *
    * <ul>
    *   <li>'"&lt;&gt;&amp;*+=#-; —— SQL 特殊字符</li>
    *   <li>\n —— 换行符</li>
    *   <li>\t —— 制表符</li>
    *   <li>\s —— 空白字符</li>
    *   <li>\r —— 回车符</li>
    * </ul>
    */
    private static final Pattern REPLACE_BLANK = Pattern.compile("'|\"|\\<|\\>|&|\\*|\\+|=|#|-|;|\\s*|\t|\r|\n");

    /**
    * memoised padding up 转为 21 (blocks 0 转为 20 spaces)
    */
    static final String[] PADDING = {"", " ", "  ", "   ", "    ", "     ", "      ", "       ", "        ",
            "         ", "          ", "           ", "            ", "             ", "              ", "               ",
            "                ", "                 ", "                  ", "                   ", "                    "};
    /** Thread_本地_构建器 */
    private static final ThreadLocal<Stack<StringBuilder>> THREAD_LOCAL_BUILDERS = new ThreadLocal<Stack<StringBuilder>>() {
        @Override
        /** initial值 */
        protected Stack<StringBuilder> initialValue() {
            return new Stack<>();
        }
    };
    /** 无 */
    private static final String NONE = "NONE";
    /** 是否为空 */
    private static final String NULL = "NULL";
    /** Sbuf_extra_容量 */
    private static final int SBUF_EXTRA_CAPACITY = 50;
    /** 默认_pad_限制 */
    private static final int DEFAULT_PAD_LIMIT = 30;
    /** Newline */
    private static final String NEWLINE = "\r\n";
    /** Ascii_限制 */
    private static final int ASCII_LIMIT = 127;
    /** HTML_space */
    private static final int HTML_SPACE = 160;
    /** Invisible_char_1 */
    private static final char INVISIBLE_CHAR_1 = 8203;
    /** Invisible_char_2 */
    private static final char INVISIBLE_CHAR_2 = 173;

    /**
    * 释放当前线程的 {@link StringBuilder} 池 thread本地
    *
    * <p>在固定线程池中，线程长期存活会导致 {@link #THREAD_LOCAL_BUILDERS} 缓存的
    * {@link Stack} 及其持有对象无法被回收，线程池关闭或应用退出时应调用此方法。</p>
    */
    public static void clearThreadLocalBuilders() {
        THREAD_LOCAL_BUILDERS.remove();
    }

    /**
    * 判断字符串是否为 {@code null} 或空字符串（长度为 0）
    *
    * <pre>
    * StringUtils.isEmpty(null) = true
    * StringUtils.isEmpty("")   = true
    * StringUtils.isEmpty(" ")  = false
    * StringUtils.isEmpty("abc") = false
    * </pre>
    *
    * @param str 待检查字符串
    * @return 如果为 {@code null} 或空返回 {@code true}
    * @see #isBlank(CharSequence)
    */
    public static boolean isEmpty(@Nullable CharSequence str) {
        return str == null || str.length() == 0;
    }

    /**
    * 判断字符串是否为空，不为空时执行回调
    *
    * <p>如果字符串不为 {@code null} 且不为空，则调用 {@code consumer} 处理该字符串。</p>
    *
    * @param str      待检查字符串
    * @param consumer 非空时执行的回调
    * @return 如果为 {@code null} 或空返回 {@code true}
    * @see #isBlank(CharSequence)
    */
    public static boolean isEmpty(@Nullable CharSequence str, @Nonnull Consumer<CharSequence> consumer) {
        if (!isEmpty(str)) {
            consumer.accept(str);
            return false;
        }
        return true;
    }

    /**
    * 判断字符串是否为 {@code null} 或空，与 {@link #isEmpty(CharSequence)} 等价
    *
    * @param str 待检查字符串
    * @return 如果为 {@code null} 或空返回 {@code true}
    * @see #isEmpty(CharSequence)
    */
    public static boolean isNullOrEmpty(@Nullable CharSequence str) {
        return isEmpty(str);
    }

    /**
    * 判断字符串是否为 {@code null} 或空，不为空时执行回调
    *
    * @param str      待检查字符串
    * @param consumer 非空时执行的回调
    * @return 如果为 {@code null} 或空返回 {@code true}
    */
    public static boolean isNullOrEmpty(CharSequence str, Consumer<CharSequence> consumer) {
        if (!isEmpty(str)) {
            consumer.accept(str);
            return false;
        }
        return true;
    }

    /**
    * 判断字符串是否不为空（非 {@code null} 且长度大于 0）
    *
    * @param str 待检查字符串
    * @return 如果非空返回 {@code true}
    * @see #isEmpty(CharSequence)
    */
    public static boolean isNotEmpty(@Nullable CharSequence str) {
        return false == isEmpty(str);
    }

    /**
    * 判断字符串是否"有值"（非 空 且非空白），与 {@link #isNotEmpty} 语义相同，
    * 但方法名更贴近 期权 的 是否present 习惯用法。
    *
    * <pre>
    * StringUtils.isPresent(null)   = false
    * StringUtils.isPresent("")     = false
    * StringUtils.isPresent(" ")    = false
    * StringUtils.isPresent("abc")  = true
    * </pre>
    *
    * @param str 待检查字符串
    * @return 如果存在（非 空 且非空白）返回 {@code true}
    * @see #isNotEmpty(CharSequence)
    * @see #isNotBlank(CharSequence)
    */
    public static boolean isPresent(@Nullable CharSequence str) {
        return isNotBlank(str);
    }

    /**
    * 判断字符串是否为空白（{@code null}、空串或仅包含空白字符）
    *
    * <pre>
    * StringUtils.isBlank(null)   = true
    * StringUtils.isBlank("")     = true
    * StringUtils.isBlank(" ")    = true
    * StringUtils.isBlank("abc")  = false
    * </pre>
    *
    * @param str 待检查字符串
    * @return 如果为空白返回 {@code true}
    * @see #isEmpty(CharSequence)
    */
    public static boolean isBlank(@Nullable CharSequence str) {
        final int length;
        if ((str == null) || ((length = str.length()) == 0)) {
            return true;
        }

        for (int i = 0; i < length; i++) {

            if (!isBlankChar(str.charAt(i))) {
                return false;
            }
        }

        return true;
    }

    /**
    * 判断字符串是否非空白（非 {@code null}、非空、不全是空白字符）
    *
    * @param str 待检查字符串
    * @return 如果非空白返回 {@code true}
    * @see #isBlank(CharSequence)
    */
    public static boolean isNotBlank(@Nullable CharSequence str) {
        return !isBlank(str);
    }


    /**
    * 重复单个字符指定次数
    *
    * <pre>
    * StringUtils.repeat('e', 0)  = ""
    * StringUtils.repeat('e', 3)  = "eee"
    * StringUtils.repeat('e', -2) = ""
    * </pre>
    *
    * @param c     待重复的字符
    * @param count 重复次数，小于等于 0 返回空串
    * @return 重复后的字符串
    */
    @Nonnull
    public static String repeat(char c, int count) {
        if (count <= 0) {
            return CommonConstant.SYMBOL_EMPTY;
        }

        char[] result = new char[count];
        for (int i = 0; i < count; i++) {
            result[i] = c;
        }
        return new String(result);
    }

    /**
    * 重复字符串指定次数，并用分隔符连接
    *
    * <pre>
    * repeat(null, null, 2) = null
    * repeat(null, "x", 2)  = null
    * repeat("", null, 0)   = ""
    * repeat("", "", 2)     = ""
    * repeat("", "x", 3)    = "xxx"
    * repeat("?", ", ", 3)  = "?, ?, ?"
    * </pre>
    *
    * @param str       待重复的字符串，可能为 空
    * @param separator 分隔符，可能为 空
    * @param repeat    重复次数，负数按 0 处理
    * @return 重复并用分隔符连接后的字符串，输入为 空 则返回 空
    * @since 2.5
    */
    @Nullable
    public static String repeat(@Nullable final String str, @Nullable final String separator, final int repeat) {
        if (str == null || separator == null) {
            return repeat(str, repeat);
        } else {

            String result = repeat(str + separator, repeat);
            return removeEnd(result, separator);
        }
    }

    /**
    * 重复字符串指定次数
    *
    * @param str   待重复的字符串
    * @param count 重复次数
    * @return 重复后的字符串，{@code null} 输入返回 {@code null}
    */
    public static String repeat(CharSequence str, int count) {
        if (null == str) {
            return null;
        }
        if (count <= 0 || str.length() == 0) {
            return CommonConstant.SYMBOL_EMPTY;
        }
        if (count == 1) {
            return str.toString();
        }


        final int len = str.length();
        final long longSize = (long) len * (long) count;
        final int size = (int) longSize;
        if (size != longSize) {
            throw new ArrayIndexOutOfBoundsException("Required String length is too large: " + longSize);
        }

        final char[] array = new char[size];
        str.toString().getChars(0, len, array, 0);
        int n;
        for (n = len; n < size - n; n <<= 1) {
            System.arraycopy(array, 0, array, n, n);
        }
        System.arraycopy(array, 0, array, n, size - n);
        return new String(array);
    }

    /**
    * 截取字符串指定范围的子串（支持负数索引）
    *
    * <p>与 JDK {@link String#substring(int, int)} 类似，但支持负数索引（从末尾计数）。</p>
    *
    * <pre>
    * StringUtils.sub("abcdefgh", 2, 3)   = "c"
    * StringUtils.sub("abcdefgh", 2, -3)  = "cde"
    * </pre>
    *
    * @param str              字符串
    * @param fromIndexInclude 起始索引（含），负数从末尾计算
    * @param toIndexExclude   结束索引（不含），负数从末尾计算
    * @return 截取后的子串
    */
    public static String sub(CharSequence str, int fromIndexInclude, int toIndexExclude) {
        if (isEmpty(str)) {
            return str(str);
        }
        int len = str.length();

        if (fromIndexInclude < 0) {
            fromIndexInclude = len + fromIndexInclude;
            if (fromIndexInclude < 0) {
                fromIndexInclude = 0;
            }
        } else if (fromIndexInclude > len) {
            fromIndexInclude = len;
        }

        if (toIndexExclude < 0) {
            toIndexExclude = len + toIndexExclude;
            if (toIndexExclude < 0) {
                toIndexExclude = len;
            }
        } else if (toIndexExclude > len) {
            toIndexExclude = len;
        }

        if (toIndexExclude < fromIndexInclude) {
            int tmp = fromIndexInclude;
            fromIndexInclude = toIndexExclude;
            toIndexExclude = tmp;
        }

        if (fromIndexInclude == toIndexExclude) {
            return CommonConstant.SYMBOL_EMPTY;
        }

        return str.toString().substring(fromIndexInclude, toIndexExclude);
    }

    /**
    * 截取字符串从指定位置到末尾的子串
    *
    * @param string    字符串
    * @param fromIndex 起始索引（0-based）
    * @return 从起始索引到末尾的子串
    */
    public static String subSuf(CharSequence string, int fromIndex) {
        if (null == string) {
            return null;
        }
        return sub(string, fromIndex, string.length());
    }

    /**
    * 截取字符串从头到指定位置的子串
    *
    * @param string         字符串
    * @param toIndexExclude 结束索引（不含）
    * @return 从头到指定位置的子串
    */
    public static String subPre(CharSequence string, int toIndexExclude) {
        return sub(string, 0, toIndexExclude);
    }

    /**
    * 截取指定分隔符之前的子串
    *
    * <p>{@code null} 输入返回 {@code null}，空字符串返回空串。</p>
    *
    * <pre>
    * StringUtils.subBefore(null, *, false)      = null
    * StringUtils.subBefore("", *, false)        = ""
    * StringUtils.subBefore("abc", "a", false)   = ""
    * StringUtils.subBefore("abcba", "b", false) = "a"
    * StringUtils.subBefore("abc", "c", false)   = "ab"
    * StringUtils.subBefore("abc", "d", false)   = "abc"
    * StringUtils.subBefore("abc", null, false)  = "abc"
    * </pre>
    *
    * @param string          字符串
    * @param separator       分隔符
    * @param isLastSeparator 是否匹配最后一个分隔符
    * @return 分隔符之前的子串
    * @since 3.1.1
    */
    public static String subBefore(CharSequence string, CharSequence separator, boolean isLastSeparator) {
        if (isEmpty(string) || separator == null) {
            return null == string ? null : string.toString();
        }

        final String str = string.toString();
        final String sep = separator.toString();
        if (sep.isEmpty()) {
            return CommonConstant.SYMBOL_EMPTY;
        }
        final int pos = isLastSeparator ? str.lastIndexOf(sep) : str.indexOf(sep);
        if (INDEX_NOT_FOUND == pos) {
            return str;
        }
        if (0 == pos) {
            return CommonConstant.SYMBOL_EMPTY;
        }
        return str.substring(0, pos);
    }

    /**
    * 截取指定字符之前的子串
    *
    * <pre>
    * StringUtils.subBefore(null, *, false)      = null
    * StringUtils.subBefore("", *, false)        = ""
    * StringUtils.subBefore("abc", 'a', false)   = ""
    * StringUtils.subBefore("abcba", 'b', false) = "a"
    * StringUtils.subBefore("abc", 'c', false)   = "ab"
    * StringUtils.subBefore("abc", 'd', false)   = "abc"
    * </pre>
    *
    * @param string          字符串
    * @param separator       分隔字符
    * @param isLastSeparator 是否匹配最后一个分隔符
    * @return 分隔字符之前的子串
    * @since 4.1.15
    */
    public static String subBefore(CharSequence string, char separator, boolean isLastSeparator) {
        if (isEmpty(string)) {
            return null == string ? null : CommonConstant.SYMBOL_EMPTY;
        }

        final String str = string.toString();
        final int pos = isLastSeparator ? str.lastIndexOf(separator) : str.indexOf(separator);
        if (INDEX_NOT_FOUND == pos) {
            return str;
        }
        if (0 == pos) {
            return CommonConstant.SYMBOL_EMPTY;
        }
        return str.substring(0, pos);
    }

    /**
    * <br>
    * 空   ""                        <br>
    * 空   ""
    *
    * <pre>
    * StrUtil.subAfter(null, *, false)      = null
    * StrUtil.subAfter("", *, false)        = ""
    * StrUtil.subAfter(*, null, false)      = ""
    * StrUtil.subAfter("abc", "a", false)   = "bc"
    * StrUtil.subAfter("abcba", "b", false) = "cba"
    * StrUtil.subAfter("abc", "c", false)   = ""
    * StrUtil.subAfter("abc", "d", false)   = ""
    * StrUtil.subAfter("abc", "", false)    = "abc"
    * </pre>
    *
    * @param string          字符串
    * @param separator       分隔符
    * @param isLastSeparator 是否匹配最后一个分隔符（true 则查找最后一个分隔符）
    * @return 分隔符之后的子串
    * @since 3.1.1
    */
    public static String subAfter(CharSequence string, CharSequence separator, boolean isLastSeparator) {
        if (isEmpty(string)) {
            return null == string ? null : CommonConstant.SYMBOL_EMPTY;
        }
        if (separator == null) {
            return CommonConstant.SYMBOL_EMPTY;
        }
        final String str = string.toString();
        final String sep = separator.toString();
        final int pos = isLastSeparator ? str.lastIndexOf(sep) : str.indexOf(sep);
        if (INDEX_NOT_FOUND == pos || (string.length() - 1) == pos) {
            return CommonConstant.SYMBOL_EMPTY;
        }
        return str.substring(pos + separator.length());
    }

    /**
    * <br>
    * 空   ""                        <br>
    * 空   ""
    *
    * <pre>
    * StrUtil.subAfter(null, *, false)      = null
    * StrUtil.subAfter("", *, false)        = ""
    * StrUtil.subAfter("abc", 'a', false)   = "bc"
    * StrUtil.subAfter("abcba", 'b', false) = "cba"
    * StrUtil.subAfter("abc", 'c', false)   = ""
    * StrUtil.subAfter("abc", 'd', false)   = ""
    * </pre>
    *
    * @param string          字符串
    * @param separator       分隔字符
    * @param isLastSeparator 是否匹配最后一个分隔符（true 则查找最后一个分隔符）
    * @return 分隔字符之后的子串
    * @since 4.1.15
    */
    public static String subAfter(CharSequence string, char separator, boolean isLastSeparator) {
        if (isEmpty(string)) {
            return null == string ? null : CommonConstant.SYMBOL_EMPTY;
        }
        final String str = string.toString();
        final int pos = isLastSeparator ? str.lastIndexOf(separator) : str.indexOf(separator);
        if (INDEX_NOT_FOUND == pos) {
            return CommonConstant.SYMBOL_EMPTY;
        }
        return str.substring(pos + 1);
    }


    /**
    * 去除字符串数组中每个元素的空白字符
    *
    * @param strs 字符串数组
    */
    public static void trim(String[] strs) {
        if (null == strs) {
            return;
        }
        String str;
        for (int i = 0; i < strs.length; i++) {
            str = strs[i];
            if (null != str) {
                strs[i] = trim(str);
            }
        }
    }

    /**
    * {@code null}               {@code null}
    *
    * <p>
    * <pre>
    * trim(null)          = null
    * trim(&quot;&quot;)            = &quot;&quot;
    * trim(&quot;     &quot;)       = &quot;&quot;
    * trim(&quot;abc&quot;)         = &quot;abc&quot;
    * trim(&quot;    abc    &quot;) = &quot;abc&quot;
    * </pre>
    *
    * @param str 待处理的字符串
    * @return 去除首尾空白后的字符串，{@code null} 返回 {@code null}
    */
    public static String trim(CharSequence str) {
        return (null == str) ? null : trim(str, 0);
    }

    /**
    * 去除字符串首尾的空白字符（使用 {@link Character#isWhitespace} 判断）
    *
    * @param str 待处理的字符串
    * @return 去除首尾空白后的字符串
    * @see Character#isWhitespace
    */
    public static String trimWhitespace(String str) {
        if (isEmpty(str)) {
            return str;
        }

        int beginIndex = 0;
        int endIndex = str.length() - 1;

        while (beginIndex <= endIndex && Character.isWhitespace(str.charAt(beginIndex))) {
            beginIndex++;
        }

        while (endIndex > beginIndex && Character.isWhitespace(str.charAt(endIndex))) {
            endIndex--;
        }

        return str.substring(beginIndex, endIndex + 1);
    }

    /**
    * 去除字符串中所有空白字符（包括中间空白）
    *
    * <pre>
    *     trimAllWhitespace("test") = "test"
    *     trimAllWhitespace("test ") = "test"
    *     trimAllWhitespace(" test ") = "test"
    *     trimAllWhitespace(" te st ") = "test"
    *     trimAllWhitespace(null) = null
    * </pre>
    *
    * @param source 源字符串
    * @return 去除所有空白后的字符串
    */
    public static String trimAllWhitespace(String source) {
        if (isEmpty(source)) {
            return source;
        }

        int len = source.length();
        StringBuilder sb = new StringBuilder(source.length());
        for (int i = 0; i < len; ++i) {
            char c = source.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
    * {@code null}         {@code ""}
    *
    * <pre>
    * StrUtil.trimToEmpty(null)          = ""
    * StrUtil.trimToEmpty("")            = ""
    * StrUtil.trimToEmpty("     ")       = ""
    * StrUtil.trimToEmpty("abc")         = "abc"
    * StrUtil.trimToEmpty("    abc    ") = "abc"
    * </pre>
    *
    * @param str 待处理的字符串
    * @return 去除首尾空白后的字符串，null 转为空字符串
    * @since 3.1.1
    */
    public static String trimToEmpty(CharSequence str) {
        return str == null ? CommonConstant.SYMBOL_EMPTY : trim(str);
    }

    /**
    * {@code null}      ""         {@code null}
    *
    * <pre>
    * StrUtil.trimToNull(null)          = null
    * StrUtil.trimToNull("")            = null
    * StrUtil.trimToNull("     ")       = null
    * StrUtil.trimToNull("abc")         = "abc"
    * StrUtil.trimToEmpty("    abc    ") = "abc"
    * </pre>
    *
    * @param str 待处理的字符串
    * @return 去除首尾空白后的字符串，空串转为 空
    * @since 3.2.1
    */
    public static String trimToNull(CharSequence str) {
        final String trimStr = trim(str);
        return CommonConstant.SYMBOL_EMPTY.equals(trimStr) ? null : trimStr;
    }

    /**
    * 根据模式去除字符串的空白字符
    *
    * @param str  待处理的字符串
    * @param mode 模式：{@code -1} 去除开头空白，{@code 0} 去除首尾空白，{@code 1} 去除结尾空白
    * @return 去除空白后的字符串，{@code null} 返回 {@code null}
    */
    public static String trim(CharSequence str, int mode) {
        return trim(str, mode, new Predicate<Character>() {
            @Override
            /**
            * 测试
            * @param c c
            */
            public boolean test(Character c) {
                return isBlankChar(c);
            }
        });
    }


    /**
    * 根据模式和自定义判断条件去除字符串字符
    *
    * @param str       待处理的字符串
    * @param mode      模式：{@code -1} 去除开头，{@code 0} 去除首尾，{@code 1} 去除结尾
    * @param predicate 判断条件，返回 {@code true} 表示去除该字符
    * @return 处理后的字符串，{@code null} 返回 {@code null}
    * @since 5.7.4
    */
    public static String trim(CharSequence str, int mode, Predicate<Character> predicate) {
        String result;
        if (str == null) {
            result = null;
        } else {
            int length = str.length();
            int start = 0;
            int end = length;
            if (mode <= 0) {
                while ((start < end) && (predicate.test(str.charAt(start)))) {
                    start++;
                }
            }
            if (mode >= 0) {
                while ((start < end) && (predicate.test(str.charAt(end - 1)))) {
                    end--;
                }
            }
            if ((start > 0) || (end < length)) {
                result = str.toString().substring(start, end);
            } else {
                result = str.toString();
            }
        }

        return result;
    }

    /**
    * 将对象转换为 UTF-8 编码的字符串
    *
    * <p>对于 Byte 数组和 ByteBuffer 会按 UTF-8 字符集解码；
    * 其他类型的数组使用 Arrays.转为字符串；其他对象直接调用 转为字符串()。</p>
    *
    * @param obj 待转换的对象
    * @return UTF-8 编码的字符串
    * @see #str(Object, Charset)
    */
    public static String utf8Str(Object obj) {
        return str(obj, UTF_8);
    }

    /**
    * 将对象按指定字符集转换为字符串
    *
    * <pre>
    *   1. Byte 数组或 ByteBuffer 按指定字符集解码
    *   2. 其他数组使用 Arrays.toString
    *   3. 其他对象直接调用 toString()
    * </pre>
    *
    * @param obj     待转换的对象
    * @param charset 字符集
    * @return 转换后的字符串
    */
    public static String str(Object obj, Charset charset) {
        if (null == obj) {
            return null;
        }

        if (obj instanceof String) {
            return (String) obj;
        } else if (obj instanceof byte[]) {
            return str((byte[]) obj, charset);
        } else if (obj instanceof Byte[]) {
            return str((Byte[]) obj, charset);
        } else if (obj instanceof ByteBuffer) {
            return str((ByteBuffer) obj, charset);
        } else if (ArrayUtils.isArray(obj)) {
            return ArrayUtils.toString(obj);
        }

        return obj.toString();
    }

    /**
    * 将字节数组按指定字符集名称转换为字符串
    *
    * @param bytes   字节数组
    * @param charset 字符集名称
    * @return 转换后的字符串
    */
    public static String str(byte[] bytes, String charset) {
        return str(bytes, Charset.forName(charset));
    }

    /**
    * 将字节数组按指定字符集转换为字符串
    *
    * @param data    字节数组
    * @param charset 字符集
    * @return 转换后的字符串
    */
    public static String str(byte[] data, Charset charset) {
        if (data == null) {
            return null;
        }

        if (null == charset) {
            return new String(data);
        }
        return new String(data, charset);
    }


    /**
    * 判断字符是否为空白字符
    *
    * <p>包括 {@link Character#isWhitespace(int)}、{@link Character#isSpaceChar(int)} 定义的空白字符，
    * 以及部分特殊不可见字符（如 BOM 零宽空格、朝鲜文填充符等）。</p>
    *
    * @param c 待检查的字符
    * @return 如果是空白字符返回 true
    * @see Character#isWhitespace(int)
    * @see Character#isSpaceChar(int)
    * @since 4.0.10
    */
    public static boolean isBlankChar(int c) {
        return Character.isWhitespace(c)
                || Character.isSpaceChar(c)
                || c == '\ufeff'
                || c == '\u202a'
                || c == '\u0000'

                || c == '\u3164'

                || c == '\u2800'

                || c == '\u180e';
    }


    /**
    * 判断字符串是否以指定字符结尾
    *
    * @param str 字符串
    * @param c   指定字符
    * @return 如果以该字符结尾返回 true
    */
    public static boolean endWith(CharSequence str, char c) {
        return c == str.charAt(str.length() - 1);
    }

    /**
    * 判断字符串是否以指定后缀结尾，可选忽略大小写
    *
    * <p>如果字符串和后缀均为 {@code null}，返回 {@code true}。</p>
    *
    * @param str          字符串
    * @param suffix       待检查的后缀
    * @param isIgnoreCase 是否忽略大小写
    * @return 如果以指定后缀结尾返回 true；两者均为 空 返回 true
    */
    public static boolean endWith(CharSequence str, CharSequence suffix, boolean isIgnoreCase) {
        if (null == str || null == suffix) {
            return null == str && null == suffix;
        }

        if (isIgnoreCase) {
            return str.toString().toLowerCase().endsWith(suffix.toString().toLowerCase());
        } else {
            return str.toString().endsWith(suffix.toString());
        }
    }

    /**
    * 判断字符串是否以指定后缀结尾（区分大小写）
    *
    * @param str    字符串
    * @param suffix 待检查的后缀
    * @return 如果以指定后缀结尾返回 true
    * @see #endWith(CharSequence, CharSequence, boolean)
    */
    public static boolean endWith(CharSequence str, CharSequence suffix) {
        return endWith(str, suffix, false);
    }

    /**
    * 如果字符串不以指定后缀结尾，则追加该后缀
    *
    * @param str    字符串
    * @param suffix 后缀
    * @return 追加后缀后的字符串
    */
    public static String endWithAppend(CharSequence str, CharSequence suffix) {
        if (null == str) {
            return SYMBOL_EMPTY;
        }
        boolean endWith = endWith(str, suffix, false);
        return endWith ? str.toString() : str.toString() + suffix.toString();
    }

    /**
    * 移除字符串末尾的指定后缀
    *
    * <p>如果字符串以指定后缀结尾，则移除后缀并返回；否则返回原字符串。</p>
    *
    * @param str    源字符串
    * @param suffix 待移除的后缀
    * @return 移除后缀后的字符串
    */
    public static String endWithMove(String str, String suffix) {
        boolean endWith = endWith(str, suffix);
        return endWith ? str.substring(0, str.length() - suffix.length()) : str;
    }

    /**
    * 判断字符串是否以指定前缀开头（忽略大小写）
    *
    * @param str    字符串
    * @param prefix 前缀
    * @return 如果以指定前缀开头返回 true
    * @see #startWith(CharSequence, CharSequence, boolean)
    */
    public static boolean startWithIgnoreCase(CharSequence str, CharSequence prefix) {
        return startWith(str, prefix, true);
    }

    /**
    * 判断字符串是否以指定字符开头
    *
    * @param str 字符串
    * @param c   指定字符
    * @return 如果以该字符开头返回 true
    */
    public static boolean startWith(CharSequence str, char c) {
        return c == str.charAt(0);
    }

    /**
    * <p>检查 CharSequence 是否以指定前缀开头（可选是否忽略大小写）。</p>
    *
    * @param str        the charsequence 转为 检查, may be 空
    * @param prefix     the 前缀 转为 查找, may be 空
    * @param ignoreCase indicates whether the compare should ignore 大小写
    * (大小写 insensitive) 或 not.
    * @return {@code true} if the charsequence 启动 with the 前缀 或
    * both {@code null}
    * @see String#startsWith(String)
    */
    private static boolean startsWith(final CharSequence str, final CharSequence prefix, final boolean ignoreCase) {
        if (str == null || prefix == null) {
            return str == prefix;
        }

        final int preLen = prefix.length();
        if (preLen > str.length()) {
            return false;
        }
        return CharUtils.regionMatches(str, ignoreCase, 0, prefix, 0, preLen);
    }

    /**
    * 判断字符串是否以指定前缀开头（区分大小写）
    *
    * @param str    字符串
    * @param prefix 前缀
    * @return 如果以指定前缀开头返回 true
    * @see #startWith(CharSequence, CharSequence, boolean)
    */
    public static boolean startWith(CharSequence str, CharSequence prefix) {
        return startWith(str, prefix, false);
    }

    /**
    * 判断字符串是否以指定前缀开头，可选忽略大小写
    *
    * <p>如果字符串和前缀均为 {@code null}，返回 {@code true}。</p>
    *
    * @param str          字符串
    * @param prefix       前缀
    * @param isIgnoreCase 是否忽略大小写
    * @return 如果以指定前缀开头返回 true；两者均为 空 返回 true
    */
    public static boolean startWith(CharSequence str, CharSequence prefix, boolean isIgnoreCase) {
        if (null == str || null == prefix) {
            return null == str && null == prefix;
        }

        if (isIgnoreCase) {
            return str.toString().toLowerCase().startsWith(prefix.toString().toLowerCase());
        } else {
            return str.toString().startsWith(prefix.toString());
        }
    }

    /**
    * 如果字符串不以指定前缀开头，则添加该前缀
    *
    * @param str    字符串
    * @param prefix 前缀
    * @return 添加前缀后的字符串
    */
    public static String startWithAppend(CharSequence str, CharSequence prefix) {
        if (null == str || prefix == null) {
            return null;
        }
        return startWith(str, prefix, false) ? str.toString() : prefix.toString() + str.toString();
    }

    /**
    * 移除字符串开头的指定前缀
    *
    * <p>如果字符串以指定前缀开头，则移除前缀并返回；否则返回原字符串。</p>
    *
    * @param str    源字符串
    * @param prefix 待移除的前缀
    * @return 移除前缀后的字符串
    */
    public static String startWithMove(CharSequence str, CharSequence prefix) {
        str = null == str ? "" : str;
        return !startWith(str, prefix, false) ? str.toString() : str.subSequence(prefix.length(), str.length()).toString();
    }

    /**
    * 依次移除字符串开头匹配的多个前缀
    *
    * <p>按顺序依次移除每个前缀，直到所有前缀处理完毕。</p>
    *
    * @param str    源字符串
    * @param prefix 待移除的前缀列表
    * @return 移除所有前缀后的字符串
    */
    public static String startWithMove(CharSequence str, CharSequence... prefix) {
        String rs = str.toString();
        for (CharSequence charSequence : prefix) {
            rs = startWithMove(rs, charSequence);
        }
        return rs;
    }

    /**
    * 使用 字符串tokenizer 将字符串拆分为字符串数组
    *
    * <p>默认启用去除空白和忽略空串。</p>
    *
    * @param str        输入字符串
    * @param delimiters 分隔符集合
    * @return 拆分后的字符串数组
    * @see #tokenizeToStringArray(String, String, boolean, boolean)
    */
    public static String[] tokenizeToStringArray(String str, String delimiters) {
        return tokenizeToStringArray(str, delimiters, true, true);
    }

    /**
    * 使用 字符串tokenizer 将字符串拆分为字符串数组，可配置是否修剪和忽略空串
    *
    * @param str               输入字符串
    * @param delimiters        分隔符集合
    * @param trimTokens        是否去除每个 令牌 的空白
    * @param ignoreEmptyTokens 是否忽略空 令牌
    * @return 拆分后的字符串数组
    */
    public static String[] tokenizeToStringArray(String str, String delimiters, boolean trimTokens, boolean ignoreEmptyTokens) {

        if (str == null) {
            return null;
        }

        StringTokenizer st = new StringTokenizer(str, delimiters);
        List<String> tokens = new ArrayList<String>();
        while (st.hasMoreTokens()) {
            String token = st.nextToken();
            if (trimTokens) {
                token = token.trim();
            }
            if (!ignoreEmptyTokens || token.length() > 0) {
                tokens.add(token);
            }
        }
        return tokens.toArray(SYMBOL_EMPTY_STRING_ARRAY);
    }


    /**
    * 使用 {} 占位符格式化字符串
    *
    * <p>支持反斜杠转义占位符：\{} 表示字面量 {}。</p>
    *
    * <pre>
    * format("this is {} for {}", "a", "b")   = "this is a for b"
    * format("this is \\{} for {}", "a", "b") = "this is \{} for a"
    * </pre>
    *
    * @param template 模板字符串，使用 {} 作为占位符
    * @param params   占位符参数
    * @return 格式化后的字符串
    */
    public static String format(CharSequence template, Object... params) {
        if (null == template) {
            return null;
        }
        if (null == params || params.length == 0) {
            return template.toString();
        }
        return format(template.toString(), params);
    }

    /**
    * 使用 {} 占位符格式化字符串（增强版）
    *
    * <p>支持以下特性：</p>
    * <ol>
    *   <li>{} 占位符按顺序替换</li>
    *   <li>{:.2f} 指定数字格式（如保留两位小数）</li>
    *   <li>反斜杠转义：\{} 输出字面量 {}</li>
    * </ol>
    *
    * <pre>
    * format("this is {} for {}", "a", "b")       = "this is a for b"
    * format("result: {:.2f}ms", 123.456)         = "result: 123.46ms"
    * format("this is \\{} for {}", "a", "b")     = "this is \{} for a"
    * </pre>
    *
    * @param strPattern 包含 {} 占位符的模板字符串
    * @param argArray   占位符参数数组
    * @return 格式化后的字符串
    */
    public static String format(final String strPattern, final Object... argArray) {
        if (isEmpty(strPattern) || null == argArray || argArray.length == 0) {
            return strPattern;
        }
        final int strPatternLength = strPattern.length();
        StringBuilder sbuf = new StringBuilder(strPatternLength + 50);
        int handledPosition = 0;

        for (int argIndex = 0; argIndex < argArray.length; argIndex++) {
            //                                        
            int delimindex = strPattern.indexOf(SYMBOL_LEFT_BIG_PARANTHESES_CHAR, handledPosition);
            if (delimindex == -1) {
                if (handledPosition == 0) {
                    return strPattern;
                }
                sbuf.append(strPattern, handledPosition, strPatternLength);
                return sbuf.toString();
            }

            //                   
            if (delimindex > 0 && strPattern.charAt(delimindex - 1) == SYMBOL_RIGHT_SLASH_CHAR) {
                if (delimindex > 1 && strPattern.charAt(delimindex - 2) == SYMBOL_RIGHT_SLASH_CHAR) {
                    //                            
                    sbuf.append(strPattern, handledPosition, delimindex - 1);
                    sbuf.append(utf8Str(argArray[argIndex]));
                    handledPosition = findPlaceholderEnd(strPattern, delimindex) + 1;
                } else {
                    //                               
                    argIndex--;
                    sbuf.append(strPattern, handledPosition, delimindex - 1);
                    sbuf.append(SYMBOL_LEFT_BIG_PARANTHESES_CHAR);
                    handledPosition = delimindex + 1;
                }
            } else {
                //                      
                sbuf.append(strPattern, handledPosition, delimindex);

                //                            
                int endIndex = findPlaceholderEnd(strPattern, delimindex);
                if (endIndex == -1) {
                    //                                              
                    sbuf.append(SYMBOL_LEFT_BIG_PARANTHESES_CHAR);
                    handledPosition = delimindex + 1;
                    argIndex--;
                } else {
                    //                      
                    String placeholder = strPattern.substring(delimindex + 1, endIndex);

                    //                         
                    String formattedValue = formatPlaceholder(placeholder, argArray[argIndex]);
                    sbuf.append(formattedValue);
                    handledPosition = endIndex + 1;
                }
            }
        }

        //                   
        sbuf.append(strPattern, handledPosition, strPattern.length());
        return sbuf.toString();
    }

    /**
    * 转义 HTML 文本内容中的特殊字符，防止 XSS 注入。
    *
    * <p>转义规则：{@code & → &amp;}、{@code < → &lt;}、{@code > → &gt;}。</p>
    *
    * @param text 原始文本（可为 空）
    * @return 转义后的安全文本；输入为 空 时返回 空
    */
    public static String escapeHtml(String text) {
        if (text == null) {
            return null;
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /**
    * 转义 HTML 属性值中的特殊字符，防止 XSS 注入。
    *
    * <p>转义规则：{@code & → &amp;}、{@code " → &quot;}、{@code < → &lt;}。</p>
    *
    * @param text 原始文本（可为 空）
    * @return 转义后的安全文本；输入为 空 时返回 空
    */
    public static String escapeAttr(String text) {
        if (text == null) {
            return null;
        }
        return text.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;");
    }

    /**
    * 查找占位符结束位置（即下一个 } 的位置）
    *
    * @param strPattern 模板字符串
    * @param startIndex 起始搜索位置（{ 的索引）
    * @return } 的索引，未找到返回 -1
    */
    private static int findPlaceholderEnd(String strPattern, int startIndex) {
        return strPattern.indexOf(SYMBOL_RIGHT_BIG_PARANTHESES_CHAR, startIndex);
    }

    /**
    * 格式化单个占位符
    *
    * <p>如果占位符为空（即 {}），直接转换参数为字符串；
    * 如果以冒号开头（如 {:.2f}），则按格式规范处理。</p>
    *
    * @param placeholder 占位符内容（{} 中间的部分）
    * @param arg         参数值
    * @return 格式化后的字符串
    */
    private static String formatPlaceholder(String placeholder, Object arg) {
        //                            
        if (isEmpty(placeholder)) {
            return utf8Str(createValue(arg));
        }

        //                                         :.2f   
        if (placeholder.startsWith(":")) {
            String formatSpec = placeholder.substring(1);
            return formatWithSpec(formatSpec, arg);
        }

        //                         
        return utf8Str(createValue(arg));
    }

    /**
    * 按格式规范格式化参数
    *
    * <p>支持以下格式规范：</p>
    * <ul>
    *   <li>{@code .2f} — 保留两位小数的浮点数</li>
    *   <li>{@code d} — 整数</li>
    *   <li>{@code s} — 字符串</li>
    *   <li>{@code .2e} — 科学计数法</li>
    *   <li>{@code .2%} — 百分比</li>
    * </ul>
    *
    * @param formatSpec 格式规范（如 .2f, d, s）
    * @param arg        待格式化的参数
    * @return 格式化后的字符串
    */
    private static String formatWithSpec(String formatSpec, Object arg) {
        try {
            //                   .2f, .3f    
            if (formatSpec.matches("\\.\\d+f")) {
                int precision = Integer.parseInt(formatSpec.substring(1, formatSpec.length() - 1));
                double value = convertToDouble(arg);
                return String.format("%." + precision + "f", value);
            }

            //                d
            if ("d".equals(formatSpec)) {
                long value = convertToLong(arg);
                return String.valueOf(value);
            }

            //                   s
            if ("s".equals(formatSpec)) {
                return String.valueOf(arg);
            }

            //                   .2e
            if (formatSpec.matches("\\.\\d+e")) {
                int precision = Integer.parseInt(formatSpec.substring(1, formatSpec.length() - 1));
                double value = convertToDouble(arg);
                return String.format("%." + precision + "e", value);
            }

            //             .2%
            if (formatSpec.matches("\\.\\d+%")) {
                int precision = Integer.parseInt(formatSpec.substring(1, formatSpec.length() - 1));
                double value = convertToDouble(arg);
                return String.format("%." + precision + "f%%", value * 100);
            }

            //                                        
            return utf8Str(createValue(arg));
        } catch (Exception e) {
            //                               
            return utf8Str(createValue(arg));
        }
    }

    /**
    * 将对象转换为 double 类型
    *
    * @param obj 待转换的对象
    * @return double 值
    */
    private static double convertToDouble(Object obj) {
        if (obj instanceof Number) {
            return ((Number) obj).doubleValue();
        }
        return Double.parseDouble(String.valueOf(obj));
    }

    /**
    * 将对象转换为 long 类型
    *
    * @param obj 待转换的对象
    * @return long 值
    */
    private static long convertToLong(Object obj) {
        if (obj instanceof Number) {
            return ((Number) obj).longValue();
        }
        return Long.parseLong(String.valueOf(obj));
    }


    /**
    * 将 {@link CharSequence} 转为字符串，{@code null} 返回 {@code null}
    *
    * @param cs {@link CharSequence}
    * @return 字符串，{@code null} 返回 {@code null}
    */
    public static String str(CharSequence cs) {
        return null == cs ? null : cs.toString();
    }


    /**
    * 创建格式化的参数值
    *
    * <p>将 {@code null} 转为 "NULL"，日期和时间类型按标准格式格式化。</p>
    *
    * @param o 原始参数
    * @return 格式化后的参数值
    */
    private static Object createValue(Object o) {
        if (null == o) {
            return "NULL";
        }

        if (o instanceof String) {
            return o.toString();
        }

        if (o instanceof Date) {
            return DateUtils.format((Date) o);
        }


        if (o instanceof LocalDateTime) {
            return DateUtils.format(((LocalDateTime) o), YYYY_MM_DD_HH_MM_SS);
        }

        if (o instanceof LocalDate) {
            return DateUtils.format(((LocalDate) o), YYYY_MM_DD);
        }


        if (o instanceof LocalTime) {
            return DateUtils.format(((LocalTime) o), HH_MM_SS);
        }

        return o;
    }


    /**
    * 获取字符串最左边指定长度的子串
    *
    * <p>如果长度超出字符串长度则返回原字符串，负数长度返回空串。</p>
    *
    * <pre>
    * StringUtils.left(null, *)    = null
    * StringUtils.left(*, -ve)     = ""
    * StringUtils.left("", *)      = ""
    * StringUtils.left("abc", 0)   = ""
    * StringUtils.left("abc", 2)   = "ab"
    * StringUtils.left("abc", 4)   = "abc"
    * </pre>
    *
    * @param str 字符串，可能为 空
    * @param len 所需长度
    * @return 最左边指定长度的子串，null 输入返回 空
    */
    public static String left(String str, int len) {
        if (str == null) {
            return null;
        }
        if (len < 0) {
            return CommonConstant.SYMBOL_EMPTY;
        }
        if (str.length() <= len) {
            return str;
        }
        return str.substring(0, len);
    }


    /**
    * 用空格左补齐字符串至指定长度
    *
    * <pre>
    * StringUtils.leftPad(null, *)   = null
    * StringUtils.leftPad("", 3)     = "   "
    * StringUtils.leftPad("bat", 3)  = "bat"
    * StringUtils.leftPad("bat", 5)  = "  bat"
    * StringUtils.leftPad("bat", 1)  = "bat"
    * StringUtils.leftPad("bat", -1) = "bat"
    * </pre>
    *
    * @param str  待填充的字符串，可能为 空
    * @param size 目标长度
    * @return 左补齐后的字符串，null 输入返回 空
    */
    public static String leftPad(String str, int size) {
        return leftPad(str, size, ' ');
    }

    /**
    * 用指定字符左补齐字符串至指定长度
    *
    * <pre>
    * StringUtils.leftPad(null, *, *)     = null
    * StringUtils.leftPad("", 3, 'z')     = "zzz"
    * StringUtils.leftPad("bat", 3, 'z')  = "bat"
    * StringUtils.leftPad("bat", 5, 'z')  = "zzbat"
    * StringUtils.leftPad("bat", 1, 'z')  = "bat"
    * StringUtils.leftPad("bat", -1, 'z') = "bat"
    * </pre>
    *
    * @param str     待填充的字符串，可能为 空
    * @param size    目标长度
    * @param padChar 填充字符
    * @return 左补齐后的字符串，null 输入返回 空
    * @since 2.0
    */
    public static String leftPad(String str, int size, char padChar) {
        if (str == null) {
            return null;
        }
        int pads = size - str.length();
        if (pads <= 0) {
            return str;
        }
        if (pads > SYMBOL_PAD_LIMIT) {
            return leftPad(str, size, String.valueOf(padChar));
        }
        return padding(pads, padChar).concat(str);
    }

    /**
    * 用指定字符串左补齐字符串至指定长度
    *
    * <pre>
    * StringUtils.leftPad(null, *, *)      = null
    * StringUtils.leftPad("", 3, "z")      = "zzz"
    * StringUtils.leftPad("bat", 3, "yz")  = "bat"
    * StringUtils.leftPad("bat", 5, "yz")  = "yzbat"
    * StringUtils.leftPad("bat", 8, "yz")  = "yzyzybat"
    * StringUtils.leftPad("bat", 1, "yz")  = "bat"
    * StringUtils.leftPad("bat", -1, "yz") = "bat"
    * StringUtils.leftPad("bat", 5, null)  = "  bat"
    * StringUtils.leftPad("bat", 5, "")    = "  bat"
    * </pre>
    *
    * @param str    待填充的字符串，可能为 空
    * @param size   目标长度
    * @param padStr 填充字符串，空 或空串视为单个空格
    * @return 左补齐后的字符串，null 输入返回 空
    */
    public static String leftPad(String str, int size, String padStr) {
        if (str == null) {
            return null;
        }
        if (isEmpty(padStr)) {
            padStr = " ";
        }
        int padLen = padStr.length();
        int strLen = str.length();
        int pads = size - strLen;
        if (pads <= 0) {
            return str;
        }
        if (padLen == 1 && pads <= SYMBOL_PAD_LIMIT) {
            return leftPad(str, size, padStr.charAt(0));
        }

        if (pads == padLen) {
            return padStr.concat(str);
        } else if (pads < padLen) {
            return padStr.substring(0, pads).concat(str);
        } else {
            char[] padding = new char[pads];
            char[] padChars = padStr.toCharArray();
            for (int i = 0; i < pads; i++) {
                padding[i] = padChars[i % padLen];
            }
            return new String(padding).concat(str);
        }
    }

    /**
    * 返回指定长度的空格填充（默认最大 30）
    *
    * @param width 填充宽度
    * @return 指定长度的空格字符串
    * @see #padding(int, int)
    */
    public static String padding(int width) {
        return padding(width, 30);
    }

    /**
    * 返回指定字符重复指定次数的填充字符串
    *
    * @param width   填充宽度
    * @param padChar 填充字符
    * @return 填充字符重复 width 次的字符串
    */
    public static String padding(int width, char padChar) {
        if (width <= 0) {
            return "";
        }
        if (padChar == ' ' && width < PADDING.length) {
            return PADDING[width];
        }
        char[] out = new char[width];
        for (int i = 0; i < width; i++) {
            out[i] = padChar;
        }
        return String.valueOf(out);
    }

    /**
    * 返回空格填充，最多不超过指定上限
    *
    * @param width           填充宽度
    * @param maxPaddingWidth 最大填充宽度，{@code -1} 表示无限制
    * @return 空格填充字符串
    */
    public static String padding(int width, int maxPaddingWidth) {
        if (maxPaddingWidth != -1) {
            width = Math.min(width, maxPaddingWidth);
        }
        if (width < PADDING.length) {
            return PADDING[width];
        }
        char[] out = new char[width];
        for (int i = 0; i < width; i++) {
            out[i] = ' ';
        }
        return String.valueOf(out);
    }

    /**
    * 用空格右补齐字符串至指定长度
    *
    * <pre>
    * StringUtils.rightPad(null, *)   = null
    * StringUtils.rightPad("", 3)     = "   "
    * StringUtils.rightPad("bat", 3)  = "bat"
    * StringUtils.rightPad("bat", 5)  = "bat  "
    * StringUtils.rightPad("bat", 1)  = "bat"
    * StringUtils.rightPad("bat", -1) = "bat"
    * </pre>
    *
    * @param str  待填充的字符串，可能为 空
    * @param size 目标长度
    * @return 右补齐后的字符串，null 输入返回 空
    */
    public static String rightPad(String str, int size) {
        return rightPad(str, size, ' ');
    }

    /**
    * 用指定字符右补齐字符串至指定长度
    *
    * <pre>
    * StringUtils.rightPad(null, *, *)     = null
    * StringUtils.rightPad("", 3, 'z')     = "zzz"
    * StringUtils.rightPad("bat", 3, 'z')  = "bat"
    * StringUtils.rightPad("bat", 5, 'z')  = "batzz"
    * StringUtils.rightPad("bat", 1, 'z')  = "bat"
    * StringUtils.rightPad("bat", -1, 'z') = "bat"
    * </pre>
    *
    * @param str     待填充的字符串，可能为 空
    * @param size    目标长度
    * @param padChar 填充字符
    * @return 右补齐后的字符串，null 输入返回 空
    * @since 2.0
    */
    public static String rightPad(String str, int size, char padChar) {
        if (str == null) {
            return null;
        }
        int pads = size - str.length();
        if (pads <= 0) {
            return str;
        }
        if (pads > SYMBOL_PAD_LIMIT) {
            return rightPad(str, size, String.valueOf(padChar));
        }
        return str.concat(padding(pads, padChar));
    }

    /**
    * 用指定字符串右补齐字符串至指定长度
    *
    * <pre>
    * StringUtils.rightPad(null, *, *)      = null
    * StringUtils.rightPad("", 3, "z")      = "zzz"
    * StringUtils.rightPad("bat", 3, "yz")  = "bat"
    * StringUtils.rightPad("bat", 5, "yz")  = "batyz"
    * StringUtils.rightPad("bat", 8, "yz")  = "batyzyzy"
    * StringUtils.rightPad("bat", 1, "yz")  = "bat"
    * StringUtils.rightPad("bat", -1, "yz") = "bat"
    * StringUtils.rightPad("bat", 5, null)  = "bat  "
    * StringUtils.rightPad("bat", 5, "")    = "bat  "
    * </pre>
    *
    * @param str    待填充的字符串，可能为 空
    * @param size   目标长度
    * @param padStr 填充字符串，空 或空串视为单个空格
    * @return 右补齐后的字符串，null 输入返回 空
    */
    public static String rightPad(String str, int size, String padStr) {
        if (str == null) {
            return null;
        }
        if (isEmpty(padStr)) {
            padStr = " ";
        }
        int padLen = padStr.length();
        int strLen = str.length();
        int pads = size - strLen;
        if (pads <= 0) {
            return str;
        }
        if (padLen == 1 && pads <= SYMBOL_PAD_LIMIT) {
            return rightPad(str, size, padStr.charAt(0));
        }

        if (pads == padLen) {
            return str.concat(padStr);
        } else if (pads < padLen) {
            return str.concat(padStr.substring(0, pads));
        } else {
            char[] padding = new char[pads];
            char[] padChars = padStr.toCharArray();
            for (int i = 0; i < pads; i++) {
                padding[i] = padChars[i % padLen];
            }
            return str.concat(new String(padding));
        }
    }

    /**
    * 在字符串末尾填充指定字符至指定长度。
    *
    * @param str     原始字符串
    * @param size    目标长度
    * @param padChar 填充字符
    * @return 填充后的字符串
    */
    public static String padAfter(String str, int size, char padChar) {
        if (str == null) {
            return null;
        }
        int pads = size - str.length();
        if (pads <= 0) {
            return str;
        }
        char[] padding = new char[pads];
        Arrays.fill(padding, padChar);
        return str.concat(new String(padding));
    }

    /**
    * 移除字符串开头的指定前缀
    *
    * <p>如果字符串以指定前缀开头，则移除该前缀；否则返回原字符串。</p>
    *
    * @param str    源字符串
    * @param prefix 待移除的前缀
    * @return 移除前缀后的字符串
    */
    public static String removePrefix(CharSequence str, CharSequence prefix) {
        if (isEmpty(str) || isEmpty(prefix)) {
            return str(str);
        }

        final String str2 = str.toString();
        if (str2.startsWith(prefix.toString())) {

            return subSuf(str2, prefix.length());
        }
        return str2;
    }

    /**
    * 忽略大小写移除字符串开头的指定前缀
    *
    * <p>如果字符串以指定前缀开头（忽略大小写），则移除该前缀；否则返回原字符串。</p>
    *
    * @param str    源字符串
    * @param prefix 待移除的前缀（忽略大小写）
    * @return 移除前缀后的字符串
    */
    public static String removePrefixIgnoreCase(CharSequence str, CharSequence prefix) {
        if (isEmpty(str) || isEmpty(prefix)) {
            return str(str);
        }

        final String str2 = str.toString();
        if (str2.toLowerCase().startsWith(prefix.toString().toLowerCase())) {

            return subSuf(str2, prefix.length());
        }
        return str2;
    }

    /**
    * 移除字符串末尾的指定后缀
    *
    * <p>如果字符串以指定后缀结尾，则移除该后缀；否则返回原字符串。</p>
    *
    * @param str    源字符串
    * @param suffix 待移除的后缀
    * @return 移除后缀后的字符串
    */
    public static String removeSuffix(CharSequence str, CharSequence suffix) {
        if (isEmpty(str) || isEmpty(suffix)) {
            return str(str);
        }

        final String str2 = str.toString();
        if (str2.endsWith(suffix.toString())) {

            return subPre(str2, str2.length() - suffix.length());
        }
        return str2;
    }

    /**
    * 返回指定子串在字符串中首次出现的位置
    *
    * <pre>
    *     indexOf("abc", "a") = 0
    *     indexOf("abc", "d") = -1
    *     indexOf("abc", "") == -1
    *     indexOf(null, "") == -1
    * </pre>
    *
    * @param value 被查找的字符串
    * @param signs 要查找的子串
    * @return 首次出现的索引，未找到返回 -1
    */
    public static int indexOf(final CharSequence value, CharSequence signs) {
        if (null == value || null == signs) {
            return INDEX_NOT_FOUND;
        }
        return value.toString().indexOf(signs.toString());
    }

    /**
    * 返回指定字符在字符串中首次出现的位置
    *
    * @param str        字符串
    * @param searchChar 要查找的字符
    * @return 首次出现的索引，未找到返回 -1
    */
    public static int indexOf(final CharSequence str, char searchChar) {
        return indexOf(str, searchChar, 0);
    }

    /**
    * 从指定位置开始返回指定字符在字符串中首次出现的位置
    *
    * @param str        字符串
    * @param searchChar 要查找的字符
    * @param start      起始搜索位置（0-based）
    * @return 首次出现的索引，未找到返回 -1
    */
    public static int indexOf(CharSequence str, char searchChar, int start) {
        if (str instanceof String) {
            return ((String) str).indexOf(searchChar, start);
        } else {
            return indexOf(str, searchChar, start, -1);
        }
    }

    /**
    * 在指定范围内返回指定字符首次出现的位置
    *
    * @param str        字符串
    * @param searchChar 要查找的字符
    * @param start      起始索引（含），负数或超出长度默认从 0 开始
    * @param end        结束索引（不含），负数或超出长度默认为字符串长度
    * @return 首次出现的索引，未找到返回 -1
    */
    public static int indexOf(final CharSequence str, char searchChar, int start, int end) {
        if (isEmpty(str)) {
            return INDEX_NOT_FOUND;
        }
        final int len = str.length();
        if (start < 0 || start > len) {
            start = 0;
        }
        if (end > len || end < 0) {
            end = len;
        }
        for (int i = start; i < end; i++) {
            if (str.charAt(i) == searchChar) {
                return i;
            }
        }
        return INDEX_NOT_FOUND;
    }

    /**
    * 如果字符串为空（blank），返回默认值
    *
    * @param source       源字符串
    * @param defaultValue 默认值
    * @return 非空时返回源字符串，否则返回默认值
    */
    public static String defaultString(String source, String defaultValue) {
        return isBlank(source) ? defaultValue : source;
    }

    /**
    * 如果字符串为空（blank），依次尝试第二个字符串和默认值
    *
    * @param source       源字符串
    * @param source2      第二个备选字符串
    * @param defaultValue 默认值
    * @return 第一个非空字符串
    */
    public static String defaultString(String source, String source2, String defaultValue) {
        return isBlank(source) ? defaultString(source2, defaultValue) : source;
    }

    /**
    * 如果字符串为空（blank），返回默认值；否则应用转换函数
    *
    * @param source            源字符串
    * @param noneValueFunction 转换函数，为 空 时返回默认值
    * @param defaultValue      默认值
    * @return 处理后的字符串
    */
    public static String defaultString(String source, Function<String, String> noneValueFunction, String defaultValue) {
        return isBlank(source) ? defaultValue : null == noneValueFunction ? defaultValue : noneValueFunction.apply(source);
    }

    /**
    * 将 {@code null} 转为空字符串
    *
    * @param str 字符串
    * @return 非 空 返回原字符串，空 返回空串
    */
    public static String nullToEmpty(CharSequence str) {
        return nullToDefault(str, SYMBOL_EMPTY);
    }

    /**
    * 将 {@code null} 转为指定默认值
    *
    * <pre>
    * nullToDefault(null, "default")  = "default"
    * nullToDefault("", "default")    = ""
    * nullToDefault("  ", "default")  = "  "
    * nullToDefault("bat", "default") = "bat"
    * </pre>
    *
    * @param str        字符串，可能为 空
    * @param defaultStr 默认值
    * @return 非 空 返回原字符串，空 返回默认值
    */
    public static String nullToDefault(CharSequence str, String defaultStr) {
        return (str == null) ? defaultStr : str.toString();
    }


    /**
    * 替换字符串列表中的所有指定占位符（合并后替换）
    *
    * @param value    字符串列表
    * @param oldPlace 待替换的占位符数组
    * @param newPlace 新字符串
    * @return 替换后的字符串
    */
    public static String replaceAll(List<String> value, String[] oldPlace, String newPlace) {
 // 替换全部
        return replaceAll(Joiner.on("\r\n").join(value), oldPlace, false, newPlace);
    }

    /**
    * 替换字符串列表中的所有指定占位符，可选忽略大小写
    *
    * @param value     字符串列表
    * @param oldPlace  待替换的占位符数组
    * @param ignoreCase 是否忽略大小写
    * @param newPlace  新字符串
    * @return 替换后的字符串
    */
    public static String replaceAll(List<String> value, String[] oldPlace, boolean ignoreCase, String newPlace) {
 // 替换全部
        return replaceAll(Joiner.on("\r\n").join(value), oldPlace, ignoreCase, newPlace);
    }

    /**
    * 替换字符串中的所有指定占位符，可选忽略大小写
    *
    * @param value      源字符串
    * @param oldPlace   待替换的占位符数组
    * @param ignoreCase 是否忽略大小写
    * @param newPlace   新字符串
    * @return 替换后的字符串
    */
    public static String replaceAll(String value, String[] oldPlace, boolean ignoreCase, String newPlace) {
        if (isEmpty(value)) {
            return SYMBOL_EMPTY;
        }

        if (null == newPlace) {
            return value;
        }

        if (ignoreCase) {
            newPlace = newPlace.toLowerCase();
        }
        for (String place : oldPlace) {
            if (ignoreCase) {
                place = place.toLowerCase();
            }
            value = value.replace(place, newPlace);
        }
        return value;
    }

    /**
    * 替换字符串中的所有指定占位符
    *
    * @param value    源字符串
    * @param oldPlace 待替换的占位符数组
    * @param newPlace 新字符串
    * @return 替换后的字符串
    */
    public static String replaceAll(String value, String[] oldPlace, String newPlace) {
        return replaceAll(value, oldPlace, false, newPlace);
    }

    /**
    * 替换字符串中的多个占位符（变参形式）
    *
    * @param value    源字符串
    * @param newPlace 新字符串
    * @param oldPlace 待替换的占位符列表
    * @return 替换后的字符串
    */
    public static String replaceAll(String value, String newPlace, String... oldPlace) {
        if (isEmpty(value)) {
            return SYMBOL_EMPTY;
        }

        if (null == newPlace) {
            return value;
        }

        for (String place : oldPlace) {
            value = value.replace(place, newPlace);
        }
        return value;
    }

    /**
    * 替换字符串列表中的多个占位符（合并后替换）
    *
    * @param value    字符串列表
    * @param newPlace 新字符串
    * @param oldPlace 待替换的占位符列表
    * @return 替换后的字符串
    */
    public static String replaceAll(List<String> value, String newPlace, String... oldPlace) {
        return replaceAll(Joiner.on("\r\n").join(value), newPlace, oldPlace);
    }

    /**
    * 替换字符串指定范围内的字符为指定字符
    *
    * <p>将字符串中 [startInclude, endExclude) 范围内的所有字符替换为 replacedChar。</p>
    *
    * @param str           字符串
    * @param startInclude  起始索引（含）
    * @param endExclude    结束索引（不含）
    * @param replacedChar  替换字符
    * @return 替换后的字符串
    * @since 3.2.1
    */
    public static String replace(CharSequence str, int startInclude, int endExclude, char replacedChar) {
        if (isEmpty(str)) {
            return str(str);
        }
        final int strLength = str.length();
        if (startInclude > strLength) {
            return str(str);
        }
        if (endExclude > strLength) {
            endExclude = strLength;
        }
        if (startInclude > endExclude) {

            return str(str);
        }

        final char[] chars = new char[strLength];
        for (int i = 0; i < strLength; i++) {
            if (i >= startInclude && i < endExclude) {
                chars[i] = replacedChar;
            } else {
                chars[i] = str.charAt(i);
            }
        }
        return new String(chars);
    }

    /**
    * 替换字符串中所有匹配的子串
    *
    * @param source     源字符串
    * @param oldPattern 待替换的子串
    * @param newPattern 新子串
    * @return 替换后的字符串
    */
    public static String replace(String source, String oldPattern, String newPattern) {
        if (!isEmpty(source) && !isEmpty(oldPattern) && !isEmpty(newPattern)) {
            int index = source.indexOf(oldPattern);
            if (index == -1) {
                return source;
            } else {
                int capacity = source.length();
                if (newPattern.length() > oldPattern.length()) {
                    capacity += 16;
                }

                StringBuilder sb = new StringBuilder(capacity);
                int pos = 0;

                for (int patLen = oldPattern.length(); index >= 0; index = source.indexOf(oldPattern, pos)) {
                    sb.append(source, pos, index);
                    sb.append(newPattern);
                    pos = index + patLen;
                }

                sb.append(source.substring(pos));
                return sb.toString();
            }
        } else {
            return source;
        }
    }

    /**
    * 用指定分隔符将字符串拆分为数组
    *
    * @param source    源字符串
    * @param delimiter 分隔符
    * @return 拆分后的字符串数组
    */
    public static String[] delimitedListToStringArray(String source, String delimiter) {
        return delimitedListToStringArray(source, delimiter, null);
    }

    /**
    * 用指定分隔符拆分字符串，同时移除每个元素中的指定字符
    *
    * @param source        源字符串
    * @param delimiter     分隔符
    * @param charsToDelete 需要从每个元素中移除的字符集合
    * @return 拆分并移除字符后的字符串数组
    */
    public static String[] delimitedListToStringArray(String source, String delimiter, String charsToDelete) {
        if (source == null) {
            return new String[0];
        }
        if (delimiter == null) {
            return new String[]{source};
        }

        List<String> result = new ArrayList<>();
        if (delimiter.isEmpty()) {
            for (int i = 0; i < source.length(); i++) {
                result.add(deleteAny(source.substring(i, i + 1), charsToDelete));
            }
        } else {
            int pos = 0;
            int delPos;
            while ((delPos = source.indexOf(delimiter, pos)) != -1) {
                result.add(deleteAny(source.substring(pos, delPos), charsToDelete));
                pos = delPos + delimiter.length();
            }
            if (source.length() > 0 && pos <= source.length()) {
                result.add(deleteAny(source.substring(pos), charsToDelete));
            }
        }
        return result.toArray(new String[0]);
    }

    /**
    * 删除字符串中包含在指定字符集合中的所有字符
    *
    * <pre>
    *     deleteAny("11", "1") = ""
    *     deleteAny("t1", "") = "t1"
    *     deleteAny(null, "1") = null
    *     deleteAny("T1", "1") = "T"
    * </pre>
    *
    * @param source        源字符串
    * @param charsToDelete 要删除的字符集合
    * @return 删除后的字符串
    */
    public static String deleteAny(String source, String charsToDelete) {
        if (isEmpty(source) || isEmpty(charsToDelete)) {
            return source;
        }

        StringBuilder sb = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (charsToDelete.indexOf(c) == -1) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
    * 将字符串首字母大写
    *
    * <pre>
    *     capitalize("11") = "11"
    *     capitalize("t1") = "T1"
    *     capitalize(null) = null
    * </pre>
    *
    * @param source 源字符串
    * @return 首字母大写后的字符串
    */
    public static String capitalize(String source) {
        if (isEmpty(source)) {
            return source;
        }

        char baseChar = source.charAt(0);
        char updatedChar = Character.toUpperCase(baseChar);
        if (baseChar == updatedChar) {
            return source;
        }

        char[] chars = source.toCharArray();
        chars[0] = updatedChar;
        return new String(chars, 0, chars.length);
    }

    /**
    * 移除字符串中首次出现指定字串及其之后的部分
    *
    * <p>如果字符串包含指定子串，则移除子串及其之后的内容并返回之前部分。</p>
    *
    * @param resource 源字符串
    * @param s        目标子串
    * @return 移除后的字符串
    */
    public static String removeSuffixContains(String resource, String s) {
        if (isEmpty(resource) || isEmpty(s) || !resource.contains(s)) {
            return resource;
        }

        return resource.substring(0, resource.indexOf(s));
    }

    /**
    * 移除字符串末尾的指定子串
    *
    * <pre>
    * removeEnd(null, *)      = null
    * removeEnd("", *)        = ""
    * removeEnd(*, null)      = *
    * removeEnd("www.domain.com", ".com")   = "www.domain"
    * removeEnd("abc", "")    = "abc"
    * </pre>
    *
    * @param str    源字符串，可能为 空
    * @param remove 要移除的子串，可能为 空
    * @return 移除子串后的字符串，null 输入返回 空
    * @since 2.1
    */
    public static String removeEnd(final String str, final String remove) {
        if (isEmpty(str) || isEmpty(remove)) {
            return str;
        }
        if (str.endsWith(remove)) {
            return str.substring(0, str.length() - remove.length());
        }
        return str;
    }

    /**
    * 将字符串数组转换为指定分隔符连接的字符串（如 CSV）
    *
    * @param arr   数组（可能为 空 或空）
    * @param delim 分隔符（通常为 ","）
    * @return 分隔符连接的字符串
    */
    public static String arrayToDelimitedString(Object[] arr, String delim) {
        if (ArrayUtils.isEmpty(arr)) {
            return "";
        }
        if (arr.length == 1) {
            return ObjectUtils.nullSafeToString(arr[0]);
        }

        StringJoiner sj = new StringJoiner(delim);
        for (Object elem : arr) {
            sj.add(String.valueOf(elem));
        }
        return sj.toString();
    }


    /**
    * 拆分字符串为数组，支持修剪和忽略空值
    *
    * @param str         字符串
    * @param separator   分隔符（字符）
    * @param limit       限制长度
    * @param isTrim      是否去除每个元素的空白
    * @param ignoreEmpty 是否忽略空元素
    * @return 拆分后的字符串数组
    * @since 3.0.8
    */
    public static String[] splitToArray(CharSequence str, char separator, int limit, boolean isTrim, boolean ignoreEmpty) {
        return ArrayUtils.toArray(split(str, separator, limit, isTrim, ignoreEmpty));
    }


    /**
    * 拆分字符串为数组，支持修剪和忽略空值（字符串分隔符）
    *
    * @param str         字符串
    * @param separator   分隔符（字符串）
    * @param limit       限制长度
    * @param isTrim      是否去除每个元素的空白
    * @param ignoreEmpty 是否忽略空元素
    * @return 拆分后的字符串数组
    * @since 3.0.8
    */
    public static String[] splitToArray(CharSequence str, String separator, int limit, boolean isTrim, boolean ignoreEmpty) {
        return Optional.ofNullable(ArrayUtils.toArray(split(str, separator, limit, isTrim, ignoreEmpty))).orElse(SYMBOL_EMPTY_STRING_ARRAY);
    }

    /**
    * 使用字符分隔符拆分字符串为列表
    *
    * <pre>
    * a#b#c = [a,b,c]
    * a##b#c = [a,"",b,c]
    * </pre>
    *
    * @param str       字符串
    * @param separator 分隔符
    * @return 拆分后的列表
    */
    public static List<String> splitList(CharSequence str, char separator) {
        return split(str, separator, 0);
    }

    /**
    * 拆分字符串为数组（使用 Guava Splitter，自动去除空白和忽略空串）
    *
    * @param str       字符串
    * @param separator 分隔符
    * @return 拆分后的字符串数组
    * @since 5.6.7
    */
    public static String[] splitToArray(CharSequence str, CharSequence separator) {
        if (str == null) {
            return new String[]{};
        }

        return Splitter.on(separator.toString()).trimResults().omitEmptyStrings().splitToList(String.valueOf(str)).toArray(SYMBOL_EMPTY_ARRAY);
    }

    /**
    * 使用字符分隔符拆分字符串为数组
    *
    * @param str       字符串
    * @param separator 分隔符
    * @return 拆分后的字符串数组
    */
    public static String[] splitToArray(CharSequence str, char separator) {
        return splitToArray(str, separator, 0);
    }

    /**
    * 拆分字符串为数组，限制结果数量
    *
    * @param text      字符串
    * @param separator 分隔符
    * @param limit     限制数量
    * @return 拆分后的字符串数组
    */
    public static String[] splitToArray(CharSequence text, char separator, int limit) {
        return Splitter.on(separator).trimResults().omitEmptyStrings().limit(limit).splitToList(String.valueOf(text)).toArray(SYMBOL_EMPTY_ARRAY);
    }

    /**
    * 使用字符分隔符拆分字符串为数组
    *
    * <pre>
    * a#b#c = [a,b,c]
    * a##b#c = [a,"",b,c]
    * </pre>
    *
    * @param str       字符串
    * @param separator 分隔符
    * @return 拆分后的字符串数组
    */
    public static String[] split(CharSequence str, char separator) {
        return split(str, separator, 0).toArray(new String[0]);
    }

    /**
    * 拆分并去除空白，忽略空串
    *
    * @param str       字符串
    * @param separator 分隔符
    * @return 拆分并修剪后的字符串数组
    */
    public static String[] splitAndTrim(String str, String separator) {
        if (StringUtils.isBlank(str)) {
            return SYMBOL_EMPTY_ARRAY;
        }
        return Splitter.on(separator).trimResults().omitEmptyStrings().splitToStream(str).toArray(it -> new String[0]);
    }

    /**
    * 使用字符分隔符拆分字符串为列表（默认不修剪、不忽略空）
    *
    * @param str       字符串
    * @param separator 分隔符
    * @param limit     限制 为 -1 时无限制
    * @return 拆分后的列表
    */
    public static List<String> split(CharSequence str, char separator, int limit) {
        return split(str, separator, limit, false, false);
    }

    /**
    * 使用字符分隔符拆分字符串（相邻分隔符视为一个）
    *
    * <pre>
    * StringUtils.split(null, *)         = null
    * StringUtils.split("", *)           = []
    * StringUtils.split("a.b.c", '.')    = ["a", "b", "c"]
    * StringUtils.split("a..b.c", '.')   = ["a", "b", "c"]
    * StringUtils.split("a:b:c", '.')    = ["a:b:c"]
    * StringUtils.split("a b c", ' ')    = ["a", "b", "c"]
    * </pre>
    *
    * @param str           字符串，可能为 空
    * @param separatorChar 分隔字符
    * @return 拆分后的字符串数组，null 输入返回空数组
    * @since 2.0
    */
    public static String[] split(final String str, final char separatorChar) {
        return splitWorker(str, separatorChar, false);
    }

    /**
    * 使用分隔符集合拆分字符串（字符串tokenizer）
    *
    * @param list       字符串
    * @param separators 分隔符集合（多个字符中的任意一个作为分隔）
    * @return 拆分后的字符串数组
    * @since 2.0
    */
    public static String[] split(String list, String separators) {
        return split(separators, list, false);
    }

    /**
    * 使用 字符串tokenizer 拆分字符串，可选是否包含分隔符
    *
    * @param separators 分隔符集合
    * @param list       待拆分的字符串
    * @param include    是否将分隔符作为 令牌 返回
    * @return 拆分后的字符串数组
    * @since 2.0
    */
    public static String[] split(String separators, String list, boolean include) {
        if (isEmpty(separators)) {
            return SYMBOL_EMPTY_ARRAY;
        }
        StringTokenizer tokens = new StringTokenizer(list, separators, include);
        String[] result = new String[tokens.countTokens()];
        int i = 0;
        while (tokens.hasMoreTokens()) {
            result[i++] = tokens.nextToken();
        }
        return result;
    }

    /**
    * 分割 和 分割preserve全部令牌 的核心实现
    *
    * @param str               字符串，可能为 {@code null}
    * @param separatorChar     分隔字符
    * @param preserveAllTokens 为 true 时相邻分隔符视为空 令牌；为 false 时相邻分隔符合并为一个
    * @return 拆分后的字符串数组
    */
    private static String[] splitWorker(final String str, final char separatorChar, final boolean preserveAllTokens) {


        if (str == null) {
            return new String[0];
        }
        final int len = str.length();
        if (len == 0) {
            return new String[0];
        }
        final List<String> list = new ArrayList<String>();
        int i = 0;
        int start = 0;
        boolean match = false;
        boolean lastMatch = false;
        while (i < len) {
            if (str.charAt(i) == separatorChar) {
                if (match || preserveAllTokens) {
                    list.add(str.substring(start, i));
                    match = false;
                    lastMatch = true;
                }
                start = ++i;
                continue;
            }
            lastMatch = false;
            match = true;
            i++;
        }
        if (match || preserveAllTokens) {
            list.add(str.substring(start, i));
        }
        return list.toArray(new String[0]);
    }

    /**
    * 使用字符串分隔符拆分字符串列表（默认不忽略大小写）
    *
    * @param str         字符串
    * @param separator   分隔符
    * @param limit       限制数量，0 表示无限制
    * @param isTrim      是否去除空白
    * @param ignoreEmpty 是否忽略空串
    * @return 拆分后的列表
    * @since 3.0.8
    */
    public static List<String> split(CharSequence str, String separator, int limit, boolean isTrim, boolean ignoreEmpty) {
        return split(str, separator, limit, isTrim, ignoreEmpty, false);
    }

    /**
    * 使用字符串分隔符拆分字符串列表，可配置修剪、忽略空串和忽略大小写
    *
    * @param text        字符串
    * @param separator   分隔符
    * @param limit       限制数量，0 表示无限制
    * @param isTrim      是否去除空白
    * @param ignoreEmpty 是否忽略空串
    * @param ignoreCase  是否忽略大小写
    * @return 拆分后的列表
    * @since 3.2.1
    */
    public static List<String> split(CharSequence text, String separator, int limit, boolean isTrim, boolean ignoreEmpty, boolean ignoreCase) {
        if (null == text) {
            return new ArrayList<>(0);
        }
        Splitter splitter = Splitter.on(separator);
        if (ignoreEmpty) {
            splitter = splitter.omitEmptyStrings();
        }

        if (isTrim) {
            splitter = splitter.trimResults();
        }


        if (limit > 0) {
            return splitter.limit(limit).splitToList(String.valueOf(text));
        }

        return splitter.splitToList(String.valueOf(text));
    }

    /**
    * 使用字符分隔符拆分字符串列表，可配置修剪和忽略空串
    *
    * @param str         字符串
    * @param separator   分隔符
    * @param limit       限制数量，-1 表示无限制
    * @param isTrim      是否去除空白
    * @param ignoreEmpty 是否忽略空串
    * @return 拆分后的列表
    * @since 3.0.8
    */
    public static List<String> split(CharSequence str, char separator, int limit, boolean isTrim, boolean ignoreEmpty) {
        if (null == str) {
            return new ArrayList<>(0);
        }
        Splitter splitter = Splitter.on(separator);
        if (limit > 0) {
            splitter = splitter.limit(limit);
        }
        if (isTrim) {
            splitter = splitter.trimResults();
        }

        if (ignoreEmpty) {
            splitter = splitter.omitEmptyStrings();
        }
        return splitter.splitToList(String.valueOf(str));
    }

    /**
    * 检查字符串在指定位置是否与子串匹配
    *
    * @param str       字符串
    * @param index     起始索引
    * @param substring 待匹配的子串
    * @return 如果匹配返回 true
    */
    public static boolean substringMatch(CharSequence str, int index, CharSequence substring) {
        if (index + substring.length() > str.length()) {
            return false;
        }
        for (int i = 0; i < substring.length(); i++) {
            if (str.charAt(index + i) != substring.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
    * 修饰符位掩码转换为字符串表示。
    *
    * <p>使用 {@link java.lang.reflect.Modifier} 静态判断位掩码各修饰符，
    * 属 JDK 常量判断 API（非方法/字段反射调用），规约 1.10 豁免。</p>
    *
    * @param mod      修饰符位掩码
    * @param splitter 分隔符
    * @return 修饰符字符串，如 "abstract final"
    */
    public static String modifier(int mod, char splitter) {
        StringBuilder sb = new StringBuilder(64);
        if (Modifier.isAbstract(mod)) {
            sb.append("abstract").append(splitter);
        }
        if (Modifier.isFinal(mod)) {
            sb.append("final").append(splitter);
        }
        if (Modifier.isInterface(mod)) {
            sb.append("interface").append(splitter);
        }
        if (Modifier.isNative(mod)) {
            sb.append("native").append(splitter);
        }
        if (Modifier.isPrivate(mod)) {
            sb.append("private").append(splitter);
        }
        if (Modifier.isProtected(mod)) {
            sb.append("protected").append(splitter);
        }
        if (Modifier.isPublic(mod)) {
            sb.append("public").append(splitter);
        }
        if (Modifier.isStatic(mod)) {
            sb.append("static").append(splitter);
        }
        if (Modifier.isStrict(mod)) {
            sb.append("strict").append(splitter);
        }
        if (Modifier.isSynchronized(mod)) {
            sb.append("synchronized").append(splitter);
        }
        if (Modifier.isTransient(mod)) {
            sb.append("transient").append(splitter);
        }
        if (Modifier.isVolatile(mod)) {
            sb.append("volatile").append(splitter);
        }
        if (sb.length() > 0) {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }

    /**
    * 按显示宽度自动换行
    *
    * @param string 字符串
    * @param width  每行显示宽度
    * @return 换行后的字符串
    * @see #wrapByDisplayWidth(String, int)
    */
    public static String wrap(String string, int width) {
        return wrapByDisplayWidth(string, width);
    }

    /**
    * 根据显示宽度自动换行（中文字符宽度为 2，英文字符宽度为 1）
    *
    * @param string 字符串
    * @param width  每行显示宽度
    * @return 换行后的字符串
    */
    public static String wrapByDisplayWidth(String string, int width) {
        if (string == null || width <= 0) {
            return string;
        }

        final StringBuilder sb = new StringBuilder(string.length());
        final char[] buffer = string.toCharArray();
        int displayCount = 0;

        for (char c : buffer) {
            //                                  
            int charWidth = isDoubleByteChar(c) ? 2 : 1;

            //                                                    
            if (displayCount + charWidth > width && displayCount > 0) {
                sb.append('\n');
                displayCount = 0;
            }

            if (c == '\n') {
                displayCount = 0;
            } else {
                displayCount += charWidth;
            }

            sb.append(c);
        }
        return sb.toString();
    }


    /**
    * 获取类的完整类名（数组类型带 [] 后缀）
    *
    * @param clazz Java 类
    * @return 类名
    */
    public static String classname(Class<?> clazz) {
        if (clazz.isArray()) {
            StringBuilder sb = new StringBuilder(clazz.getName());
            sb.delete(0, 2);
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) == CommonConstant.SYMBOL_SEMICOLON_CHAR) {
                sb.deleteCharAt(sb.length() - 1);
            }
            sb.append("[]");
            return sb.toString();
        } else {
            return clazz.getName();
        }
    }

    /**
    * 将对象转换为字符串，捕获异常防止抛出
    *
    * <p>如果 {@code toString()} 抛出异常，返回包含异常信息的错误描述。</p>
    *
    * @param obj 待转换的对象
    * @return 对象的字符串表示
    */
    public static String objectToString(Object obj) {
        if (null == obj) {
            return EMPTY_STRING;
        }
        try {
            return obj.toString();
        } catch (Throwable t) {
            return "ERROR DATA!!! Method toString() throw exception. obj class: " + obj.getClass()
                    + ", exception class: " + t.getClass()
                    + ", exception message: " + t.getMessage();
        }
    }

    /**
    * 获取 charsequence 的长度，{@code null} 返回 0
    *
    * @param cs 字符串或 {@code null}
    * @return 字符串长度，{@code null} 返回 0
    * @since 2.4
    */
    public static int length(final CharSequence cs) {
        return cs == null ? 0 : cs.length();
    }

    /**
    * 获取字符串的显示宽度（中文等宽字符计 2，其他计 1）
    *
    * @param str 字符串
    * @return 显示宽度
    */
    public static int getDisplayWidth(String str) {
        if (str == null) {
            return 0;
        }
        return getSingleCharCount(str) + getCharCount(str) * 2;
    }

    /**
    * 判断字符是否为双字节字符（如中文）
    *
    * @param c 字符
    * @return 如果为双字节字符返回 true
    */
    public static boolean isDoubleByteChar(char c) {
        //                      ASCII                                 
        return c > 0xFF;
    }


    /**
    * 转为线。
    * @param text 文本
    * @return 转为线的结果
    */
    public static List<String> toLines(String text) {
        List<String> result = new ArrayList<String>();
        BufferedReader reader = new BufferedReader(new StringReader(text));
        try {
            String line = reader.readLine();
            while (line != null) {
                result.add(line);
                line = reader.readLine();
            }
        } catch (IOException exc) {

        } finally {
            try {
                reader.close();
            } catch (IOException e) {

            }
        }
        return result;
    }

    /**
    * 去除字符串首尾的指定字符
    *
    * <pre>
    * strip(null, *)          = null
    * strip("", *)            = ""
    * strip("abc", null)      = "abc"
    * strip("  abc", null)    = "abc"
    * strip("abc  ", null)    = "abc"
    * strip(" abc ", null)    = "abc"
    * strip("  abcyx", "xyz") = "  abc"
    * </pre>
    *
    * @param str        字符串
    * @param stripChars 要去除的字符集合，空 去除空白字符
    * @return 去除后的字符串
    */
    public static String strip(final String str, final String stripChars) {
        if (isNullOrEmpty(str)) {
            return str;
        }
        final String newStr = stripStart(str, stripChars);
        return stripEnd(newStr, stripChars);
    }

    /**
    * 去除字符串末尾的指定字符
    *
    * <pre>
    * stripEnd(null, *)          = null
    * stripEnd("", *)            = ""
    * stripEnd("abc", "")        = "abc"
    * stripEnd("abc", null)      = "abc"
    * stripEnd("  abc", null)    = "  abc"
    * stripEnd("abc  ", null)    = "abc"
    * stripEnd(" abc ", null)    = " abc"
    * stripEnd("  abcyx", "xyz") = "  abc"
    * stripEnd("120.00", ".0")   = "12"
    * </pre>
    *
    * @param str        字符串
    * @param stripChars 要去除的字符集合，空 去除空白字符
    * @return 去除后的字符串
    */
    public static String stripEnd(final String str, final String stripChars) {
        int end;
        if (str == null || (end = str.length()) == 0) {
            return str;
        }

        if (stripChars == null) {
            while (end != 0 && Character.isWhitespace(str.charAt(end - 1))) {
                end--;
            }
        } else if (stripChars.isEmpty()) {
            return str;
        } else {
            while (end != 0 && stripChars.indexOf(str.charAt(end - 1)) != -1) {
                end--;
            }
        }
        return str.substring(0, end);
    }

    /**
    * 去除字符串开头的指定字符
    *
    * <pre>
    * stripStart(null, *)          = null
    * stripStart("", *)            = ""
    * stripStart("abc", "")        = "abc"
    * stripStart("abc", null)      = "abc"
    * stripStart("  abc", null)    = "abc"
    * stripStart("abc  ", null)    = "abc  "
    * stripStart(" abc ", null)    = "abc "
    * stripStart("yxabc  ", "xyz") = "abc  "
    * </pre>
    *
    * @param str        字符串
    * @param stripChars 要去除的字符集合，空 去除空白字符
    * @return 去除后的字符串
    */
    public static String stripStart(final String str, final String stripChars) {
        int strLen;
        if (str == null || (strLen = str.length()) == 0) {
            return str;
        }
        int start = 0;
        if (stripChars == null) {
            while (start != strLen && Character.isWhitespace(str.charAt(start))) {
                start++;
            }
        } else if (stripChars.isEmpty()) {
            return str;
        } else {
            while (start != strLen && stripChars.indexOf(str.charAt(start)) != INDEX_NOT_FOUND) {
                start++;
            }
        }
        return str.substring(start);
    }


    /**
    * 获取线程本地 StringBuilder 池中的构建器（无副作用：归还/复用）
    *
    * @return 借出的 StringBuilder 构建器，初始容量默认（16）
    */
    public static StringBuilder borrowBuilder() {
        return new StringBuilder(16);
    }


    /**
    * 使用分隔符连接集合中的元素
    *
    * @param strings 字符串集合
    * @param sep     分隔符
    * @return 连接后的字符串
    */
    public static String join(Collection<?> strings, String sep) {
        return join(strings.iterator(), sep);
    }

    /**
    * 使用分隔符连接迭代器中的元素
    *
    * @param strings 字符串迭代器
    * @param sep     分隔符
    * @return 连接后的字符串
    */
    public static String join(Iterator<?> strings, String sep) {
        if (!strings.hasNext()) {
            return SYMBOL_EMPTY;
        }

        String start = StringUtils.defaultString(strings.next().toString(), SYMBOL_EMPTY);
        if (!strings.hasNext()) {
            return start;
        }

        StringJoiner j = new StringJoiner(sep);
        j.add(start);
        while (strings.hasNext()) {
            j.add(strings.next().toString());
        }
        return j.toString();
    }

    /**
    * 使用分隔符连接字符串数组（跳过空元素）
    *
    * @param strings 字符串数组
    * @param sep     分隔符
    * @return 连接后的字符串
    */
    public static String join(String[] strings, String sep) {
        List<String> list = new ArrayList<>(strings.length);
        for (String s : strings) {
            if (StringUtils.isEmpty(s)) {
                continue;
            }
            list.add(s);
        }
        return join(list, sep);
    }

    /**
    * 使用分隔符连接可变参数中的字符串（跳过空元素）
    *
    * @param sep     分隔符
    * @param strings 可变参数中的字符串
    * @return 连接后的字符串
    */
    public static String join(String sep, String... strings) {
        List<String> list = new ArrayList<>(strings.length);
        for (String s : strings) {
            if (StringUtils.isEmpty(s)) {
                continue;
            }
            list.add(s);
        }
        return join(list, sep);
    }

    /**
    * 测试 that a 字符串 contains only ASCII characters.
    *
    * @param string scanned 字符串
    * @return true if 全部 characters are 入 范围 0 - 127
    */
    public static boolean isAscii(String string) {
        for (int i = 0; i < string.length(); i++) {
            int c = string.charAt(i);
            if (c > 127) {
                return false;
            }
        }
        return true;
    }


    /**
    * 测试 if a 编码 point 是否 "whitespace" as defined 入 the HTML spec. Used for 输出 HTML.
    *
    * @param c 编码 point 转为 测试
    * @return true if 编码 point 是否 whitespace, false otherwise
    * @see #isActuallyWhitespace(int)
    */
    public static boolean isWhitespace(int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\f' || c == '\r';
    }

    /**
    * 测试 if a 编码 point 是否 "whitespace" as defined by what it looks like. Used for Element.文本 etc.
    *
    * @param c 编码 point 转为 测试
    * @return true if 编码 point 是否 whitespace, false otherwise
    */
    public static boolean isActuallyWhitespace(int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\f' || c == '\r' || c == 160;

    }

    /**
     * 是否InvisibleChar。
     *
     * @param c 方法入参 c
     * @return 是否成功（true 表示成功）
     */
    public static boolean isInvisibleChar(int c) {
        return c == 8203 || c == 173;
    }

    /**
    * 归一化字符串中的空白字符：连续空白折叠为单个空格，换行、制表符等控制字符统一转为普通空格。
    *
    * <p>例如 {@code "a  \n\tb"} 归一化为 {@code "a b"}。适用于展示前的文本清洗，
    * 不改变首尾非空白字符本身。</p>
    *
    * @param string 待归一化的字符串，null 安全（null 直接返回 null）
    * @return 归一化后的字符串；入参为 null 时返回 null
    */
    public static String normaliseWhitespace(String string) {
        StringBuilder sb = borrowBuilder();
        appendNormalisedWhitespace(sb, string, false);
        return sb.toString();
    }

    /**
    * 将归一化后的空白文本追加到指定 {@link StringBuilder}，用于流式拼接场景。
    *
    * <p>对 {@code string} 内的空白做归一化（连续空白折叠为单个空格），
    * 结果追加到 {@code accum} 末尾，不修改 {@code accum} 中已有内容。
    * 当 {@code stripLeading} 为 true 时，仅跳过尚未遇到任何非空白字符之前的前导空白。</p>
    *
    * @param accum        目标 StringBuilder（非 null），归一化结果将追加到其末尾
    * @param string       待归一化的字符串，null 时不追加任何内容
    * @param stripLeading 是否去除前导空白；仅对追加到 accum 之前的空白有效
    */
    public static void appendNormalisedWhitespace(StringBuilder accum, String string, boolean stripLeading) {
        boolean lastWasWhite = false;
        boolean reachedNonWhite = false;

        int len = string.length();
        int c;
        for (int i = 0; i < len; i += Character.charCount(c)) {
            c = string.codePointAt(i);
            if (isActuallyWhitespace(c)) {
                boolean b = (stripLeading && !reachedNonWhite) || lastWasWhite;
                if (b) {
                    continue;
                }
                accum.append(' ');
                lastWasWhite = true;
            } else if (!isInvisibleChar(c)) {
                accum.appendCodePoint(c);
                lastWasWhite = false;
                reachedNonWhite = true;
            }
        }
    }

    /**
     * in。
     *
     * @param needle 方法入参 needle
     * @param haystack 方法入参 haystack
     * @return 是否成功（true 表示成功）
     */
    public static boolean in(final String needle, final String... haystack) {
        final int len = haystack.length;
        for (int i = 0; i < len; i++) {
            if (haystack[i].equals(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * inSorted。
     *
     * @param needle 方法入参 needle
     * @param haystack 方法入参 haystack
     * @return 是否成功（true 表示成功）
     */
    public static boolean inSorted(String needle, String[] haystack) {
        return Arrays.binarySearch(haystack, needle) >= 0;
    }


    /**
    * 创建 a 新 absolute URL, 从 a provided existing absolute URL 和 a relative URL 组件.
    *
    * @param baseUrl the existing absolute 基础 URL
    * @param relUrl  the relative URL 转为 resolve. (If it's already absolute, it will be 返回)
    * @return an absolute URL if one was able 转为 be generated, 或 the 空 字符串 if not
    */
    public static String resolve(String baseUrl, String relUrl) {

        baseUrl = stripControlChars(baseUrl);
        relUrl = stripControlChars(relUrl);
        try {
            URL base;
            try {
                base = new URL(baseUrl);
            } catch (MalformedURLException e) {

                URL abs = new URL(relUrl);
                return abs.toExternalForm();
            }
            return resolve(base, relUrl).toExternalForm();
        } catch (MalformedURLException e) {


            return RegexConstant.VALID_URI_SCHEME.matcher(relUrl).find() ? relUrl : "";
        }
    }

    /**
    * 创建 a 新 absolute URL, 从 a provided existing absolute URL 和 a relative URL 组件.
    *
    * @param base   the existing absolute 基础 URL
    * @param relUrl the relative URL 转为 resolve. (If it's already absolute, it will be 返回)
    * @return the resolved absolute URL
    * @throws MalformedURLException if an 错误 occurred generating the URL
    */
    public static URL resolve(URL base, String relUrl) throws MalformedURLException {
        relUrl = stripControlChars(relUrl);

        if (relUrl.startsWith(SYMBOL_QUESTION)) {
            relUrl = base.getPath() + relUrl;
        }

        URL url = new URL(base, relUrl);
        String fixedFile = RegexConstant.EXTRA_DOT_SEGMENTS_PATTERN.matcher(url.getFile()).replaceFirst("/");
        if (url.getRef() != null) {
            fixedFile = fixedFile + "#" + url.getRef();
        }
        return new URL(url.getProtocol(), url.getHost(), url.getPort(), fixedFile);
    }

    /**
     * stripControlChars。
     *
     * @param input 方法入参 input
     * @return 结果字符串
     */
    private static String stripControlChars(final String input) {
        return CONTROL_CHARS.matcher(input).replaceAll("");
    }


    /**
    * Prepends the 前缀 转为 the 启动 的 the 字符串 if the 字符串 执行 not
    * already 启动 with 任意 的 the 前缀.
    *
    * @param str        The 字符串.
    * @param prefix     The 前缀 转为 prepend 转为 the 启动 的 the 字符串.
    * @param ignoreCase Indicates whether the compare should ignore 大小写.
    * @param prefixes   其他合法的前缀（可选）。
    * @return A 新 字符串 if 前缀 was prepended, the same 字符串 otherwise.
    */
    private static String prependIfMissing(final String str, final CharSequence prefix, final boolean ignoreCase, final CharSequence... prefixes) {
        if (str == null || isEmpty(prefix) || startsWith(str, prefix, ignoreCase)) {
            return str;
        }
        if (ArrayUtils.isNotEmpty(prefixes)) {
            for (final CharSequence p : prefixes) {
                if (startsWith(str, p, ignoreCase)) {
                    return str;
                }
            }
        }
        return prefix + str;
    }

    /**
    * Prepends the 前缀 转为 the 启动 的 the 字符串 if the 字符串 执行 not
    * already 启动 with 任意 的 the 前缀.
    *
    * <pre>
    * StringUtils.prependIfMissing(null, null) = null
    * StringUtils.prependIfMissing("abc", null) = "abc"
    * StringUtils.prependIfMissing("", "xyz") = "xyz"
    * StringUtils.prependIfMissing("abc", "xyz") = "xyzabc"
    * StringUtils.prependIfMissing("xyzabc", "xyz") = "xyzabc"
    * StringUtils.prependIfMissing("XYZabc", "xyz") = "xyzXYZabc"
    * </pre>
    * <p>With additional prefixes,</p>
    * <pre>
    * StringUtils.prependIfMissing(null, null, null) = null
    * StringUtils.prependIfMissing("abc", null, null) = "abc"
    * StringUtils.prependIfMissing("", "xyz", null) = "xyz"
    * StringUtils.prependIfMissing("abc", "xyz", new CharSequence[]{null}) = "xyzabc"
    * StringUtils.prependIfMissing("abc", "xyz", "") = "abc"
    * StringUtils.prependIfMissing("abc", "xyz", "mno") = "xyzabc"
    * StringUtils.prependIfMissing("xyzabc", "xyz", "mno") = "xyzabc"
    * StringUtils.prependIfMissing("mnoabc", "xyz", "mno") = "mnoabc"
    * StringUtils.prependIfMissing("XYZabc", "xyz", "mno") = "xyzXYZabc"
    * StringUtils.prependIfMissing("MNOabc", "xyz", "mno") = "xyzMNOabc"
    * </pre>
    *
    * @param str      The 字符串.
    * @param prefix   The 前缀 转为 prepend 转为 the 启动 的 the 字符串.
    * @param prefixes 其他合法的前缀。
    * @return A 新 字符串 if 前缀 was prepended, the same 字符串 otherwise.
    * @since 3.2
    */
    public static String prependIfMissing(final String str, final CharSequence prefix, final CharSequence... prefixes) {
        return prependIfMissing(str, prefix, false, prefixes);
    }

    /**
    * Prepends the 前缀 转为 the 启动 的 the 字符串 if the 字符串 执行 not
    * already 启动, 大小写 insensitive, with 任意 的 the 前缀.
    *
    * <pre>
    * StringUtils.prependIfMissingIgnoreCase(null, null) = null
    * StringUtils.prependIfMissingIgnoreCase("abc", null) = "abc"
    * StringUtils.prependIfMissingIgnoreCase("", "xyz") = "xyz"
    * StringUtils.prependIfMissingIgnoreCase("abc", "xyz") = "xyzabc"
    * StringUtils.prependIfMissingIgnoreCase("xyzabc", "xyz") = "xyzabc"
    * StringUtils.prependIfMissingIgnoreCase("XYZabc", "xyz") = "XYZabc"
    * </pre>
    * <p>With additional prefixes,</p>
    * <pre>
    * StringUtils.prependIfMissingIgnoreCase(null, null, null) = null
    * StringUtils.prependIfMissingIgnoreCase("abc", null, null) = "abc"
    * StringUtils.prependIfMissingIgnoreCase("", "xyz", null) = "xyz"
    * StringUtils.prependIfMissingIgnoreCase("abc", "xyz", new CharSequence[]{null}) = "xyzabc"
    * StringUtils.prependIfMissingIgnoreCase("abc", "xyz", "") = "abc"
    * StringUtils.prependIfMissingIgnoreCase("abc", "xyz", "mno") = "xyzabc"
    * StringUtils.prependIfMissingIgnoreCase("xyzabc", "xyz", "mno") = "xyzabc"
    * StringUtils.prependIfMissingIgnoreCase("mnoabc", "xyz", "mno") = "mnoabc"
    * StringUtils.prependIfMissingIgnoreCase("XYZabc", "xyz", "mno") = "XYZabc"
    * StringUtils.prependIfMissingIgnoreCase("MNOabc", "xyz", "mno") = "MNOabc"
    * </pre>
    *
    * @param str      The 字符串.
    * @param prefix   The 前缀 转为 prepend 转为 the 启动 的 the 字符串.
    * @param prefixes 其他合法的前缀（可选）。
    * @return A 新 字符串 if 前缀 was prepended, the same 字符串 otherwise.
    * @since 3.2
    */
    public static String prependIfMissingIgnoreCase(final String str, final CharSequence prefix, final CharSequence... prefixes) {
        return prependIfMissing(str, prefix, true, prefixes);
    }


    /**
    *
    *
    * @param str the 输入 字符串
    * @return the 结果
    */
    public static byte[] utf8Bytes(CharSequence str) {
        return bytes(str, UTF_8);
    }

    /**
    * 将字符串转为字节数组
    *
    * @param str     输入字符串
    * @param charset 字符集
    * @return 字节数组
    */
    public static byte[] bytes(CharSequence str, Charset charset) {
        if (str == null) {
            return null;
        }

        if (null == charset) {
            return str.toString().getBytes();
        }
        return str.toString().getBytes(charset);
    }

    /**
    * 将字符串转为字节数组
    *
    * @param str     输入字符串
    * @param charset 字符集名称
    * @return 字节数组
    */
    public static byte[] bytes(CharSequence str, String charset) {
        return bytes(str, isNullOrEmpty(charset) ? Charset.defaultCharset() : Charset.forName(charset));
    }


    /**
    * <p>仅当子串在源字符串开头时移除该子串，否则返回源字符串。</p>
    *
    * <p>{@code null} 源字符串返回 {@code null}。
    * 空字符串（""）源字符串返回空字符串。
    * {@code null} 搜索字符串返回源字符串。</p>
    *
    * <pre>
    * StringUtils.removeStart(null, *)      = null
    * StringUtils.removeStart("", *)        = ""
    * StringUtils.removeStart(*, null)      = *
    * StringUtils.removeStart("www.domain.com", "www.")   = "domain.com"
    * StringUtils.removeStart("domain.com", "www.")       = "domain.com"
    * StringUtils.removeStart("www.domain.com", "domain") = "www.domain.com"
    * StringUtils.removeStart("abc", "")    = "abc"
    * </pre>
    *
    * @param str    源字符串，可能为 空
    * @param remove 要搜索并移除的子串，可能为 空
    * @return 移除子串后的字符串，如果源字符串为 空 则返回 空
    * @since 2.1
    */
    public static String removeStart(String str, String remove) {
        if (isEmpty(str) || isEmpty(remove)) {
            return str;
        }
        if (str.startsWith(remove)) {
            return str.substring(remove.length());
        }
        return str;
    }

    /**
    * 移除字符串开头和结尾的指定子串
    *
    * @param str    源字符串
    * @param remove 要移除的子串
    * @return 移除开头和结尾子串后的字符串
    */
    public static String removeStartEnd(String str, String remove) {
        //                                                                                                    
        if (isEmpty(str) || isEmpty(remove)) {
            return str;
        }
        //                                                                                                                               
        if (str.startsWith(remove)) {
            return removeEnd(str.substring(remove.length()), remove);
        }
        //                                                                                     
        return removeEnd(str, remove);
    }

    /**
    * <p>忽略大小写移除字符串开头匹配的子串，否则返回源字符串。</p>
    *
    * <p>{@code null} 源字符串返回 {@code null}。
    * 空字符串（""）源字符串返回空字符串。
    * {@code null} 搜索字符串返回源字符串。</p>
    *
    * <pre>
    * StringUtils.removeStartIgnoreCase(null, *)      = null
    * StringUtils.removeStartIgnoreCase("", *)        = ""
    * StringUtils.removeStartIgnoreCase(*, null)      = *
    * StringUtils.removeStartIgnoreCase("www.domain.com", "www.")   = "domain.com"
    * StringUtils.removeStartIgnoreCase("www.domain.com", "WWW.")   = "domain.com"
    * StringUtils.removeStartIgnoreCase("domain.com", "www.")       = "domain.com"
    * StringUtils.removeStartIgnoreCase("www.domain.com", "domain") = "www.domain.com"
    * StringUtils.removeStartIgnoreCase("abc", "")    = "abc"
    * </pre>
    *
    * @param str    源字符串，可能为 空
    * @param remove 要搜索并移除的子串（忽略大小写），可能为 空
    * @return 移除子串后的字符串，如果源字符串为 空 则返回 空
    * @since 2.4
    */
    public static String removeStartIgnoreCase(String str, String remove) {
        if (isEmpty(str) || isEmpty(remove)) {
            return str;
        }
        if (startsWithIgnoreCase(str, remove)) {
            return str.substring(remove.length());
        }
        return str;
    }

    /**
    * <p>忽略大小写检查字符串是否以指定前缀开头。</p>
    *
    * <p>{@code null} 值会被安全处理。两个 {@code null}
    * 引用被视为相等。比较时忽略大小写。</p>
    *
    * <pre>
    * StringUtils.startsWithIgnoreCase(null, null)      = true
    * StringUtils.startsWithIgnoreCase(null, "abc")     = false
    * StringUtils.startsWithIgnoreCase("abcdef", null)  = false
    * StringUtils.startsWithIgnoreCase("abcdef", "abc") = true
    * StringUtils.startsWithIgnoreCase("ABCDEF", "abc") = true
    * </pre>
    *
    * @param str    待检查的字符串，可能为 空
    * @param prefix 要查找的前缀，可能为 空
    * @return 如果字符串以指定前缀开头（忽略大小写），或两者都为 {@code null}，则返回 {@code true}
    * @see String#startsWith(String)
    * @since 2.4
    */
    public static boolean startsWithIgnoreCase(String str, String prefix) {
        return startsWith(str, prefix, true);
    }


    /**
    * <p>获取字符串最右侧的指定长度的字符。</p>
    *
    * <p>如果指定的长度不可用，或字符串为 {@code null}，
    * 则直接返回原字符串而不抛出异常。如果 len 为负数，返回空字符串。</p>
    *
    * <pre>
    * StringUtils.right(null, *)    = null
    * StringUtils.right(*, -ve)     = ""
    * StringUtils.right("", *)      = ""
    * StringUtils.right("abc", 0)   = ""
    * StringUtils.right("abc", 2)   = "bc"
    * StringUtils.right("abc", 4)   = "abc"
    * </pre>
    *
    * @param str 要从中获取最右侧字符的字符串，可能为 空
    * @param len 需要的字符串长度
    * @return 最右侧的字符，如果输入为 空 则返回 空
    */
    public static String right(String str, int len) {
        if (str == null) {
            return null;
        }
        if (len < 0) {
            return CommonConstant.SYMBOL_EMPTY;
        }
        if (str.length() <= len) {
            return str;
        }
        return str.substring(str.length() - len);
    }

    /**
    * 获取文件大小的可读描述（如 1.23GB、456.78KB）
    *
    * @param size 文件大小（字节）
    * @return 文件大小描述
    */
    public static String getNetFileSizeDescription(long size) {
        return getNetFileSizeDescription(size, new DecimalFormat("#.00"));
    }

    /**
    * 获取文件大小的可读描述，使用指定的数字格式
    *
    * @param size   文件大小（字节）
    * @param format 数字格式
    * @return 文件大小描述
    */
    public static String getNetFileSizeDescription(long size, DecimalFormat format) {
        StringBuilder bytes = new StringBuilder(16);
        int s1024 = 1024;
        if (size >= s1024 * s1024 * s1024) {
            double i = (size / (1024.0 * 1024.0 * 1024.0));
            bytes.append(format.format(i)).append("GB");
        } else if (size >= s1024 * s1024) {
            double i = (size / (1024.0 * 1024.0));
            bytes.append(format.format(i)).append("MB");
        } else if (size >= s1024) {
            double i = (size / (1024.0));
            bytes.append(format.format(i)).append("KB");
        } else {
            if (size <= 0) {
                bytes.append("0B");
            } else {
                bytes.append((int) size).append("B");
            }
        }
        return bytes.toString();
    }


    /**
    * 统计字符串中指定子串出现的次数
    *
    * @param source 源字符串
    * @param symbol 要统计的子串
    * @return 出现次数
    */
    public static int count(String source, String symbol) {
        if (isNullOrEmpty(source) || isNullOrEmpty(symbol)) {
            return 0;
        }
        int index = -1;
        int count = 0;
        while ((index = source.indexOf(symbol, index + 1)) != -1) {
            ++count;
        }
        return count;
    }

    /**
    * 获取字符串中中文字符（双字节字符）的数量
    *
    * @param cell 输入字符串
    * @return 中文字符数量
    */
    public static Integer getCharCount(String cell) {
        if (cell == null) {
            return 0;
        }
        return cell.length() - getSingleCharCount(cell);
    }


    /**
    * 获取字符串中单字节字符的数量
    *
    * @param cell 输入字符串
    * @return 单字节字符数量
    */
    public static Integer getSingleCharCount(String cell) {
        if (cell == null) {
            return 0;
        }
        String reg = "[^\t\\x00-\\xff]";
        cell = cell.replaceAll(reg, "");

        return cell.replaceAll("  ", "").length();
    }

    /**
    * 获取填充后的字符串（带列表索引）
    *
    * @param str    源字符串
    * @param len    目标长度
    * @param symbol 填充符号
    * @param index  列表索引
    * @return 填充后的字符串
    */
    public static String getPadString(String str, Integer len, String symbol, int index) {
        String origin = str + "  ";
        if (index == 0) {
            String tmp = getPadString(origin, len - 2);
            return symbol + tmp + symbol;
        } else {

            String tmp = getPadString(origin, len - 1);
            return tmp + symbol;
        }
    }

    /**
    * 将字符串填充到指定长度（居中填充空格）
    *
    * @param str 源字符串
    * @param len 目标长度
    * @return 填充后的字符串
    */
    public static String getPadString(String str, Integer len) {
        StringBuilder res = new StringBuilder(len + 16);
        str = str.trim();
        if (str.length() < len) {
            int diff = len - str.length();
            int fixLen = diff / 2;
            String fix = repeat(" ", fixLen);
            res.append(fix).append(str).append(fix);
            if (res.length() > len) {
                return res.substring(0, len);
            } else {
                res.append(repeat(" ", len - res.length()));
                return res.toString();
            }
        }
        return str.substring(0, len);
    }

    /**
    * 比较两个字符串是否相等（忽略大小写）
    *
    * <pre>
    * equalsIgnoreCase(null, null)   = true
    * equalsIgnoreCase(null, &quot;abc&quot;)  = false
    * equalsIgnoreCase(&quot;abc&quot;, null)  = false
    * equalsIgnoreCase(&quot;abc&quot;, &quot;abc&quot;) = true
    * equalsIgnoreCase(&quot;abc&quot;, &quot;ABC&quot;) = true
    * </pre>
    *
    * @param str1 第一个字符串
    * @param str2 第二个字符串
    * @return 如果两个字符串相等（忽略大小写）则返回 {@code true}
    */
    public static boolean equalsIgnoreCase(CharSequence str1, CharSequence str2) {
        return equals(str1, str2, true);
    }

    /**
    * 比较两个字符串是否相等，可选是否忽略大小写
    *
    * @param str1       第一个字符串
    * @param str2       第二个字符串
    * @param ignoreCase 是否忽略大小写
    * @return 如果相等则返回 {@code true}
    * @since 3.2.0
    */
    public static boolean equals(CharSequence str1, CharSequence str2, boolean ignoreCase) {
        if (null == str1) {

            return str2 == null;
        }
        if (null == str2) {

            return false;
        }

        if (ignoreCase) {
            return str1.toString().equalsIgnoreCase(str2.toString());
        } else {
            return str1.equals(str2);
        }
    }

    /**
    * 将字符串转换为 ASCII 码序列（逗号分隔）
    *
    * @param strValue 输入字符串
    * @return ASCII 码序列，如 "65,66,67"
    */
    public static String stringToAscii(String strValue) {
        StringBuilder sbu = new StringBuilder(strValue.length() * 4);
        char[] chars = strValue.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (i != chars.length - 1) {
                sbu.append((int) chars[i]).append(",");
            } else {
                sbu.append((int) chars[i]);
            }
        }
        return sbu.toString();
    }

    /**
    * 将 ASCII 码序列（逗号分隔）还原为字符串
    *
    * @param ascii ASCII 码序列，如 "65,66,67"
    * @return 还原后的字符串
    */
    public static String asciiToStr(String ascii) {
        StringBuilder sbu = new StringBuilder(ascii.length());
        String[] chars = ascii.split(",");
        for (String aChar : chars) {
            sbu.append((char) Integer.parseInt(aChar));
        }
        return sbu.toString();
    }


    /**
    * 判断字符串是否包含指定字符
    *
    * @param str        输入字符串
    * @param searchChar 要搜索的字符
    * @return 如果包含则返回 true
    * @since 3.1.2
    */
    public static boolean contains(CharSequence str, char searchChar) {
        return indexOf(str, searchChar) > -1;
    }

    /**
    * 判断字符串是否包含指定子串
    *
    * @param str       输入字符串
    * @param searchStr 要搜索的子串
    * @return 如果包含则返回 true
    * @since 5.1.1
    */
    public static boolean contains(CharSequence str, CharSequence searchStr) {
        if (null == str || null == searchStr) {
            return false;
        }
        return str.toString().contains(searchStr);
    }

    /**
    * 判断字符串是否包含任意一个指定子串
    *
    * @param str      输入字符串
    * @param testStrs 待检查的子串列表
    * @return 如果包含任意一个则返回 true
    * @since 3.2.0
    */
    public static boolean containsAny(CharSequence str, CharSequence... testStrs) {
        return null != getContainsStr(str, testStrs);
    }

    /**
    * 判断字符串是否包含任意一个指定字符
    *
    * @param str       输入字符串
    * @param testChars 待检查的字符列表
    * @return 如果包含任意一个则返回 true
    * @since 4.1.11
    */
    public static boolean containsAny(CharSequence str, char... testChars) {
        if (!isEmpty(str)) {
            int len = str.length();
            for (int i = 0; i < len; i++) {
                if (ArrayUtils.contains(testChars, str.charAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
    * 判断字符串是否仅包含指定字符集中的字符
    *
    * @param str       输入字符串
    * @param testChars 允许的字符集
    * @return 如果仅包含指定字符集中的字符则返回 true，否则返回 false
    * @since 4.4.1
    */
    public static boolean containsOnly(CharSequence str, char... testChars) {
        if (!isEmpty(str)) {
            int len = str.length();
            for (int i = 0; i < len; i++) {
                if (!ArrayUtils.contains(testChars, str.charAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
    * 判断字符串中是否包含空白字符
    * <br>
    * 空 返回 false，空字符串返回 false
    *
    * @param str 输入字符串
    * @return 如果包含空白字符则返回 true
    * @since 4.0.8
    */
    public static boolean containsBlank(CharSequence str) {
        if (null == str) {
            return false;
        }
        final int length = str.length();
        if (0 == length) {
            return false;
        }

        for (int i = 0; i < length; i += 1) {
            if (CharUtils.isBlankChar(str.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
    * 获取字符串中包含的第一个匹配子串
    *
    * @param str      输入字符串
    * @param testStrs 待检查的子串列表
    * @return 第一个匹配的子串，如果没有匹配则返回 空
    * @since 3.2.0
    */
    public static String getContainsStr(CharSequence str, CharSequence... testStrs) {
        if (isEmpty(str) || ArrayUtils.isEmpty(testStrs)) {
            return null;
        }
        for (CharSequence checkStr : testStrs) {
            if (str.toString().contains(checkStr)) {
                return checkStr.toString();
            }
        }
        return null;
    }

    /**
    * 忽略大小写判断字符串是否包含指定子串
    *
    * <p>{@code null} 安全处理。</p>
    *
    * @param str     输入字符串
    * @param testStr 待检查的子串
    * @return 如果包含则返回 true
    */
    public static boolean containsIgnoreCase(CharSequence str, CharSequence testStr) {
        if (null == str) {

            return null == testStr;
        }
        return str.toString().toLowerCase().contains(testStr.toString().toLowerCase());
    }

    /**
    * 忽略大小写判断字符串是否包含任意一个指定子串
    * <br>
    *
    * @param str      输入字符串
    * @param testStrs 待检查的子串列表
    * @return 如果包含任意一个则返回 true
    * @since 3.2.0
    */
    public static boolean containsAnyIgnoreCase(CharSequence str, CharSequence... testStrs) {
        return null != getContainsStrIgnoreCase(str, testStrs);
    }

    /**
    * 忽略大小写获取字符串中包含的第一个匹配子串
    * <br>
    *
    * @param str       输入字符串
    * @param sequences 待检查的子串列表
    * @return 第一个匹配的子串，如果没有匹配则返回 空
    * @since 3.2.0
    */
    public static String getContainsStrIgnoreCase(CharSequence str, CharSequence... sequences) {
        if (isEmpty(str) || ArrayUtils.isEmpty(sequences)) {
            return null;
        }
        for (CharSequence testStr : sequences) {
            if (containsIgnoreCase(str, testStr)) {
                return testStr.toString();
            }
        }
        return null;
    }


    /**
    * <p>获取分隔符第一次出现之前的子串。分隔符不会被返回。</p>
    *
    * <p>{@code null} 字符串输入返回 {@code null}。空字符串（""）输入返回空字符串。
    * {@code null} 分隔符返回原字符串。</p>
    *
    * <p>如果未找到分隔符，返回原字符串。</p>
    *
    * <pre>
    * StringUtils.substringBefore(null, *)      = null
    * StringUtils.substringBefore("", *)        = ""
    * StringUtils.substringBefore("abc", "a")   = ""
    * StringUtils.substringBefore("abcba", "b") = "a"
    * StringUtils.substringBefore("abc", "c")   = "ab"
    * StringUtils.substringBefore("abc", "d")   = "abc"
    * StringUtils.substringBefore("abc", "")    = ""
    * StringUtils.substringBefore("abc", null)  = "abc"
    * </pre>
    *
    * @param str       待获取子串的字符串，可能为 空
    * @param separator 要搜索的分隔符，可能为 空
    * @return 分隔符第一次出现之前的子串，如果输入为 空 则返回 空
    */
    public static String substringBefore(final String str, final String separator) {
        if (isEmpty(str) || separator == null) {
            return str;
        }
        if (separator.isEmpty()) {
            return "";
        }
        final int pos = str.indexOf(separator);
        if (pos == -1) {
            return str;
        }
        return str.substring(0, pos);
    }

    /**
    * 将对象数组转换为 "obj1,obj2...,objn" 格式的字符串
    *
    * @param array 对象数组
    * @return 逗号分隔的字符串
    */
    public static String arrayToString(Object... array) {
        StringBuilder sb = new StringBuilder(array.length * 16);
        for (Object object : array) {
            sb.append(object).append(",");
        }
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /**
    * 将数组对象转换为逗号分隔的字符串
    /**
    * 将任意类型数组（含基本类型数组）转换为逗号分隔的字符串。
    *
    * <p>使用 {@link java.lang.reflect.Array} 支持基本类型数组（int[]/long[] 等），
    * 这是 JDK 反射工具 API（非方法/字段反射调用），属规约 1.10 豁免范畴
    * （ReflectUtils 不覆盖基本类型数组遍历场景）。</p>
    *
    * @param array 数组对象（Object 类型或基本类型数组）
    * @return 逗号分隔的字符串
    * @throws NullPointerException 如果 array 为 null
    */
    public static <T> String arrayToString(Object array) {
        int length = Array.getLength(array);
        StringBuilder sb = new StringBuilder(Math.max(length * 16, 16));

        // 逐元素追加，元素间以逗号分隔
        for (int i = 0; i < length; i++) {
            sb.append(Array.get(array, i)).append(",");
        }

        // 移除末尾多余的逗号
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1);
        }

        return sb.toString();
    }

    /**
    * 将对象数组使用指定的分隔符连接成字符串
    *
    * @param array          对象数组
    * @param seperateString 分隔符
    * @return 连接后的字符串
    */
    public static String arrayToString(Object[] array, String seperateString) {
        StringBuilder sb = new StringBuilder(array.length * 16);
        for (Object object : array) {
            sb.append(object).append(seperateString);
        }
        if (sb.length() > 0) {
            sb.setLength(sb.length() - seperateString.length());
        }
        return sb.toString();
    }

    /**
    * <p>获取嵌套在两个字符串之间的子串。仅返回第一个匹配。</p>
    *
    * <p>{@code null} 输入字符串返回 {@code null}。{@code null} 开始/结束字符串返回 {@code null}（不匹配）。
    * 空字符串（""）的开始和结束返回空字符串。</p>
    *
    * <pre>
    * StringUtils.substringBetween("wx[b]yz", "[", "]") = "b"
    * StringUtils.substringBetween(null, *, *)          = null
    * StringUtils.substringBetween(*, null, *)          = null
    * StringUtils.substringBetween(*, *, null)          = null
    * StringUtils.substringBetween("", "", "")          = ""
    * StringUtils.substringBetween("", "", "]")         = null
    * StringUtils.substringBetween("", "[", "]")        = null
    * StringUtils.substringBetween("yabcz", "", "")     = ""
    * StringUtils.substringBetween("yabcz", "y", "z")   = "abc"
    * StringUtils.substringBetween("yabczyabcz", "y", "z")   = "abc"
    * </pre>
    *
    * @param str   包含子串的字符串，可能为 空
    * @param open  子串前的字符串，可能为 空
    * @param close 子串后的字符串，可能为 空
    * @return 两个字符串之间的子串，如果没有匹配则返回 空
    * @since 2.0
    */
    public static String substringBetween(String str, String open, String close) {
        if (str == null || open == null || close == null) {
            return null;
        }
        int start = str.indexOf(open);
        if (start != -1) {
            int end = str.indexOf(close, start + open.length());
            if (end != -1) {
                return str.substring(start + open.length(), end);
            }
            return str.substring(start + open.length());
        }
        return null;
    }

    /**
    * 合并两个字符串数组为一个
    *
    * @param array1 第一个数组
    * @param array2 第二个数组
    * @return 合并后的数组
    */
    public static String[] joinStringArray(String[] array1, String[] array2) {
        List<String> l = new ArrayList<String>();
        Collections.addAll(l, array1);
        Collections.addAll(l, array2);
        return l.toArray(new String[0]);
    }


    /**
    * 忽略大小写替换所有匹配的子串<br/>
    * 替换ignore大小写("abcdecd", "Cd", "FF") = "abffeff"
    *
    * @param text      原文本
    * @param findtxt   要查找的子串
    * @param replacetxt 替换文本
    * @return 替换后的字符串
    */
    public static String replaceIgnoreCase(String text, String findtxt, String replacetxt) {
        if (text == null) {
            return null;
        }
        String str = text;
        if (findtxt == null || findtxt.length() == 0) {
            return str;
        }
        if (findtxt.length() > str.length()) {
            return str;
        }
        int counter = 0;
        String thesubstr;
        while ((counter < str.length()) && (str.substring(counter).length() >= findtxt.length())) {
            thesubstr = str.substring(counter, counter + findtxt.length());
            if (thesubstr.equalsIgnoreCase(findtxt)) {
                str = str.substring(0, counter) + replacetxt + str.substring(counter + findtxt.length());
                counter += replacetxt.length();
            } else {
                counter++;
            }
        }
        return str;
    }


    /**
    * 清除列名中的引号字符，如 `someCol` 或 "somecol" 或 [somecol]
    *
    * @param columnName 列名
    * @return 清除引号后的列名
    */
    public static String clearQuote(String columnName) {
        if (isEmpty(columnName)) {
            return columnName;
        }
        String s = replace(columnName, "`", "");
        s = replace(s, "\"", "");
        s = replace(s, "[", "");
        s = replace(s, "]", "");
        return s;
    }

    /**
    * 将对象列表转换为 "obj1,obj2...,objn" 格式的字符串
    *
    * @param lst 对象列表
    * @return 逗号分隔的字符串
    */
    public static String listToString(List<?> lst) {

        StringBuilder sb = new StringBuilder(Math.max(lst.size() * 16, 16));
        for (Object object : lst) {
            sb.append(object).append(",");
        }
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /**
    * 将对象数组转换为 "obj1,obj2...,objn" 格式的字符串（跳过第一个元素）
    *
    * @param array 对象数组
    * @return 逗号分隔的字符串（跳过第一个元素）
    */
    public static String arrayToStringButSkipFirst(Object[] array) {
        StringBuilder sb = new StringBuilder(array.length * 16);
        int i = 1;
        for (Object object : array) {
            if (i++ != 1) {
                sb.append(object).append(",");
            }

        }
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /**
    * 替换字符串中第一个出现的子串
    *
    * @param originString 原始字符串
    * @param oldPattern   要替换的旧模式
    * @param newPattern   替换的新模式
    * @return 替换后的字符串
    */
    public static String replaceFirst(String originString, String oldPattern, String newPattern) {
        if (isEmpty(originString) || isEmpty(oldPattern) || newPattern == null) {
            return originString;
        }
        StringBuilder sb = new StringBuilder(Math.max(originString.length() + 16, 16));
        int pos = 0;
        int index = originString.indexOf(oldPattern);
        int patLen = oldPattern.length();
        if (index >= 0) {
            sb.append(originString.substring(pos, index));
            sb.append(newPattern);
            pos = index + patLen;
        }
        sb.append(originString.substring(pos));
        return sb.toString();
    }

    /**
    * 检查对象值是否有效（非空），对于 charsequence 检查是否非空白
    *
    * @param object 待检查的对象
    * @return 如果有效则返回 true
    */
    public static boolean checkValNotNull(Object object) {
        if (object instanceof CharSequence) {
            return isNotEmpty((CharSequence) object);
        }
        return object != null;
    }

    /**
    * SQL
    */
    private static final Pattern SQL_SYNTAX_PATTERN = Pattern.compile("(insert|delete|update|select|create|drop|truncate|grant|alter|deny|revoke|call|execute|exec|declare|show|rename|set)" +
            "\\s+.*(into|from|set|where|table|database|view|index|on|cursor|procedure|trigger|for|password|union|and|or)|(select\\s*\\*\\s*from\\s+)", Pattern.CASE_INSENSITIVE);
    /**
    * '   ;               SQL
    */
    private static final Pattern SQL_COMMENT_PATTERN = Pattern.compile("'.*(or|union|--|#|/*|;)", Pattern.CASE_INSENSITIVE);

    /**
    * SQL 注入替换空白字符
    * <ul>
    *     <li>\n       </li>
    *     <li>\t                </li>
    *     <li>\s       </li>
    *     <li>\r       </li>
    * </ul>
    *
    * @param str 输入字符串
    * @return sqlinjection替换blank的结果
    */
    public static String sqlInjectionReplaceBlank(String str) {
        if (check(str)) {
            /**
            * SQL                         SQL
            */
            Matcher matcher = REPLACE_BLANK.matcher(str);
            str = matcher.replaceAll("");
        }
        return str;
    }

    /**
    * 检查 SQL 注入关键字
    *
    * @param value 待检查的值
    * @return 如果包含 SQL 注入关键字则返回 true，否则返回 false
    */
    private static boolean check(String value) {
        Objects.requireNonNull(value);

        return SQL_COMMENT_PATTERN.matcher(value).find() || SQL_SYNTAX_PATTERN.matcher(value).find();
    }

    /**
    * 将逗号分隔的列表（如 CSV 行）转换为字符串数组
    *
    * @param str 输入字符串
    * @return 字符串数组，输入为空时返回空数组
    */
    public static String[] commaDelimitedListToStringArray(String str) {
        return delimitedListToStringArray(str, ",");
    }

    /**
    * 按分隔符分割后获取指定偏移量的子串
    *
    * @param str       输入字符串
    * @param separator 分隔符
    * @param offset    偏移量
    * @return 分割后的第 偏移量 个子串
    */
    public static String after(String str, String separator, int offset) {
        String[] split = str.split(separator);
        if (split.length < offset) {
            return CommonConstant.SYMBOL_EMPTY;
        }

        return split[offset];
    }

    /**
    * 按分隔符分割后获取指定范围的子串（用分隔符重新连接）
    *
    * @param str       输入字符串
    * @param separator 分隔符
    * @param offset    起始偏移量
    * @param offset2   结束偏移量
    * @return 指定范围子串用分隔符连接后的结果
    */
    public static String between(String str, String separator, int offset, int offset2) {
        String[] split = str.split(separator);
        if (split.length < offset) {
            return CommonConstant.SYMBOL_EMPTY;
        }

        if (offset2 <= offset) {
            return CommonConstant.SYMBOL_EMPTY;
        }

        return Joiner.on(separator).join((Object[]) ArrayUtils.subArray(split, offset, offset2));
    }

    /**
    * 比较两个字符串是否相等（安全比较，防止时序攻击）
    *
    * @param source 源字符串
    * @param target 目标字符串
    * @return 如果相等则返回 true
    */
    public static boolean equals(String source, String target) {
        if (null == source || null == target) {
            return false;
        }

        if (source.length() != target.length()) {
            return false;
        }
        int length = source.length();
        for (int i = 0; i < length; i++) {
            char s = source.charAt(i);
            if ((s ^ target.charAt(i)) == 1) {
                return false;
            }
        }
        return true;
    }

    /**
    * 安全比较两个字符串是否相等（防止时序攻击）
    *
    * @param source 源字符串
    * @param target 目标字符串
    * @return 如果相等则返回 true
    */
    public static boolean safeEquals(String source, String target) {
        if (null == source || null == target) {
            return false;
        }

        if (source.length() != target.length()) {
            return false;
        }

        int equal = 0;
        int length = source.length();
        for (int i = 0; i < length; i++) {
            char s = source.charAt(i);
            equal |= s ^ target.charAt(i);
        }

        return equal == 0;
    }

    /**
    * 获取有效字符串，如果值为 空、空字符串或 "空"/"无" 则返回默认值
    *
    * @param value        待检查的值
    * @param defaultValue 默认值
    * @return 有效字符串或默认值
    */
    public static String ifValid(String value, String defaultValue) {
        if (null == value) {
            return defaultValue;
        }

        if (isEmpty(value)) {
            return defaultValue;
        }

        if (NONE.equals(value) || NULL.equalsIgnoreCase(value)) {
            return defaultValue;
        }

        return value;
    }


    /**
    * <p>从指定字符串中安全地获取子串，避免异常。</p>
    *
    * <p>负数的起始位置表示从字符串末尾倒数 {@code n} 个字符开始。</p>
    *
    * <p>{@code null} 字符串返回 {@code null}。空字符串（""）返回空字符串。</p>
    *
    * <pre>
    * StringUtils.substring(null, *)   = null
    * StringUtils.substring("", *)     = ""
    * StringUtils.substring("abc", 0)  = "abc"
    * StringUtils.substring("abc", 2)  = "c"
    * StringUtils.substring("abc", 4)  = ""
    * StringUtils.substring("abc", -2) = "bc"
    * StringUtils.substring("abc", -4) = "abc"
    * </pre>
    *
    * @param str   待获取子串的字符串，可能为 空
    * @param start 起始位置，负数表示从字符串末尾倒数
    * @return 从起始位置开始的子串，如果输入为 空 则返回 空
    */
    public static String substring(final String str, int start) {
        if (str == null) {
            return null;
        }


        if (start < 0) {
            start = str.length() + start;
        }

        if (start < 0) {
            start = 0;
        }
        if (start > str.length()) {
            return CommonConstant.SYMBOL_EMPTY;
        }

        return str.substring(start);
    }

    /**
    * <p>从指定字符串中安全地获取子串，避免异常。</p>
    *
    * <p>负数的起始/结束位置表示从字符串末尾倒数 {@code n} 个字符。</p>
    *
    * <p>返回的子串从 {@code start} 位置开始，到 {@code end} 位置结束（不包含）。
    * 所有位置计数从零开始。负数的起始和结束位置可用于指定相对于字符串末尾的偏移。</p>
    *
    * <p>如果 {@code start} 不在 {@code end} 的左侧，返回空字符串。</p>
    *
    * <pre>
    * StringUtils.substring(null, *, *)    = null
    * StringUtils.substring("", * ,  *)    = "";
    * StringUtils.substring("abc", 0, 2)   = "ab"
    * StringUtils.substring("abc", 2, 0)   = ""
    * StringUtils.substring("abc", 2, 4)   = "c"
    * StringUtils.substring("abc", 4, 6)   = ""
    * StringUtils.substring("abc", 2, 2)   = ""
    * StringUtils.substring("abc", -2, -1) = "b"
    * StringUtils.substring("abc", -4, 2)  = "ab"
    * </pre>
    *
    * @param str   待获取子串的字符串，可能为 空
    * @param start 起始位置，负数表示从字符串末尾倒数
    * @param end   结束位置（不包含），负数表示从字符串末尾倒数
    * @return 从起始位置到结束位置的子串，如果输入为 空 则返回 空
    */
    public static String substring(final String str, int start, int end) {
        if (str == null) {
            return null;
        }


        if (end < 0) {
            end = str.length() + end;
        }
        if (start < 0) {
            start = str.length() + start;
        }


        if (end > str.length()) {
            end = str.length();
        }


        if (start > end) {
            return CommonConstant.SYMBOL_EMPTY;
        }

        if (start < 0) {
            start = 0;
        }
        if (end < 0) {
            end = 0;
        }

        return str.substring(start, end);
    }


    /**
    * <p>检查 CharSequence 是否以指定后缀结尾。</p>
    *
    * <p>{@code null} 值会被安全处理。两个 {@code null}
    * 引用被视为相等。比较时区分大小写。</p>
    *
    * <pre>
    * StringUtils.endsWith(null, null)      = true
    * StringUtils.endsWith(null, "def")     = false
    * StringUtils.endsWith("abcdef", null)  = false
    * StringUtils.endsWith("abcdef", "def") = true
    * StringUtils.endsWith("ABCDEF", "def") = false
    * StringUtils.endsWith("ABCDEF", "cde") = false
    * StringUtils.endsWith("ABCDEF", "")    = true
    * </pre>
    *
    * @param str    待检查的 charsequence，可能为 空
    * @param suffix 要查找的后缀，可能为 空
    * @return 如果字符串以指定后缀结尾（区分大小写），或两者都为 {@code null}，则返回 {@code true}
    * @see String#endsWith(String)
    * @since 2.4
    * @since 3.0 Changed signature from endsWith(String, String) to endsWith(CharSequence, CharSequence)
    */
    public static boolean endsWith(final CharSequence str, final CharSequence suffix) {
        return endsWith(str, suffix, false);
    }

    /**
    * <p>检查 CharSequence 是否以指定后缀结尾（可选是否忽略大小写）。</p>
    *
    * @param str        待检查的 charsequence，可能为 空
    * @param suffix     要查找的后缀，可能为 空
    * @param ignoreCase 是否忽略大小写
    * @return 如果字符串以指定后缀结尾，或两者都为 {@code null}，则返回 {@code true}
    * @see String#endsWith(String)
    */
    private static boolean endsWith(final CharSequence str, final CharSequence suffix, final boolean ignoreCase) {
        if (str == null || suffix == null) {
            return str == suffix;
        }
        if (suffix.length() > str.length()) {
            return false;
        }
        final int strOffset = str.length() - suffix.length();
        return regionMatches(str, ignoreCase, strOffset, suffix, 0, suffix.length());
    }

    /**
    * <p>检查 CharSequence 是否不包含指定字符集中的任何字符。</p>
    *
    * <p>{@code null} CharSequence 返回 {@code true}。
    * {@code null} 无效字符数组返回 {@code true}。
    * 空 charsequence (长度()=0) 始终返回 true。</p>
    *
    * <pre>
    * StringUtils.containsNone(null, *)       = true
    * StringUtils.containsNone(*, null)       = true
    * StringUtils.containsNone("", *)         = true
    * StringUtils.containsNone("ab", '')      = true
    * StringUtils.containsNone("abab", 'xyz') = true
    * StringUtils.containsNone("ab1", 'xyz')  = true
    * StringUtils.containsNone("abz", 'xyz')  = false
    * </pre>
    *
    * @param cs          待检查的 charsequence，可能为 空
    * @param searchChars 无效字符数组，可能为 空
    * @return 如果不包含任何无效字符，或为 空，则返回 true
    * @since 2.0
    * @since 3.0 Changed signature from containsNone(String, char[]) to containsNone(CharSequence, char...)
    */
    public static boolean containsNone(final CharSequence cs, final char... searchChars) {
        if (cs == null || searchChars == null) {
            return true;
        }
        final int csLen = cs.length();
        final int csLast = csLen - 1;
        final int searchLen = searchChars.length;
        final int searchLast = searchLen - 1;
        for (int i = 0; i < csLen; i++) {
            final char ch = cs.charAt(i);
            for (int j = 0; j < searchLen; j++) {
                if (searchChars[j] == ch) {
                    if (Character.isHighSurrogate(ch)) {
                        if (j == searchLast) {

                            return false;
                        }
                        if (i < csLast && searchChars[j + 1] == cs.charAt(i + 1)) {
                            return false;
                        }
                    } else {

                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
    * <p>检查 CharSequence 是否不包含指定字符串中的任何字符。</p>
    *
    * <p>{@code null} CharSequence 返回 {@code true}。
    * {@code null} 无效字符字符串返回 {@code true}。
    * 空字符串（""）始终返回 true。</p>
    *
    * <pre>
    * StringUtils.containsNone(null, *)       = true
    * StringUtils.containsNone(*, null)       = true
    * StringUtils.containsNone("", *)         = true
    * StringUtils.containsNone("ab", "")      = true
    * StringUtils.containsNone("abab", "xyz") = true
    * StringUtils.containsNone("ab1", "xyz")  = true
    * StringUtils.containsNone("abz", "xyz")  = false
    * </pre>
    *
    * @param cs           待检查的 charsequence，可能为 空
    * @param invalidChars 包含无效字符的字符串，可能为 空
    * @return 如果不包含任何无效字符，或为 空，则返回 true
    * @since 2.0
    * @since 3.0 Changed signature from containsNone(String, String) to containsNone(CharSequence, String)
    */
    public static boolean containsNone(final CharSequence cs, final String invalidChars) {
        if (invalidChars == null) {
            return true;
        }
        return containsNone(cs, invalidChars.toCharArray());
    }

    /**
    * <p>将字符串首字母转为小写，其余字符不变。</p>
    *
    * <p>对于基于单词的算法，请参见 {@link org.apache.commons.lang3.text.WordUtils#uncapitalize(String)}。
    * {@code null} 输入返回 {@code null}。</p>
    *
    * <pre>
    * StringUtils.uncapitalize(null)  = null
    * StringUtils.uncapitalize("")    = ""
    * StringUtils.uncapitalize("cat") = "cat"
    * StringUtils.uncapitalize("Cat") = "cat"
    * StringUtils.uncapitalize("CAT") = "cAT"
    * </pre>
    *
    * @param str 待处理的字符串，可能为 空
    * @return 首字母小写后的字符串，如果输入为 空 则返回 空
    * @see org.apache.commons.lang3.text.WordUtils#uncapitalize(String)
    * @see #capitalize(String)
    * @since 2.0
    */
    public static String uncapitalize(final String str) {
        final int strLen = length(str);
        if (strLen == 0) {
            return str;
        }

        final int firstCodepoint = str.codePointAt(0);
        final int newCodePoint = Character.toLowerCase(firstCodepoint);
        if (firstCodepoint == newCodePoint) {

            return str;
        }

        final int[] newCodePoints = new int[strLen];
        int outOffset = 0;
        newCodePoints[outOffset++] = newCodePoint;
        for (int inOffset = Character.charCount(firstCodepoint); inOffset < strLen; ) {
            final int codepoint = str.codePointAt(inOffset);
            newCodePoints[outOffset++] = codepoint;
            inOffset += Character.charCount(codepoint);
        }
        return new String(newCodePoints, 0, outOffset);
    }

    /**
    * 判断关键字是否与值匹配（支持通配符 * 和 ?）
    *
    * @param keyword 关键字（可包含 * 或 ? 通配符）
    * @param value   待匹配的值
    * @return 如果匹配则返回 true
    */
    public static boolean isSimpleMatch(String keyword, String value) {
        if (isEmpty(keyword)) {
            return true;
        }

        if (keyword.contains("*") || keyword.contains("?")) {
            return PathMatcher.INSTANCE.match(keyword, value);
        }

        return keyword.equals(value) || value.matches(keyword);
    }

    /**
    * 判断输入字符串是否包含运算符（+ - * / % > < = !）
    *
    * @param input 输入字符串
    * @return 如果包含运算符则返回 true
    */
    public static boolean containsOperator(String input) {
        String regex = "\\s*[+\\-\\*/%><=!]+\\s*";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(input);
        return matcher.find();
    }

    /**
    * 判断输入字符串是否包含函数调用（如 func()）
    *
    * @param input 输入字符串
    * @return 如果包含函数调用则返回 true
    */
    public static boolean containsFunction(String input) {
        String regex = "\\s*[a-zA-Z]+\\(.*\\)\\s*";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(input);
        return matcher.find();
    }

    /**
    * 替换字符串中的重复子串
    *
    * @param source       源字符串
    * @param repeatSymbol 要被替换的重复符号
    * @param newSymbol    替换后的新符号
    * @return 替换后的字符串
    */
    public static String removeRepeat(String source, String repeatSymbol, String newSymbol) {
        return null == source ? null : source.replace(repeatSymbol, newSymbol);
    }

    /**
    * 将 Unicode 编码的字符串转换为 UTF-8 编码
    *
    * @param str 输入字符串
    * @return UTF-8 编码的字符串
    */
    public static String unicodeToUtf8(String str) {
        try {
            byte[] bytes = str.getBytes("Unicode");
            return new String(bytes, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
    * 将 UTF-8 编码的字符串转换为 Unicode 编码
    *
    * @param str 输入字符串
    * @return Unicode 编码的字符串
    */
    public static String utf8ToUnicode(String str) {
        try {
            byte[] bytes = str.getBytes("UTF-8");
            return new String(bytes, "Unicode");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }


    /**
    * 对字符串进行 URL 编码
    *
    * @param word 待编码的字符串
    * @return URL 编码后的字符串
    */
    public static String encode(String word) {
        try {
            return URLEncoder.encode(word, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException var2) {
            return "";
        }
    }

    /**
    * 对字符串进行 URL 解码
    *
    * @param word 待解码的字符串
    * @return URL 解码后的字符串
    */
    public static String decode(String word) {
        try {
            return URLDecoder.decode(word, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException var2) {
            return "";
        }
    }

    /**
    * 将对象转换为字符串表示
    *
    * <p>null 返回空字符串，Iterable 返回逗号分隔的字符串，其他返回 toString()</p>
    *
    * @param value 待转换的对象
    * @return 字符串表示
    */
    public static String toString(Object value) {
 // 空
        if (ObjectUtils.isNull(value)) {
            return CommonConstant.SYMBOL_EMPTY;
        }

        if (value instanceof Iterable) {
            return String.join(SYMBOL_COMMA, (Iterable) value);
        }
        return value.toString();
    }


    /**
    * 判断字符串是否为 "空"（忽略大小写），如果是则返回默认对象
    *
    * @param string 输入字符串
    * @param o      默认对象
    * @return 如果字符串为 "空" 则返回默认对象，否则返回字符串本身
    */
    public static <T> T isNullString(String string, T o) {
        return NULL.equalsIgnoreCase(string) ? o : (T) string;
    }

    /**
    * 判断逗号分隔的字符串中是否包含指定 标识
    *
    * @param ids 逗号分隔的 标识 字符串
    * @param id  要查找的 标识
    * @return 如果包含则返回 true
    */
    public static boolean transArrayContains(String ids, String id) {
        if (StringUtils.isEmpty(ids)) {
            return false;
        }

        List<String> strings = Splitter.on(",").trimResults().omitEmptyStrings().splitToList(ids);
        return strings.contains(id);
    }

    /**
    * 将字符串数组中的所有元素转换为小写
    *
    * @param strArr 字符串数组
    * @return 转换后的字符串数组，如果输入为 空 则返回 空
    */
    public static String[] toLowerCase(String[] strArr) {
 // 空
        if (strArr == null) {
            return null;
        }

        //                                                             
        String[] res = new String[strArr.length];
        for (int i = 0; i < strArr.length; i++) {
            //                                                          
            res[i] = strArr[i].toLowerCase();
        }
        //                                        
        return res;
    }

    /**
    * 判断字符串数组中是否包含指定字符串
    *
    * @param str    待查找的字符串
    * @param strArr 字符串数组
    * @return 如果 strarr 为 空 返回 false，包含则返回 true
    */
    public static boolean contains(String str, String[] strArr) {
        if (strArr == null) {
            return false;
        }

        for (String arrStr : strArr) {
            if (str.contains(arrStr)) {
                return true;
            }
        }

        return false;
    }

    /**
    * 获取字符串的第一个字符
    *
    * @param value 输入字符串
    * @return 第一个字符的字符串形式，空或 空 返回空字符串
    */
    public static String getFirst(String value) {
        if (StringUtils.isEmpty(value)) {
            return SYMBOL_EMPTY;
        }
        return value.substring(0, 1);
    }


    /**
    * 判断字符串是否为非空白（not blank）
    *
    * <p>字符串不为 null 且去除首尾空白后不为空，则返回 true。</p>
    *
    * @param keyword 待检查的字符串
    * @return 如果字符串非空白返回 true
    */
    public static boolean hasText(String keyword) {
        return null != keyword && !keyword.trim().isEmpty();
    }

    /**
    * 忽略大小写查找字符串中子串的起始位置
    *
    * @param value 源字符串
    * @param s     待查找的子串
    * @return 子串的起始位置，未找到返回 {@code -1}
    */
    public static int indexOfIgnoreCase(String value, String s) {
        if (value == null || s == null) {
            return INDEX_NOT_FOUND;
        }
        return value.toLowerCase().indexOf(s.toLowerCase());
    }

    /**
    * 将字符串首字母转为大写
    *
    * <p>如果输入为 {@code null} 或空字符串，则原样返回。</p>
    *
    * <pre>
    * StringUtils.firstUpperCase(null)  = null
    * StringUtils.firstUpperCase("")    = ""
    * StringUtils.firstUpperCase("abc") = "Abc"
    * StringUtils.firstUpperCase("ABC") = "ABC"
    * StringUtils.firstUpperCase("a")   = "A"
    * </pre>
    *
    * @param str 待处理的字符串
    * @return 首字母大写后的字符串，{@code null} 返回 {@code null}
    */
    public static String firstUpperCase(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        if (Character.isUpperCase(str.charAt(0))) {
            return str;
        }
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    /**
    * 将字符串首字母转为小写
    *
    * <p>如果输入为 {@code null} 或空字符串，则原样返回。</p>
    *
    * <pre>
    * StringUtils.firstLowerCase(null)  = null
    * StringUtils.firstLowerCase("")    = ""
    * StringUtils.firstLowerCase("ABC") = "aBC"
    * StringUtils.firstLowerCase("abc") = "abc"
    * StringUtils.firstLowerCase("A")   = "a"
    * </pre>
    *
    * @param str 待处理的字符串
    * @return 首字母小写后的字符串，{@code null} 返回 {@code null}
    */
    public static String firstLowerCase(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        if (Character.isLowerCase(str.charAt(0))) {
            return str;
        }
        return Character.toLowerCase(str.charAt(0)) + str.substring(1);
    }


    /**
    * region匹配
    * @param cs C
    * @param ignoreCase ignore大小写
    * @param thisStart this启动
    * @param substring 子串
    * @param start 启动
    * @param length 长度
    */
    static boolean regionMatches(final CharSequence cs, final boolean ignoreCase, final int thisStart,
                                 final CharSequence substring, final int start, final int length) {
        if (cs instanceof String && substring instanceof String) {
            return ((String) cs).regionMatches(ignoreCase, thisStart, (String) substring, start, length);
        }
        int index1 = thisStart;
        int index2 = start;
        int tmpLen = length;

 // Java.lang.字符串                    NPE
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

            //     String.regionMatches()
            final char u1 = Character.toUpperCase(c1);
            final char u2 = Character.toUpperCase(c2);
            if (u1 != u2 && Character.toLowerCase(u1) != Character.toLowerCase(u2)) {
                return false;
            }
        }

        return true;
    }

    /**
    * 将字节数组转换为十六进制字符串
    *
    * <p>委托给 {@link Hex#encodeHexString(byte[])} 实现。</p>
    *
    * @param data 字节数组
    * @return 小写十六进制字符串
    * @see Hex#encodeHexString(byte[])
    */
    public static String bytes2string(byte[] data) {
        return Hex.encodeHexString(data);
    }
}
