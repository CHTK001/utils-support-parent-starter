package com.chua.datasource.support.engine;

import com.chua.datasource.support.user.DataSourceAware;
import com.chua.datasource.support.user.UserInfo;
import com.chua.datasource.support.user.UserManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试用 H2 用户管理器 SPI 实现（验证 engine.user() 解析与 DataSource 注入）。
 */
public class TestH2UserManager implements UserManager, DataSourceAware {

    private DataSource dataSource;

    @Override
    public String type() {
        return "h2";
    }

    @Override
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<UserInfo> listUsers() {
        List<UserInfo> users = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT USER_NAME FROM INFORMATION_SCHEMA.USERS")) {
            while (rs.next()) {
                users.add(new UserInfo(rs.getString(1), "localhost", null));
            }
        } catch (Exception e) {
            throw new IllegalStateException("listUsers failed: " + e.getMessage(), e);
        }
        return users;
    }

    @Override
    public CreateUserStep createUser(String username) {
        return new CreateUserStep() {
            private String password;

            @Override
            public CreateUserStep withPassword(String password) {
                this.password = password;
                return this;
            }

            @Override
            public CreateUserStep withHost(String host) {
                return this;
            }

            @Override
            public void execute() {
                exec("CREATE USER IF NOT EXISTS " + username + " PASSWORD '" + password + "'");
            }
        };
    }

    @Override
    public DropUserStep dropUser(String username) {
        return () -> exec("DROP USER IF EXISTS " + username);
    }

    @Override
    public AlterUserStep alterUser(String username) {
        return new AlterUserStep() {
            private String newPassword;

            @Override
            public AlterUserStep withPassword(String password) {
                this.newPassword = password;
                return this;
            }

            @Override
            public AlterUserStep withHost(String host) {
                return this;
            }

            @Override
            public AlterUserStep withGrant(String privilege, String database) {
                return this;
            }

            @Override
            public AlterUserStep withRevoke(String privilege, String database) {
                return this;
            }

            @Override
            public void execute() {
                if (newPassword != null) {
                    exec("ALTER USER " + username + " SET PASSWORD '" + newPassword + "'");
                }
            }
        };
    }

    private void exec(String sql) {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (Exception e) {
            throw new IllegalStateException("exec failed: " + e.getMessage(), e);
        }
    }
}
