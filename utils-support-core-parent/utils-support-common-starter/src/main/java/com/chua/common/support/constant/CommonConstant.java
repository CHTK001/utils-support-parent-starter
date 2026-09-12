package com.chua.common.support.constant;

import java.text.DecimalFormat;

import static com.chua.common.support.constant.ValueConstant.SYMBOL_EMPTY_STRING;

/**
* 通用常量接口，集中定义项目中使用的各种常量。
*
* <p>该接口继承自 {@link NameConstant}，在名称常量的基础上扩展了以下类别的常量：
* <ul>
*   <li><b>符号常量</b> — 各种标点符号、运算符的字符串和字符形式</li>
*   <li><b>缓冲区大小</b> — 默认缓冲区的尺寸、填充限制</li>
*   <li><b>空值常量</b> — 各种类型的空数组、空 Properties 等</li>
*   <li><b>格式常量</b> — 数字格式化对象</li>
*   <li><b>协议分隔符</b> — JAR/WAR URL 中的路径分隔符</li>
*   <li><b>操作系统属性</b> — 当前 OS 名称</li>
*   <li><b>其他</b> — 常见值如 true/false、CRLF、通配符等</li>
* </ul>
*
* <p>正则表达式相关常量请参考 {@link RegexConstant}。</p>
*
* @author CH
* @version 1.0.0
* @since 2024-01-01
 */
public final class CommonConstant {
    private CommonConstant() {}


    // ========================== 空值常量 ==========================
    /**
    * 空字符串常量（符号命名风格）。
     */
    public static final String SYMBOL_EMPTY = SYMBOL_EMPTY_STRING;
    /**
    * 空字符串常量。
     */
    public static final String EMPTY_STRING = SYMBOL_EMPTY_STRING;



    // ========================== 索引 / 查找 ==========================

    /**
    * 未找到索引标记常量 {@value}。
     */
    public static final int INDEX_NOT_FOUND = -1;

    /**
    * 未找到索引标记常量 {@value}。
     */
    public static final String INDEX_NOT_FOUND_STRING = String.valueOf(INDEX_NOT_FOUND);
    // ========================== 操作系统属性 ==========================

    /**
    * 操作系统名称常量（通过 System.getProperty("os.name") 获取）。
     */
    public static final String OS_NAME = System.getProperty("os.name");

    // ========================== 文件分隔符 ==========================

    /**
    * JAR URL 分隔符 {@value}。格式：jar:file:/path.jar!/entry
     */
    public static final String JAR_URL_SEPARATOR = "!/";
    /**
    * WAR URL 分隔符 {@value}（Tomcat 环境）。
     */
    public static final String WAR_URL_SEPARATOR = "*/";

    // ========================== 协议常量 ==========================

    /**
    * file 协议字符串常量 {@value}。
     */
    public static final String FILE_PROTOCOL = "file";

    // ========================== 格式常量 ==========================

    /**
    * 小数格式化对象，格式 {@code ##0.000}（至少 1 位整数 + 3 位小数）。
     */
    public static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("##0.000");

    // ========================== 国际化 ==========================

    /**
    * 国际化资源文件基名 {@value}。
     */
    public static final String RESOURCE_MESSAGE = "language/message";

    // ========================== 十六进制字符数组 ==========================

