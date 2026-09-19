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

    private DataSource dataSource; // 数据源

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
        try (Connection conn = dataSource.getConnection()) {
 // 全局 privileges
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT GRANTEE, PRIVILEGE_TYPE, IS_GRANTABLE"
                                 + " FROM information_schema.USER_PRIVILEGES")) {
                while (rs.next()) {
                    result.add(new PermissionInfo(stripQuote(rs.getString("GRANTEE")), null,
                            rs.getString("PRIVILEGE_TYPE"), null, null, null, null,
                            "YES".equals(rs.getString("IS_GRANTABLE"))));
                }
            }
 // Collect 用户 第一个
            List<String[]> users = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT User, Host FROM mysql.user")) {
                while (rs.next()) {
                    users.add(new String[]{rs.getString("User"), rs.getString("Host")});
                }
            }
 // 查询 SHOW GRANTS for each 用户
            for (String[] u : users) {
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SHOW GRANTS FOR '"
                             + u[0] + "'@'" + u[1] + "'")) {
                    while (rs.next()) {
                        String grantSql = rs.getString(1);
                        String perms = parseGrantPrivileges(grantSql);
                        String db = parseGrantDatabase(grantSql);
                        if (perms != null && db != null) {
                            result.add(new PermissionInfo(u[0], db, perms, null, null, null, null, false));
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("列出 MySQL 权限失败", e);
        }
        return result;
    }

    /**
     * 解析grantprivileges。
     * @param sql SQL
     * @return 解析grantprivileges的结果
     */
    private static String parseGrantPrivileges(String sql) {
        if (sql == null) {
            return null;
        }
        int on = sql.indexOf(" ON ");
        int to = sql.indexOf(" TO ");
        if (on < 0 || to < 0 || to <= on) {
            return null;
        }
        // Privileges are between first space after "GRANT" and " ON"
        int grantIdx = sql.indexOf("GRANT ");
        if (grantIdx < 0) {
            return null;
        }
        return sql.substring(grantIdx + 6, on).trim();
    }

    /**
     * 解析grantdatabase。
     * @param sql SQL
     * @return 解析grantdatabase的结果
     */
    private static String parseGrantDatabase(String sql) {
        if (sql == null) {
            return null;
        }
        int on = sql.indexOf(" ON ");
        int to = sql.indexOf(" TO ");
        if (on < 0 || to < 0 || to <= on) {
            return null;
        }
        String target = sql.substring(on + 4, to).trim();
 // 移除 backticks
        target = target.replaceAll("`", "");
        if ("*".equals(target) || "*.*".equals(target)) {
            return null;
        }
 // 移除 .* 后缀
        if (target.endsWith(".*")) {
            target = target.substring(0, target.length() - 2);
        }
        return target;
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

    /**
     * strip引述。
     * @param raw raw
     * @return strip引述的结果
     */
    private static String stripQuote(String raw) {
        if (raw == null) {
            return raw;
        }
        String clean = raw.trim();
        // Remove surrounding quotes: 'user'@'host' -> user'@'host
        if (clean.startsWith("'") && clean.endsWith("'")) {
            clean = clean.substring(1, clean.length() - 1);
        }
        int at = clean.indexOf('@');
        if (at > 0) {
            String u = clean.substring(0, at);
 // Strip 任意 trailing 引述 left behind
            while (u.endsWith("'")) {
                u = u.substring(0, u.length() - 1);
            }
            return u;
        }
 // No @ 标志 — might be bare 用户名 从 SHOW GRANTS
        while (clean.endsWith("'")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }

    /**
     * 执行sql。
     * @param ds ds
     * @param sql SQL
     */
    private static void execSql(DataSource ds, String sql) {
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (Exception e) {
            throw new RuntimeException("执行 SQL 失败: " + sql, e);
        }
    }

    // ==================== Inner Actions ====================
    /**
     * grant动作类。
     *
     * @author CH
     * @since 4.0.0
     */

    private static class GrantAction implements GrantStep {
        private final DataSource dataSource; // 数据源
        private final String privileges; // privileges
        private String user = null; // 用户
        private String database = null; // database

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
            if (user == null) {
                throw new IllegalStateException("必须指定 toUser()");
            }
            String target = database != null ? "`" + database + "`.*" : "*.*";
            execSql(dataSource, "GRANT " + privileges + " ON " + target + " TO '" + user + "'@'%'");
        }
    }

    /**
     * revoke动作类。
     */
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
            if (user == null) {
                throw new IllegalStateException("必须指定 fromUser()");
            }
            execSql(dataSource, "REVOKE " + privileges + " ON *.* FROM '" + user + "'@'%'");
        }
    }
}
