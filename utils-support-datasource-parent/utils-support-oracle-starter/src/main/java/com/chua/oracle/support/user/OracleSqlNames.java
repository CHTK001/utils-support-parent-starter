package com.chua.oracle.support.user;

import java.util.regex.Pattern;

/**
 * Oracle 账号名白名单校验。
 * <p>
 * 用户管理类与步骤类都需要把账号名拼进 {@code CREATE/ALTER/DROP USER}、
 * {@code GRANT/REVOKE} 语句，而这些位置不能用占位符。Oracle 账号即 schema，
 * 名字遵循普通标识符规则但额外允许 {@code $ #}，因此不能复用跨厂商的 {@code SqlName} 白名单，
 * 也不能各写一份：这里集中一份，供本模块所有拼接位点使用。
 * </p>
 * <p>
 * 白名单不含双引号、单引号与分号，校验通过的名字被引用后无法提前闭合语句。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class OracleSqlNames {

    /**
     * 用户名白名单：字母开头，其后允许字母、数字与 {@code $ _ #}，最长 64
     */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_$#]{0,63}");

    /**
     * 工具类，禁止实例化。
     */
    private OracleSqlNames() {
    }

    /**
     * 校验 Oracle 账号名。
     *
     * @param username 账号名
     * @return 校验通过的账号名
     * @throws IllegalArgumentException 账号名为空或含非法字符
     */
    public static String checkUserName(String username) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new IllegalArgumentException("非法用户名: " + username);
        }
        return username;
    }
}
