package com.chua.mysql.support.user;

import com.chua.datasource.support.user.UserManager;

import javax.sql.DataSource;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class MysqlCreateUserStep implements UserManager.CreateUserStep {

    /** 数据来源 */
    private final DataSource dataSource;
    /** Username */
    private final String username;
    /** 密码 */
    private String password;
    /** 主机 */
    private String host = "%";

    MysqlCreateUserStep(DataSource dataSource, String username) {
        this.dataSource = dataSource;
        this.username = username;
    }

    @Override
    public UserManager.CreateUserStep withPassword(String password) {
        this.password = password;
        return this;
    }

    @Override
    public UserManager.CreateUserStep withHost(String host) {
        this.host = host;
        return this;
    }

    @Override
    public void execute() {
        try (var c = dataSource.getConnection();
             var s = c.createStatement()) {
            s.execute("CREATE USER '" + username + "'@'" + host + "' IDENTIFIED BY '" + password + "'");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
