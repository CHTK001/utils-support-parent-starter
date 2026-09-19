package com.chua.mysql.support.user;

import java.util.regex.Pattern;

/**
 * MySQL 账号名白名单校验。
 * <p>
 * 用户管理类与元数据类都需要把账号名拼进 {@code CREATE/ALTER/DROP USER}、
 * {@code GRANT/REVOKE} 语句，而这些位置不能用占位符。账号名的合法字符集比 SQL 标识符更宽
 * （允许 {@code . $ _} 与空格），因此不能复用跨厂商的 {@code SqlName} 白名单，
 * 但也不能各写一份：这里集中一份，供本模块所有拼接位点使用。
 * </p>
 * <p>
 * 白名单不含单引号与反斜杠，校验通过的名字被单引号包裹后无法提前闭合字面量。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class MysqlSqlNames {

    /**
     * 用户名白名单：字母、数字与 {@code . $ _} 空格，最长 64
     */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9.$_ -]{1,64}");

    /**
     * 工具类，禁止实例化。
     */
    private MysqlSqlNames() {
    }

    /**
     * 校验 MySQL 账号名。
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
