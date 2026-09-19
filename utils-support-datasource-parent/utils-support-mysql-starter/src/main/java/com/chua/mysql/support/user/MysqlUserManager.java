package com.chua.mysql.support.user;

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
 * @author CH
 * @since 4.0.0.42
 */

public class MysqlUserManager implements UserManager, DataSourceAware {

    /**
     * 数据来源
    */
    private DataSource dataSource;

    @Override
    /**
     * 类型
    */
    public String type() {
        return "mysql";
    }

    @Override
    /**
     * 设置数据源
    */
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    /**
     * 列表用户
    */
    public List<UserInfo> listUsers() {
        List<UserInfo> list = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT user, host, authentication_string FROM mysql.user")) {
            while (rs.next()) {
                UserInfo ui = new UserInfo();
                ui.setUser(rs.getString("user"));
                ui.setHost(rs.getString("host"));
                try {
                    ui.setPassword(rs.getString("authentication_string"));
                } catch (Exception ignored) {
                }
                list.add(ui);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return list;
    }

    @Override
    /**
     * 创建用户
    */
    public CreateUserStep createUser(String username) {
        return new MysqlCreateUserStep(dataSource, username);
    }

    @Override
    /**
     * 掉落用户
    */
    public DropUserStep dropUser(String username) {
        return () -> {
            try (Connection c = dataSource.getConnection();
                 Statement s = c.createStatement()) {
                s.execute("DROP USER IF EXISTS '" + username + "'@'%'");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    @Override
    /**
     * alter用户
    */
    public AlterUserStep alterUser(String username) {
        return new MysqlAlterUserStep(dataSource, username);
    }
}
