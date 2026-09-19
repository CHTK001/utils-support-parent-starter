package com.chua.mysql.support.user;

import com.chua.datasource.support.permission.PermissionInfo;
import com.chua.datasource.support.permission.PermissionManager;
import com.chua.datasource.support.user.DataSourceAware;
import com.chua.common.support.spi.annotations.Spi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * MySQL 权限管理器 SPI 实现。
 * <p>
 * 授权与回收语句中的权限列表、授权目标、账号名都先过白名单校验，账号名再以字面量形式转义下发。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("mysql")
public class MysqlPermissionManager implements PermissionManager, DataSourceAware {

    private static final Logger log = LoggerFactory.getLogger(MysqlPermissionManager.class);

    /**
     * 单个权限白名单：权限名 + 可选列清单
     */
    private static final Pattern PRIVILEGE_PATTERN = Pattern.compile("[A-Za-z_]+(\\([A-Za-z_]+(\\s*,\\s*[A-Za-z_]+)*\\))?");
    /**
     * {@code ALL} 与 {@code ALL PRIVILEGES} 的整体写法
     */
    private static final Pattern ALL_PRIVILEGES_PATTERN = Pattern.compile("ALL(\\s+PRIVILEGES)?", Pattern.CASE_INSENSITIVE);
    /**
     * 授权目标白名单：{@code *}、库名、{@code 库.*}、{@code 库.表}
     */
    private static final Pattern GRANT_TARGET_PATTERN = Pattern.compile("\\*|[A-Za-z0-9_$]+(\\.([A-Za-z0-9_$]+|\\*))?");

    /**
     * 数据源
     */
    private DataSource dataSource;

    /**
     * 设置 JDBC 数据源。
     *
     * @param dataSource 数据源
     */
    @Override
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 返回 SPI 扩展键：{@code mysql}
     *
     * @return "mysql"
     */
    @Override
    public String type() {
        return "mysql";
    }