    /**
    * 小写十六进制字符集（0-9, a-f）。
     */
    public static final char[] DIGITS_LOWER = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};
    /**
    * 大写十六进制字符集（0-9, A-F）。
     */
    public static final char[] DIGITS_UPPER = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'};

    // ========================== 填充限制 ==========================

    /**
    * 字符串填充最大长度限制 {@value}。
    * 用于 leftPad、rightPad 等方法，超过此限制时采用字符串拼接方式代替逐字符填充。
     */
    public static final int SYMBOL_PAD_LIMIT = 8192;

    // ========================== 换行 / 空格 ==========================

    /**
    * Windows 换行符 {@value}（CR+LF）。
     */
    public static final String CRLF = "\r\n";
    /**
    * 空白字符集合（空格、换行、回车、换页、制表符）。
     */
    public static final String WHITESPACE = " \n\r\f\t";

    // ========================== 符号常量（String 类型） ==========================

    /**
    * 空格符号 {@value}。
     */
    public static final String SYMBOL_BLANK = " ";
    /**
    * 空格符号（引用自 SYMBOL_BLANK）。
     */
    public static final String SYMBOL_SPACE = SYMBOL_BLANK;
    /**
    * 点号符号 {@value}。
     */
    public static final String SYMBOL_DOT = ".";
    /**
    * 逗号符号 {@value}。
     */
    public static final String SYMBOL_COMMA = ",";
    /**
    * 分号符号 {@value}。
     */
    public static final String SYMBOL_SEMICOLON = ";";
    /**
    * 冒号符号 {@value}。
     */
    public static final String SYMBOL_COLON = ":";
    /**
    * 双冒号符号 {@value}（方法引用、作用域解析）。
     */
    public static final String SYMBOL_DOUBLE_COLON = "::";

    /**
    * 双引号符号 {@value}。
     */
    public static final String SYMBOL_QUOTE = "\"";
    /**
    * 单引号符号 {@value}。
     */
    public static final String SYMBOL_SINGLE_QUOTATION_MARK = "'";
    /**
    * 竖线符号 {@value}。
     */
    public static final String SYMBOL_PIPE = "|";
    /**
    * 邮箱符号 {@value}。
     */
    public static final String SYMBOL_AT = "@";
    /**
    * 井号符号 {@value}。
     */
    public static final String SYMBOL_HASH = "#";
    /**
    * 美元符号 {@value}。
     */
    public static final String SYMBOL_DOLLAR = "$";

    /**
    * 左圆括号 {@value}。
     */
    public static final String SYMBOL_LEFT_BRACKETS = "(";
    /**
    * 左圆括号（引用自 SYMBOL_LEFT_BRACKETS）。
     */
    public static final String SYMBOL_LEFT_BRACKET = SYMBOL_LEFT_BRACKETS;
    /**
    * 右圆括号 {@value}。
     */
    public static final String SYMBOL_RIGHT_BRACKETS = ")";
    /**
    * 右圆括号（引用自 SYMBOL_RIGHT_BRACKETS）。
     */
    public static final String SYMBOL_RIGHT_BRACKET = SYMBOL_RIGHT_BRACKETS;
    /**
    * 左大括号 {@value}。
     */
    public static final String SYMBOL_LEFT_BRACE = "{";
    /**
    * 左大括号（引用自 SYMBOL_LEFT_BRACE）。
     */
    public static final String SYMBOL_LEFT_BIG_PARENTHESES = SYMBOL_LEFT_BRACE;
    /**
    * 右大括号 {@value}。
     */
    public static final String SYMBOL_RIGHT_BRACE = "}";
    /**
    * 右大括号（引用自 SYMBOL_RIGHT_BRACE）。
     */
    public static final String SYMBOL_RIGHT_BIG_PARENTHESES = SYMBOL_RIGHT_BRACE;

    /**
    * 左尖括号 {@value}。
     */
    public static final String SYMBOL_LEFT_CHEV = "<";
    /**
    * 右尖括号 {@value}。
     */
    public static final String SYMBOL_RIGHT_CHEV = ">";
    /**
    * 叹号符号 {@value}。
     */
    public static final String SYMBOL_EXCLAMATION_MARK = "!";

    /**
    * 波浪线符号 {@value}。
     */
    public static final String SYMBOL_WAVY_LINE = "~";
    /**
    * 星号符号 {@value}（乘法、通配符）。
     */
    public static final String SYMBOL_ASTERISK = "*";
    /**
    * 问号符号 {@value}（三元、通配符、查询参数）。
     */
    public static final String SYMBOL_QUESTION = "?";
    /**
    * 百分号符号 {@value}（取模、格式化）。
     */
    public static final String SYMBOL_PERCENT = "%";
    /**
    * 与符号 {@value}。
     */
    public static final String SYMBOL_AND = "&";
    /**
    * 等号符号 {@value}。
     */
    public static final String SYMBOL_EQUALS = "=";
    /**
    * 减号符号 {@value}。
     */
    public static final String SYMBOL_MINUS = "-";
    /**
    * 短横线符号（引用自 SYMBOL_MINUS）。
     */
    public static final String SYMBOL_DASH = SYMBOL_MINUS;
    /**
    * 短横线符号（引用自 SYMBOL_DASH）。
     */
    public static final String SYMBOL_MINS = SYMBOL_DASH;
    /**
    * 加号符号 {@value}。
     */
    public static final String SYMBOL_PLUS = "+";
    /**
    * 下划线符号 {@value}。
     */
    public static final String SYMBOL_UNDERLINE = "_";

    /**
    * 左方括号 {@value}。
     */
    public static final String SYMBOL_LEFT_SQUARE_BRACKET = "[";
    /**
    * 右方括号 {@value}。
     */
    public static final String SYMBOL_RIGHT_SQUARE_BRACKET = "]";
    /**
    * 左斜杠符号 {@value}（路径分隔、除法）。
     */
    public static final String SYMBOL_LEFT_SLASH = "/";
    /**
    * 右斜杠符号 {@value}（Windows 路径分隔、转义）。
     */
    public static final String SYMBOL_RIGHT_SLASH = "\\";

    /**
    * 换行符 {@value}。
     */
    public static final String SYMBOL_NEWLINE = "\n";
    /**
    * 制表符 {@value}。
     */
    public static final String SYMBOL_TAB = "\t";
    /**
    * 回车符 {@value}。
     */
    public static final String SYMBOL_RETURN = "\r";
    /**
    * 双点号 {@value}（范围、父目录）。
     */
    public static final String SYMBOL_DOUBLE_DOT = "..";
    /**
    * 双左斜杠 {@value}（注释）。
     */
    public static final String SYMBOL_DOUBLE_LEFT_SLASH = "//";
    /**
    * 逻辑与 {@value}。
     */
    public static final String SYMBOL_DOUBLE_AND = "&&";
    /**
    * 逻辑或 {@value}。
     */
    public static final String SYMBOL_DOUBLE_PIPE = "||";
    /**
    * 双星号 {@value}（Ant 风格通配符）。
     */
    public static final String SYMBOL_ASTERISK_ANY = "**";

    /**
    * 数字零的字符串形式 {@value}。
     */
    public static final String SYMBOL_NUMBERS_ZERO_STRING = "0";

    /**
    * 点号的字符串形式 {@value}。
     */
    public static final String SYMBOL_DOT_STRING = ".";

    // ========================== 符号常量（char 类型） ==========================

    /**
    * 点号的字符形式 {@code '.'}。
     */
    public static final char SYMBOL_DOT_CHAR = '.';
    /**
    * 逗号的字符形式 {@code ','}。
     */
    public static final char SYMBOL_COMMA_CHAR = ',';
    /**
    * 分号的字符形式 {@code ';'}。
     */
    public static final char SYMBOL_SEMICOLON_CHAR = ';';
    /**
    * 冒号的字符形式 {@code ':'}。
     */
    public static final char SYMBOL_COLON_CHAR = ':';
    /**
    * 双引号的字符形式 {@code '"'}。
     */
    public static final char SYMBOL_QUOTE_CHAR = '"';
    /**
    * 单引号的字符形式 {@code '\''}。
     */
    public static final char SYMBOL_SINGLE_QUOTATION_MARK_CHAR = '\'';
    /**
    * 竖线符号的字符形式 {@code '|'}。
     */
    public static final char SYMBOL_PIPE_CHAR = '|';
    /**
    * 邮箱符号的字符形式 {@code '@'}。
     */
    public static final char SYMBOL_AT_CHAR = '@';
    /**
    * 井号的字符形式 {@code '#'}。
     */
    public static final char SYMBOL_HASH_CHAR = '#';
    /**
    * 美元符号的字符形式 {@code '$'}。
     */
    public static final char SYMBOL_DOLLAR_CHAR = '$';
    /**
    * 左圆括号的字符形式 {@code '('}。
     */
    public static final char SYMBOL_LEFT_BRACKETS_CHAR = '(';
    /**
    * 右圆括号的字符形式 {@code ')'}。
     */
    public static final char SYMBOL_RIGHT_BRACKETS_CHAR = ')';
    /**
    * 左大括号的字符形式 {@code '{'}。
     */
    public static final char SYMBOL_LEFT_BIG_PARANTHESES_CHAR = '{';
    /**
    * 右大括号的字符形式 {@code '}'}。
     */
    public static final char SYMBOL_RIGHT_BIG_PARANTHESES_CHAR = '}';
    /**
    * 星号的字符形式 {@code '*'}。
     */
    public static final char SYMBOL_ASTERISK_CHAR = '*';
    /**
    * 问号的字符形式 {@code '?'}。
     */
    public static final char SYMBOL_QUESTION_CHAR = '?';
    /**
    * 与符号的字符形式 {@code '&'}。
     */
    public static final char SYMBOL_AND_CHAR = '&';
    /**
    * 等号的字符形式 {@code '='}。
     */
    public static final char SYMBOL_EQUALS_CHAR = '=';
    /**
    * 减号的字符形式 {@code '-'}。
     */
    public static final char SYMBOL_MINUS_CHAR = '-';
    /**
    * 加号的字符形式 {@code '+'}。
     */
    public static final char SYMBOL_PLUS_CHAR = '+';
    /**
    * 下划线的字符形式 {@code '_'}。
     */
    public static final char SYMBOL_UNDERLINE_CHAR = '_';
    /**
    * 左斜杠的字符形式 {@code '/'}。
     */
    public static final char SYMBOL_LEFT_SLASH_CHAR = '/';
    /**
    * 右斜杠的字符形式 {@code '\\'}。
     */
    public static final char SYMBOL_RIGHT_SLASH_CHAR = '\\';
    /**
    * 空格的字符形式 {@code ' '}。
     */
    public static final char SYMBOL_BLANK_CHAR = ' ';
    /**
    * 换行符的字符形式 {@code '\n'}。
     */
    public static final char SYMBOL_NEWLINE_CHAR = '\n';
    /**
    * 制表符的字符形式 {@code '\t'}。
     */
    public static final char SYMBOL_TAB_CHAR = '\t';
    /**
    * 回车符的字符形式 {@code '\r'}。
     */
    public static final char SYMBOL_RETURN_CHAR = '\r';

    /**
    * 小于号字符常量。
     */
    public static final char LESS_THAN = '<';
    /**
    * 大于号字符常量。
     */
    public static final char GREATER_THAN = '>';

    /**
    * 波浪线字符常量。
     */
    public static final char SYMBOL_WAVY_LINE_CHAR = '~';
    // ========================== 通配符（char 类型） ==========================

    /**
    * 通配符星号（匹配任意字符序列）。
     */
    public static final char WILDCARD_ASTERISK = '*';
    /**
    * 通配符问号（匹配单个任意字符）。
     */
    public static final char WILDCARD_QUESTION = '?';

}
