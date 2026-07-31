package com.chua.mysql.support.user;

import com.chua.datasource.support.user.UserManager;

import javax.sql.DataSource;
/**
 * @author CH
 */

public class MysqlCreateUserStep implements UserManager.CreateUserStep {

    private final DataSource dataSource;
    private final String username;
    private String password;
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
