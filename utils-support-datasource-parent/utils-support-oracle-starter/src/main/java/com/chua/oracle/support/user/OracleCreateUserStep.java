package com.chua.oracle.support.user;

import com.chua.datasource.support.user.UserManager;

import javax.sql.DataSource;

/**
 * Oracle 创建用户链式步骤实现。
 * <p>
 * Oracle 语法：{@code CREATE USER 用户名 IDENTIFIED BY "密码"}
 * 创建后自动授予 CONNECT 角色。
 * </p>
 *
 * @author CH
 * @since 2024/12/12
 */
public class OracleCreateUserStep implements UserManager.CreateUserStep {

    private final DataSource dataSource;
    private final String username;
    private String password;
    private String host = "";

    OracleCreateUserStep(DataSource dataSource, String username) {
        this.dataSource = dataSource;
        this.username = username;
    }

    /**
     * 设置用户密码。
     *
     * @param password 明文密码
     * @return this
     */
    @Override
    public UserManager.CreateUserStep withPassword(String password) {
        this.password = password;
        return this;
    }

    /**
     * Oracle 不支持 host 概念，忽略此参数。
     *
     * @param host 忽略
     * @return this
     */
    @Override
    public UserManager.CreateUserStep withHost(String host) {
        return this;
    }

    /**
     * 执行 CREATE USER 并授予 CONNECT 权限。
     */
    @Override
    public void execute() {
        try (var c = dataSource.getConnection();
             var s = c.createStatement()) {
            s.execute("CREATE USER " + username + " IDENTIFIED BY \"" + password + "\"");
            s.execute("GRANT CONNECT TO " + username);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
