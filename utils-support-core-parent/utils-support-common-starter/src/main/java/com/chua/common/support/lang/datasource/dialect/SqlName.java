package com.chua.common.support.lang.datasource.dialect;

import java.util.regex.Pattern;

/**
 * SQL 标识符（库名/表名/列名/索引名）白名单校验与引用工具。
 * <p>
 * 元数据与 DDL 相关能力必须把用户提供的名字拼进语句，而这些位置无法使用占位符，
 * 因此统一走本工具：先用跨厂商保守白名单校验，再加对应方言的引用符。
 * 校验通过的标识符不含任何引用符与控制字符，拼接后不可能改写语句结构。
 * </p>
 * <p>
 * 白名单为 {@code [A-Za-z_][A-Za-z0-9_]*}，允许一段可选的 {@code schema.object} 限定，
 * 单个片段最长 64 字符（与各数据库 64/63 的上限取保守值）。
 * </p>
 * <p>
 * 另有两个不放宽注入面的补充契约：{@link #isWord(String)} 面向 measurement/键名等非 SQL
 * 列名位点，字符集同上但允许以数字开头；{@link #escape(String, char, String)} 面向必须容纳
 * 空白、连字符等真实厂商名的元数据读回位点，改用引用符加倍转义并拒绝控制字符。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class SqlName {

    /**
     * 允许的标识符形态：可选一段限定前缀，字符集只含字母、数字与下划线
     */
    private static final Pattern NAME =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}(\\.[A-Za-z_][A-Za-z0-9_]{0,63})?");

    /**
     * 允许的简单标识符形态：不含任何限定前缀
     */
    private static final Pattern SIMPLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    /**
     * 允许的裸词形态：字符集同标识符但允许数字开头，不含限定前缀
     */
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_]{1,128}");

    /**
     * 工具类，禁止实例化。
     */
    private SqlName() {
    }

    /**
     * 校验标识符是否安全。
     *
     * @param name  待校验标识符
     * @param label 参数用途，用于异常定位
     * @return 原样返回校验通过的标识符
     * @throws IllegalArgumentException 标识符为空、含非法字符或超长
     */
    public static String check(String name, String label) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(label + "含非法字符或超长: " + name);
        }
        return name;
    }

    /**
     * 判断标识符是否安全，供需要返回布尔的调用方使用。
     *
     * @param name 待校验标识符
     * @return 合法返回 true
     */
    public static boolean isSafe(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /**
     * 判断标识符是否为不带限定前缀的简单名。
     * <p>
     * 索引名、约束名在部分厂商里不能带 {@code schema.} 前缀，用本方法把这类位置收紧。
     * </p>
     *
     * @param name 待校验标识符
     * @return 是不带限定的合法简单名返回 true
     */
    public static boolean isSimple(String name) {
        return name != null && SIMPLE.matcher(name).matches();
    }

    /**
     * 判断标识符是否为合法的裸词形态。
     * <p>
     * 用于 measurement 名、键空间片段等非 SQL 列名位点：这些位置同样会被拼进语句或命令，
     * 必须收紧到无引号、无空白、无分隔符的字符集，但允许以数字开头（{@code 5xx} 之类的
     * 指标名在时序场景是合法名字）。
     * </p>
     *
     * @param name 待校验裸词
     * @return 合法返回 true
     */
    public static boolean isWord(String name) {
        return name != null && WORD.matcher(name).matches();
    }

    /**
     * 校验裸词形态。
     *
     * @param name  待校验裸词
     * @param label 参数用途，用于异常定位
     * @return 原样返回校验通过的裸词
     * @throws IllegalArgumentException 裸词为空、含非法字符或超长
     */
    public static String checkWord(String name, String label) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (!WORD.matcher(name).matches()) {
            throw new IllegalArgumentException(label + "含非法字符或超长: " + name);
        }
        return name;
    }

    /**
     * 校验后按 MySQL 方言加反引号。
     *
     * @param name  待引用标识符，可含一段 {@code schema.object} 限定
     * @param label 参数用途，用于异常定位
     * @return 形如 {@code `tbl`} 或 {@code `db`.`tbl`} 的片段
     * @throws IllegalArgumentException 标识符非法
     */
    public static String quoteMysql(String name, String label) {
        check(name, label);
        return quoteEach(name, '`');
    }

    /**
     * 校验后按标准 SQL（PostgreSQL/H2/Oracle/SQL Server 等）加双引号。
     *
     * @param name  待引用标识符，可含一段 {@code schema.object} 限定
     * @param label 参数用途，用于异常定位
     * @return 形如 {@code "tbl"} 或 {@code "schema"."tbl"} 的片段
     * @throws IllegalArgumentException 标识符非法
     */
    public static String quote(String name, String label) {
        check(name, label);
        return quoteEach(name, '"');
    }

    /**
     * 按厂商规则转义并引用标识符，供名字必须落在白名单之外的位点使用。
     * <p>
     * 元数据读回的表名/列名可能含空白、连字符等真实厂商允许但白名单拒绝的字符，此时不能
     * 走 {@link #quote(String, String)}（会直接拒掉合法名字），只能加倍引用符转义。
     * 本方法仍拒绝控制字符：换行、退格之类会把一个名字变成两条语句的输入。
     * </p>
     *
     * @param name  待引用标识符，整体视为一个名字，不按 {@code .} 拆段
     * @param quote 厂商引用符，如 {@code '`'} 或 {@code '"'}
     * @param label 参数用途，用于异常定位
     * @return 形如 {@code "od d"} 的片段，内部引用符已加倍
     * @throws IllegalArgumentException 标识符为空或含控制字符
     */
    public static String escape(String name, char quote, String label) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch < 0x20 || ch == 0x7f) {
                throw new IllegalArgumentException(label + "包含非法控制字符: " + name);
            }
        }
        String q = String.valueOf(quote);
        return q + name.replace(q, q + q) + q;
    }

    /**
     * 逐段加引用符；白名单已排除引用符本身，因此无需再转义。
     *
     * @param name  已通过校验的标识符
     * @param quote 引用符
     * @return 引用后的片段
     */
    private static String quoteEach(String name, char quote) {
        int dot = name.indexOf('.');
        if (dot < 0) {
            return quote + name + quote;
        }
        return quote + name.substring(0, dot) + quote
                + "." + quote + name.substring(dot + 1) + quote;
    }
}
