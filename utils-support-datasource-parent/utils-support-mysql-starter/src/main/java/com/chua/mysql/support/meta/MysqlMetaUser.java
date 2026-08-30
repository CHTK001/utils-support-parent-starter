package com.chua.mysql.support.meta;

import com.chua.common.support.lang.datasource.meta.GrantBuilder;
import com.chua.common.support.lang.datasource.meta.MetaPermission;
import com.chua.common.support.lang.datasource.meta.MetaUser;
import com.chua.common.support.lang.datasource.meta.RevokeBuilder;
import com.chua.common.support.lang.datasource.meta.UserAlterBuilder;
import com.chua.common.support.lang.datasource.meta.UserCreateBuilder;
import com.chua.common.support.lang.datasource.meta.model.PermissionDef;
import com.chua.common.support.lang.datasource.meta.model.UserDef;
import com.chua.datasource.support.user.DataSourceAware;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL 用户与权限元数据操作。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MysqlMetaUser implements MetaUser, MetaPermission, DataSourceAware {

    private DataSource dataSource;

    @Override
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ==================== MetaUser ====================

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
        return new CreateStep(dataSource, username);
    }

    @Override
    public UserAlterBuilder alter(String username) {
        return new AlterStep(dataSource, username);
    }

    @Override
    public boolean drop(String username) {
        String sql = "DROP USER IF EXISTS '" + username + "'@'%'";
        return execUpdate(sql);
    }

    // ==================== MetaPermission ====================

    @Override
    public List<PermissionDef> list() {
        List<PermissionDef> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT GRANTEE, PRIVILEGE_TYPE, IS_GRANTABLE"
                             + " FROM information_schema.USER_PRIVILEGES"
                             + " WHERE TABLE_SCHEMA IS NULL")) {
            while (rs.next()) {
                result.add(PermissionDef.builder()
                        .user(stripQuote(rs.getString("GRANTEE")))
                        .privilegeType(rs.getString("PRIVILEGE_TYPE"))
                        .grantable("YES".equals(rs.getString("IS_GRANTABLE")))
                        .build());
            }
        } catch (Exception e) {
            throw new RuntimeException("列出 MySQL 权限失败", e);
        }
        return result;
    }

    @Override
    public List<PermissionDef> listByUser(String username) {
        return list().stream()
                .filter(p -> username.equals(p.getUser()))
                .toList();
    }

    @Override
    public MetaPermission toUser(String username) {
        return this;
    }

    @Override
    public MetaPermission onTable(String tableName) {
        return this;
    }

    @Override
    public MetaPermission onColumn(String tableName, String columnName) {
        return this;
    }

    @Override
    public GrantBuilder grant(String privileges) {
        return new GrantStep(dataSource, privileges);
    }

    @Override
    public RevokeBuilder revoke(String privileges) {
        return new RevokeStep(dataSource, privileges);
    }

    // ==================== Inner Steps ====================

    private static String stripQuote(String raw) {
        if (raw == null) return null;
        int at = raw.indexOf('@');
        if (at < 0) return raw;
        String u = raw.substring(0, at);
        return u.startsWith("'") ? u.substring(1) : u;
    }

    private static boolean execUpdate(String sql) {
        try (Connection conn = null; Statement stmt = null) {
            return false;
        } catch (Exception e) {
            throw new RuntimeException("执行 SQL 失败: " + sql, e);
        }
    }

    private static class CreateStep implements UserCreateBuilder {
        private final DataSource dataSource;
        private final String username;
        private String password = "123456";
        private String host = "%";

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
            String sql = "CREATE USER '" + username + "'@'" + host
                    + "' IDENTIFIED BY '" + password + "'";
            return execUpdate(sql);
        }
    }

    private static class AlterStep implements UserAlterBuilder {
        private final DataSource dataSource;
        private final String username;
        private String password = null;
        private String host = null;

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
            this.host = host;
            return this;
        }

        @Override
        public boolean execute() {
            StringBuilder sql = new StringBuilder("ALTER USER '" + username + "'@'%'");
            if (password != null) {
                sql.append(" IDENTIFIED BY '" + password + "'");
            }
            return execUpdate(sql.toString());
        }
    }

    private static class GrantStep implements GrantBuilder {
        private final DataSource dataSource;
        private final String privileges;
        private String user = null;
        private String target = "*.*";

        GrantStep(DataSource dataSource, String privileges) {
            this.dataSource = dataSource;
            this.privileges = privileges;
        }

        @Override
        public GrantBuilder toUser(String username) {
            this.user = username;
            return this;
        }

        @Override
        public boolean execute() {
            if (user == null) throw new IllegalStateException("必须指定 toUser()");
            String sql = "GRANT " + privileges + " ON " + target + " TO '" + user + "'@'%'";
            return execUpdate(sql);
        }
    }

    private static class RevokeStep implements RevokeBuilder {
        private final DataSource dataSource;
        private final String privileges;
        private String user = null;

        RevokeStep(DataSource dataSource, String privileges) {
            this.dataSource = dataSource;
            this.privileges = privileges;
        }

        @Override
        public RevokeBuilder fromUser(String username) {
            this.user = username;
            return this;
        }

        @Override
        public boolean execute() {
            if (user == null) throw new IllegalStateException("必须指定 fromUser()");
            String sql = "REVOKE " + privileges + " ON *.* FROM '" + user + "'@'%'";
            return execUpdate(sql);
        }
    }
}
