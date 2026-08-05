package com.chua.common.support.constant;



/**
 * 字符常量接口
 * <p>
 * 定义了一系列常用的字符常量，用于字符串处理、解析、分割等场景。
 * </p>
 * <ul>
 * <li>包含基本的空白与换行控制字符</li>
 * <li>包含常见的标点与分隔符号</li>
 * <li>包含成对的括号与引号</li>
 * <li>包含大小写字母边界常量</li>
 * </ul>
 *
 * @author CH
 * @since 2024-01-01
 * @version 1.0.0
 */
public interface CharConstant {
    /**
     * 空格 {@code ' '}
     */
    char SYMBOL_SPACE = ' ';
    /**
     * 制表符 {@code '\t'}
     */
    char SYMBOL_TAB = '\t';
    /**
     * 点号 {@code '.'}
     */
    char SYMBOL_DOT = '.';
    /**
     * 正斜杠 {@code '/'}
     */
    char SYMBOL_SLASH = '/';
    /**
     * 反斜杠 {@code '\\'}
     */
    char SYMBOL_BACKSLASH = '\\';
    /**
     * 回车符 {@code '\r'}
     */
    char SYMBOL_CR = '\r';
    /**
     * 换行符 {@code '\n'}
     */
    char SYMBOL_LF = '\n';
    /**
     * 连字符/减号 {@code '-'}
     */
    char SYMBOL_DASHED = '-';
    /**
     * 下划线 {@code '_'}
     */
    char SYMBOL_UNDERLINE = '_';
    /**
     * 逗号 {@code ','}
     */
    char SYMBOL_COMMA = ',';
    /**
     * 左大括号/起始分隔符 {@code '{'}
     */
    char SYMBOL_DELIM_START = '{';
    /**
     * 右大括号/结束分隔符 {@code '}'}
     */
    char SYMBOL_DELIM_END = '}';
    /**
     * 左中括号 {@code '['}
     */
    char SYMBOL_BRACKET_START = '[';
    /**
     * 右中括号 {@code ']'}
     */
    char SYMBOL_BRACKET_END = ']';
    /**
     * 双引号 {@code '"'}
     */
    char SYMBOL_DOUBLE_QUOTES = '"';
    /**
     * 单引号 {@code '\''}
     */
    char SYMBOL_SINGLE_QUOTE = '\'';
    /**
     * 和号 {@code '&'}
     */
    char SYMBOL_AMP = '&';
    /**
     * 冒号 {@code ':'}
     */
    char SYMBOL_COLON = ':';
    /**
     * At符号 {@code '@'}
     */
    char SYMBOL_AT = '@';
    /**
     * 小写字母a {@code 'a'}
     */
    char SYMBOL_LOWER_A = 'a';
    /**
     * 小写字母z {@code 'z'}
     */
    char SYMBOL_LOWER_Z = 'z';
    /**
     * 大写字母A {@code 'A'}
     */
    char SYMBOL_UPPER_A = 'A';
    /**
     * 大写字母Z {@code 'Z'}
     */
    char SYMBOL_UPPER_Z = 'Z';

    /**
     * 空字符 {@code '\0'}
     */
    char SYMBOL_NULL = '\0';
    /**
     * 换行符 {@code '\n'}
     */
    char SYMBOL_NEWLINE = '\n';

    /**
     * 数字0 {@code '0'}
     */
    char SYMBOL_ZERO = '0';
    /**
     * 数字1 {@code '1'}
     */
    char SYMBOL_ONE = '1';
    /**
     * 数字9 {@code '9'}
     */
    char SYMBOL_NINE = '9';

}
