package com.chua.mysql.support.meta;

import com.chua.common.support.lang.datasource.meta.MetaUser;
import com.chua.common.support.lang.datasource.meta.UserAlterBuilder;
import com.chua.common.support.lang.datasource.meta.UserCreateBuilder;
import com.chua.common.support.lang.datasource.meta.model.UserDef;
import com.chua.common.support.utils.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
* MySQL 用户元数据操作实现。
*
* @author CH
* @since 4.0.0.42
* @param ds ds
* @param sql SQL
* @param username 用户名
* @return 方法的结果
* @param dataSource 数据源
 */
public class MysqlMetaUser implements MetaUser {

    private final DataSource dataSource; // 数据源

    /**
    * mysqlmeta用户。
    * @param dataSource 数据源
     */
    public MysqlMetaUser(DataSource dataSource) {
        /**
        * 列表。
        * @return 列表的结果
         */
        this.dataSource = dataSource;
    }

    @Override
    public List<UserDef> list() {
        List<UserDef> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT user, host, account_locked, password_last_changed"
                             + " FROM mysql.user")) {
            while (rs.next()) {
                result.add(UserDef.builder()
                        .user(rs.getString("user"))
                        .host(rs.getString("host"))
                        .locked("Y".equals(rs.getString("account_locked")))
                        .passwordLastChanged(rs.getString("password_last_changed"))
                        .build());
            }
        } catch (Exception e) {
            throw new RuntimeException("列出 MySQL 用户失败", e);
        }
        return result;
    }

    @Override
    public UserCreateBuilder create(String username) {
        /**
        * alter。
        * @param username 用户名
        * @return alter的结果
        * @param ds ds
        * @param sql sql
         */
        return new CreateStep(dataSource, username);
    }

    @Override
    public UserAlterBuilder alter(String username) {
        return new AlterStep(dataSource, username);
    }

    @Override
    public boolean drop(String username) {
        exec(dataSource, "DROP USER IF EXISTS '" + StringUtils.replace(username, "'", "''") + "'@'%'");
        return true;
    }

    private static void exec(DataSource ds, String sql) {
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (Exception e) {
            throw new RuntimeException("执行 SQL 失败: " + sql, e);
        }
    }

    // ==================== Inner Steps ====================
    /**
     * 创建step类。
     *
     * @author CH
     * @since 4.0.0
     */

    private static class CreateStep implements UserCreateBuilder {
        private final DataSource dataSource; // 数据源
        private final String username; // 用户名
        private String password = "123456"; // 密码
        private String host = "%"; // 主机

        CreateStep(DataSource dataSource, String username) {
            this.dataSource = dataSource;
            this.username = username;
        }

        @Override
        public UserCreateBuilder withPassword(String password) {
            this.password = password;
            return this;
        }

        @Override
        public UserCreateBuilder withHost(String host) {
            this.host = host;
            return this;
        }

        @Override
        public boolean execute() {
            exec(dataSource, "CREATE USER '" + StringUtils.replace(username, "'", "''") + "'@'"
                    + StringUtils.replace(host, "'", "''")
                    + "' IDENTIFIED BY '" + StringUtils.replace(password, "'", "''") + "'");
            return true;
        }
    }

    /**
     * AlterStep类。
     */
    private static class AlterStep implements UserAlterBuilder {
        private final DataSource dataSource; // 数据源
        private final String username; // 用户名
        private String password = null; // 密码

        AlterStep(DataSource dataSource, String username) {
            this.dataSource = dataSource;
            this.username = username;
        }

        @Override
        public UserAlterBuilder withPassword(String password) {
            this.password = password;
            return this;
        }

        @Override
        public UserAlterBuilder withHost(String host) {
            return this;
        }

        @Override
        public boolean execute() {
            StringBuilder sql = new StringBuilder("ALTER USER '" + StringUtils.replace(username, "'", "''") + "'@'%'");
            if (password != null) {
                sql.append(" IDENTIFIED BY '" + StringUtils.replace(password, "'", "''") + "'");
            }
            exec(dataSource, sql.toString());
            return true;
        }
    }
}
