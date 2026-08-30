package com.chua.mysql.support.user;

import com.chua.datasource.support.permission.PermissionInfo;
import com.chua.datasource.support.permission.PermissionManager;
import com.chua.datasource.support.user.DataSourceAware;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL 权限管理器 SPI 实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MysqlPermissionManager implements PermissionManager, DataSourceAware {

    private DataSource dataSource;

    @Override
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String type() {
        return "mysql";
    }

    @Override
    public List<PermissionInfo> listPermissions() {
        List<PermissionInfo> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT GRANTEE, PRIVILEGE_TYPE, IS_GRANTABLE"
                             + " FROM information_schema.USER_PRIVILEGES"
                             + " WHERE TABLE_SCHEMA IS NULL")) {
            while (rs.next()) {
                result.add(new PermissionInfo(stripQuote(rs.getString("GRANTEE")), null,
                        rs.getString("PRIVILEGE_TYPE"), null, null, null, null,
                        "YES".equals(rs.getString("IS_GRANTABLE"))));
            }
        } catch (Exception e) {
            throw new RuntimeException("列出 MySQL 权限失败", e);
        }
        return result;
    }

    @Override
    public List<PermissionInfo> listPermissions(String username) {
        return listPermissions().stream()
                .filter(p -> username.equals(p.getUser()))
                .toList();
    }

    @Override
    public GrantStep grant(String privileges) {
        return new GrantAction(dataSource, privileges);
    }

    @Override
    public RevokeStep revoke(String privileges) {
        return new RevokeAction(dataSource, privileges);
    }

    private static String stripQuote(String raw) {
        if (raw == null || !raw.contains("@")) return raw;
        int at = raw.indexOf('@');
        String u = raw.substring(0, at);
        return u.startsWith("'") ? u.substring(1) : u;
    }

    private static void execSql(DataSource ds, String sql) {
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (Exception e) {
            throw new RuntimeException("执行 SQL 失败: " + sql, e);
        }
    }

    // ==================== Inner Actions ====================

    private static class GrantAction implements GrantStep {
        private final DataSource dataSource;
        private final String privileges;
        private String user = null;

        GrantAction(DataSource dataSource, String privileges) {
            this.dataSource = dataSource;
            this.privileges = privileges;
        }

        @Override
        public GrantStep toUser(String username) {
            this.user = username;
            return this;
        }

        @Override
        public GrantStep onDatabase(String database) { return this; }
        @Override
        public GrantStep onTable(String table) { return this; }
        @Override
        public GrantStep onColumn(String table, String column) { return this; }
        @Override
        public GrantStep withGrantOption(boolean grantable) { return this; }

        @Override
        public void execute() {
            if (user == null) throw new IllegalStateException("必须指定 toUser()");
            execSql(dataSource, "GRANT " + privileges + " ON *.* TO '" + user + "'@'%'");
        }
    }

    private static class RevokeAction implements RevokeStep {
        private final DataSource dataSource;
        private final String privileges;
        private String user = null;

        RevokeAction(DataSource dataSource, String privileges) {
            this.dataSource = dataSource;
            this.privileges = privileges;
        }

        @Override
        public RevokeStep fromUser(String username) {
            this.user = username;
            return this;
        }

        @Override
        public RevokeStep onDatabase(String database) { return this; }
        @Override
        public RevokeStep onTable(String table) { return this; }
        @Override
        public RevokeStep onColumn(String table, String column) { return this; }

        @Override
        public void execute() {
            if (user == null) throw new IllegalStateException("必须指定 fromUser()");
            execSql(dataSource, "REVOKE " + privileges + " ON *.* FROM '" + user + "'@'%'");
        }
    }
}