    /**
     * 列出全局权限以及每个账号通过 {@code SHOW GRANTS} 可见的权限。
     *
     * @return 权限信息列表
     */
    @Override
    public List<PermissionInfo> listPermissions() {
        List<PermissionInfo> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            // 全局权限
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
            // 收集全部账号
            List<String[]> users = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT User, Host FROM mysql.user")) {
                while (rs.next()) {
                    users.add(new String[]{rs.getString("User"), rs.getString("Host")});
                }
            }
            // 逐个账号读取 SHOW GRANTS 结果
            for (String[] u : users) {
                String account = quoteLiteral(u[0]) + "@" + quoteLiteral(u[1]);
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SHOW GRANTS FOR " + account)) {
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
        } catch (SQLException e) {
            throw new IllegalStateException("列出 MySQL 权限失败", e);
        }
        return result;
    }

    /**
     * 从 {@code GRANT ... ON ... TO ...} 文本中取出权限列表。
     *
     * @param sql 授权语句文本
     * @return 权限列表，解析不出时返回 null
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
        int grantIdx = sql.indexOf("GRANT ");
        if (grantIdx < 0) {
            return null;
        }
        return sql.substring(grantIdx + 6, on).trim();
    }

    /**
     * 从 {@code GRANT ... ON ... TO ...} 文本中取出库名。
     *
     * @param sql 授权语句文本
     * @return 库名，全局权限或解析不出时返回 null
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
        // 去掉反引号
        target = target.replaceAll("`", "");
        if ("*".equals(target) || "*.*".equals(target)) {
            return null;
        }
        // 去掉 .* 后缀
        if (target.endsWith(".*")) {
            target = target.substring(0, target.length() - 2);
        }
        return target;
    }

    /**
     * 列出指定用户的权限。
     *
     * @param username 用户名
     * @return 该用户的权限列表
     */
    @Override
    public List<PermissionInfo> listPermissions(String username) {
        return listPermissions().stream()
                .filter(p -> username.equals(p.getUser()))
                .toList();
    }

    /**
     * 授予权限的链式步骤。
     *
     * @param privileges 权限列表，必须命中权限白名单
     * @return 授权步骤
     */
    @Override
    public GrantStep grant(String privileges) {
        return new GrantAction(dataSource, privileges);
    }

    /**
     * 回收权限的链式步骤。
     *
     * @param privileges 权限列表，必须命中权限白名单
     * @return 回收步骤
     */
    @Override
    public RevokeStep revoke(String privileges) {
        return new RevokeAction(dataSource, privileges);
    }

    /**
     * 从 {@code 'user'@'host'} 形式的 GRANTEE 中取出用户名。
     *
     * @param raw 原始 GRANTEE
     * @return 用户名
     */
    private static String stripQuote(String raw) {
        if (raw == null) {
            return raw;
        }
        String clean = raw.trim();
        // 去掉首尾引号：'user'@'host' -> user'@'host
        if (clean.startsWith("'") && clean.endsWith("'")) {
            clean = clean.substring(1, clean.length() - 1);
        }
        int at = clean.indexOf('@');
        if (at > 0) {
            String u = clean.substring(0, at);
            // 去掉残留的尾部引号
            while (u.endsWith("'")) {
                u = u.substring(0, u.length() - 1);
            }
            return u;
        }
        // 无 @ 分隔，可能是 SHOW GRANTS 返回的裸用户名
        while (clean.endsWith("'")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }

    /**
     * 执行一条权限语句。
     *
     * @param ds       数据源
     * @param sql      待执行语句
     * @param identity 语句身份，用于异常消息，必须不含口令
     */
    private static void execSql(DataSource ds, String sql, String identity) {
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(identity + " 执行失败", e);
        }
    }

    /**
     * 校验权限列表白名单，按顶层逗号切分后逐项匹配“权限名 + 可选列清单”。
     * <p>
     * {@code *} 不是合法权限名，因此 {@code "SELECT ON *.* TO x"} 之类的改写会被整体拒绝。
     * </p>
     *
     * @param privileges 权限列表
     * @return 规范化后的权限列表
     */
    private static String checkPrivileges(String privileges) {
        if (privileges == null || privileges.trim().isEmpty()) {
            throw new IllegalArgumentException("权限列表不能为空");
        }
        List<String> checked = new ArrayList<>();
        for (String item : splitTopLevel(privileges)) {
            String privilege = item.trim();
            if (privilege.isEmpty()
                    || (!PRIVILEGE_PATTERN.matcher(privilege).matches()
                    && !ALL_PRIVILEGES_PATTERN.matcher(privilege).matches())) {
                throw new IllegalArgumentException("非法权限名: " + privilege);
            }
            checked.add(privilege);
        }
        return String.join(", ", checked);
    }

    /**
     * 校验授权目标白名单。
     *
     * @param database 授权目标
     * @return 校验通过的授权目标
     */
    private static String checkGrantTarget(String database) {
        String target = database == null ? "" : database.trim();
        if (!GRANT_TARGET_PATTERN.matcher(target).matches()) {
            throw new IllegalArgumentException("非法授权目标: " + database);
        }
        return target;
    }

    /**
     * 按顶层逗号切分权限列表，括号内的逗号不参与切分。
     *
     * @param privileges 权限列表
     * @return 切分后的权限项
     */
    private static List<String> splitTopLevel(String privileges) {
        List<String> items = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < privileges.length(); i++) {
            char ch = privileges.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth = Math.max(0, depth - 1);
            }
            if (ch == ',' && depth == 0) {
                items.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        items.add(current.toString());
        return items;
    }

    /**
     * 生成单引号字面量：反斜杠加倍，单引号成对。
     *
     * @param value 原始值
     * @return 含首尾单引号的字面量
     */
    private static String quoteLiteral(String value) {
        return "'" + (value == null ? "" : value.replace("\\", "\\\\").replace("'", "''")) + "'";
    }

    // ==================== Inner Actions ====================

    /**
     * 授权动作实现。
     *
     * @author CH
     * @since 4.0.0
     */
    private static class GrantAction implements GrantStep {

        /**
         * 数据源
         */
        private final DataSource dataSource;
        /**
         * 权限列表
         */
        private final String privileges;
        /**
         * 目标账号
         */
        private String user = null;
        /**
         * 授权目标
         */
        private String database = null;

        GrantAction(DataSource dataSource, String privileges) {
            this.dataSource = dataSource;
            this.privileges = checkPrivileges(privileges);
        }

        /**
         * 指定被授权账号。
         *
         * @param username 用户名，必须命中用户名白名单
         * @return this
         */
        @Override
        public GrantStep toUser(String username) {
            this.user = MysqlSqlNames.checkUserName(username);
            return this;
        }

        /**
         * 指定授权目标，仅库名时自动展开为整库授权。
         *
         * @param database 授权目标，支持 {@code *}、库名、{@code 库.*}、{@code 库.表}
         * @return this
         */
        @Override
        public GrantStep onDatabase(String database) {
            this.database = checkGrantTarget(database);
            return this;
        }

        /**
         * 表级授权尚未实现，保持原有忽略语义，仅告警提示实际范围。
         *
         * @param table 表名
         * @return this
         */
        @Override
        public GrantStep onTable(String table) {
            log.warn("暂不支持表级授权，入参 table={} 被忽略，实际按库级或全局范围授权", table);
            return this;
        }

        /**
         * 列级授权尚未实现，保持原有忽略语义，仅告警提示实际范围。
         *
         * @param table  表名
         * @param column 列名
         * @return this
         */
        @Override
        public GrantStep onColumn(String table, String column) {
            log.warn("暂不支持列级授权，入参 table={}, column={} 被忽略，实际按库级或全局范围授权", table, column);
            return this;
        }

        /**
         * GRANT OPTION 尚未实现，保持原有忽略语义。
         *
         * @param grantable 是否可再授权
         * @return this
         */
        @Override
        public GrantStep withGrantOption(boolean grantable) {
            log.warn("暂不支持 GRANT OPTION，入参 grantable={} 被忽略", grantable);
            return this;
        }

        /**
         * 执行授权。
         */
        @Override
        public void execute() {
            if (user == null) {
                throw new IllegalStateException("必须指定 toUser()");
            }
            String target = "*.*";
            if (database != null) {
                target = "*".equals(database) || !database.contains(".") ? database + ".*" : database;
            }
            String sql = "GRANT " + checkPrivileges(privileges) + " ON " + target
                    + " TO " + quoteLiteral(MysqlSqlNames.checkUserName(user)) + "@'%'";
            execSql(dataSource, sql, sql);
        }
    }

    /**
     * 回收动作实现。
     *
     * @author CH
     * @since 4.0.0
     */
    private static class RevokeAction implements RevokeStep {

        /**
         * 数据源
         */
        private final DataSource dataSource;
        /**
         * 权限列表
         */
        private final String privileges;
        /**
         * 目标账号
         */
        private String user = null;

        RevokeAction(DataSource dataSource, String privileges) {
            this.dataSource = dataSource;
            this.privileges = checkPrivileges(privileges);
        }

        /**
         * 指定被回收账号。
         *
         * @param username 用户名，必须命中用户名白名单
         * @return this
         */
        @Override
        public RevokeStep fromUser(String username) {
            this.user = MysqlSqlNames.checkUserName(username);
            return this;
        }

        /**
         * 按库回收尚未实现，保持原有忽略语义，仅告警提示实际范围。
         *
         * @param database 库名
         * @return this
         */
        @Override
        public RevokeStep onDatabase(String database) {
            log.warn("暂不支持按库回收权限，入参 database={} 被忽略，实际按全局范围回收", database);
            return this;
        }

        /**
         * 按表回收尚未实现，保持原有忽略语义，仅告警提示实际范围。
         *
         * @param table 表名
         * @return this
         */
        @Override
        public RevokeStep onTable(String table) {
            log.warn("暂不支持按表回收权限，入参 table={} 被忽略，实际按全局范围回收", table);
            return this;
        }

        /**
         * 按列回收尚未实现，保持原有忽略语义，仅告警提示实际范围。
         *
         * @param table  表名
         * @param column 列名
         * @return this
         */
        @Override
        public RevokeStep onColumn(String table, String column) {
            log.warn("暂不支持按列回收权限，入参 table={}, column={} 被忽略，实际按全局范围回收", table, column);
            return this;
        }

        /**
         * 执行回收。
         */
        @Override
        public void execute() {
            if (user == null) {
                throw new IllegalStateException("必须指定 fromUser()");
            }
            String sql = "REVOKE " + checkPrivileges(privileges) + " ON *.* FROM "
                    + quoteLiteral(MysqlSqlNames.checkUserName(user)) + "@'%'";
            execSql(dataSource, sql, sql);
        }
    }
}
