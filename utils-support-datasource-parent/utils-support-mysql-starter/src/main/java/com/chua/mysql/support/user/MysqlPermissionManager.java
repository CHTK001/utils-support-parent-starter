package com.chua.mysql.support.user;

import com.chua.datasource.support.permission.PermissionInfo;
import com.chua.datasource.support.permission.PermissionManager;
import com.chua.datasource.support.user.DataSourceAware;
import com.chua.common.support.spi.annotations.Spi;

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
@Spi("mysql")
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
             Statement stmt = conn.createStatement()) {
            // Global privileges
            ResultSet rs = stmt.executeQuery(
                    "SELECT GRANTEE, PRIVILEGE_TYPE, IS_GRANTABLE"
                            + " FROM information_schema.USER_PRIVILEGES");
            while (rs.next()) {
                String grantee = rs.getString("GRANTEE");
                result.add(new PermissionInfo(stripQuote(grantee), null,
                        rs.getString("PRIVILEGE_TYPE"), null, null, null, null,
                        "YES".equals(rs.getString("IS_GRANTABLE"))));
            }
            rs.close();
            // Schema-level privileges
            ResultSet rs2 = stmt.executeQuery(
                    "SELECT GRANTEE, TABLE_SCHEMA, PRIVILEGE_TYPE, IS_GRANTABLE"
                            + " FROM information_schema.SCHEMA_PRIVILEGES");
            while (rs2.next()) {
                result.add(new PermissionInfo(stripQuote(rs2.getString("GRANTEE")),
                        rs2.getString("TABLE_SCHEMA"),
                        rs2.getString("PRIVILEGE_TYPE"), null, null, null, null,
                        "YES".equals(rs2.getString("IS_GRANTABLE"))));
            }
            rs2.close();
            // Table-level privileges
            ResultSet rs3 = stmt.executeQuery(
                    "SELECT GRANTEE, TABLE_SCHEMA, TABLE_NAME, PRIVILEGE_TYPE, IS_GRANTABLE"
                            + " FROM information_schema.TABLE_PRIVILEGES");
            while (rs3.next()) {
                result.add(new PermissionInfo(stripQuote(rs3.getString("GRANTEE")),
                        rs3.getString("TABLE_SCHEMA"),
                        rs3.getString("PRIVILEGE_TYPE"),
                        rs3.getString("TABLE_NAME"), null, null, null,
                        "YES".equals(rs3.getString("IS_GRANTABLE"))));
            }
            rs3.close();
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
        String clean = raw.trim();
        if (clean.startsWith("'") && clean.endsWith("'")) {
            clean = clean.substring(1, clean.length() - 1);
        }
        int at = clean.indexOf('@');
        return at > 0 ? clean.substring(0, at) : clean;
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
        private String database = null;

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
        public GrantStep onDatabase(String database) {
            this.database = database;
            return this;
        }

        @Override
        public GrantStep onTable(String table) { return this; }

        @Override
        public GrantStep onColumn(String table, String column) { return this; }

        @Override
        public GrantStep withGrantOption(boolean grantable) { return this; }

        @Override
        public void execute() {
            if (user == null) throw new IllegalStateException("必须指定 toUser()");
            String target = database != null ? "`" + database + "`.*" : "*.*";
            execSql(dataSource, "GRANT " + privileges + " ON " + target + " TO '" + user + "'@'%'");
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
