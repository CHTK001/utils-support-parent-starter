package com.chua.mysql.support.meta;

import com.chua.common.support.lang.datasource.meta.GrantBuilder;
import com.chua.common.support.lang.datasource.meta.MetaPermission;
import com.chua.common.support.lang.datasource.meta.RevokeBuilder;
import com.chua.common.support.lang.datasource.meta.model.PermissionDef;
import com.chua.mysql.support.user.MysqlSqlNames;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * MySQL 权限元数据操作实现。
 * <p>
 * 授权与回收语句中的权限列表按白名单校验，账号名按白名单校验后再转义成字面量，避免拼接注入。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MysqlMetaPermission implements MetaPermission {

    /**
     * 单个权限白名单：权限名 + 可选列清单
     */
    private static final Pattern PRIVILEGE_PATTERN = Pattern.compile("[A-Za-z_]+(\\([A-Za-z_]+(\\s*,\\s*[A-Za-z_]+)*\\))?");
    /**
     * {@code ALL} 与 {@code ALL PRIVILEGES} 的整体写法
     */
    private static final Pattern ALL_PRIVILEGES_PATTERN = Pattern.compile("ALL(\\s+PRIVILEGES)?", Pattern.CASE_INSENSITIVE);

    /**
     * 数据源
     */
    private final DataSource dataSource;
    /**
     * 目标账号
     */
    private String user;
    /**
     * 目标表
     */
    private String table;

    /**
     * 构造方法，创建 MySQL 权限元数据操作实例。
     *
     * @param dataSource 数据源
     */
    public MysqlMetaPermission(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 列出全局权限。
     *
     * @return 权限定义列表
     */
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
        } catch (SQLException e) {
            throw new IllegalStateException("列出 MySQL 权限失败", e);
        }
        return result;
    }

    /**
     * 列出指定账号的权限。
     *
     * @param username 用户名
     * @return 该账号的权限定义列表
     */
    @Override
    public List<PermissionDef> listByUser(String username) {
        return list().stream()
                .filter(p -> username.equals(p.getUser()))
                .toList();
    }

    /**
     * 指定后续授权的账号。
     *
     * @param username 用户名，必须命中用户名白名单
     * @return this
     */
    @Override
    public MetaPermission toUser(String username) {
        this.user = MysqlSqlNames.checkUserName(username);
        return this;
    }

    /**
     * 指定授权的目标表。
     *
     * @param tableName 表名
     * @return this
     */
    @Override
    public MetaPermission onTable(String tableName) {
        this.table = tableName;
        return this;
    }

    /**
     * 指定授权的目标列。
     *
     * @param tableName  表名
     * @param columnName 列名
     * @return this
     */
    @Override
    public MetaPermission onColumn(String tableName, String columnName) {
        return this;
    }

    /**
     * 构造授权步骤。
     *
     * @param privileges 权限列表，必须命中权限白名单
     * @return 授权步骤
     */
    @Override
    public GrantBuilder grant(String privileges) {
        return new GrantStep(dataSource, privileges, user);
    }

    /**
     * 构造回收步骤。
     *
     * @param privileges 权限列表，必须命中权限白名单
     * @return 回收步骤
     */
    @Override
    public RevokeBuilder revoke(String privileges) {
        return new RevokeStep(dataSource, privileges, user);
    }

    /**
     * 从 {@code 'user'@'host'} 形式的 GRANTEE 中取出用户名。
     *
     * @param raw 原始 GRANTEE
     * @return 用户名
     */
    private static String stripQuote(String raw) {
        if (raw == null || !raw.contains("@")) {
            return raw;
        }
        int at = raw.indexOf('@');
        String u = raw.substring(0, at);
        return u.startsWith("'") ? u.substring(1) : u;
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
     * <p>
     * 反斜杠加倍在默认模式与 {@code NO_BACKSLASH_ESCAPES} 下都是安全的。
     * </p>
     *
     * @param value 原始值
     * @return 含首尾单引号的字面量
     */
    private static String quoteLiteral(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    // ==================== Inner Steps ====================

    /**
     * 授权步骤实现。
     *
     * @author CH
     * @since 4.0.0
     */
    private static class GrantStep implements GrantBuilder {

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
        private String user;

        GrantStep(DataSource dataSource, String privileges, String user) {
            this.dataSource = dataSource;
            this.privileges = checkPrivileges(privileges);
            this.user = user == null ? null : MysqlSqlNames.checkUserName(user);
        }

        /**
         * 指定被授权账号。
         *
         * @param username 用户名，必须命中用户名白名单
         * @return this
         */
        @Override
        public GrantBuilder toUser(String username) {
            this.user = MysqlSqlNames.checkUserName(username);
            return this;
        }

        /**
         * 执行授权。
         *
         * @return 执行成功返回 true
         */
        @Override
        public boolean execute() {
            if (user == null) {
                throw new IllegalStateException("必须指定 toUser()");
            }
            String account = quoteLiteral(MysqlSqlNames.checkUserName(user)) + "@'%'";
            String sql = "GRANT " + checkPrivileges(privileges) + " ON *.* TO " + account;
            execSql(dataSource, sql, sql);
            return true;
        }
    }

    /**
     * 回收步骤实现。
     *
     * @author CH
     * @since 4.0.0
     */
    private static class RevokeStep implements RevokeBuilder {

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
        private String user;

        RevokeStep(DataSource dataSource, String privileges, String user) {
            this.dataSource = dataSource;
            this.privileges = checkPrivileges(privileges);
            this.user = user == null ? null : MysqlSqlNames.checkUserName(user);
        }

        /**
         * 指定被回收账号。
         *
         * @param username 用户名，必须命中用户名白名单
         * @return this
         */
        @Override
        public RevokeBuilder fromUser(String username) {
            this.user = MysqlSqlNames.checkUserName(username);
            return this;
        }

        /**
         * 执行回收。
         *
         * @return 执行成功返回 true
         */
        @Override
        public boolean execute() {
            if (user == null) {
                throw new IllegalStateException("未指定 fromUser()");
            }
            String account = quoteLiteral(MysqlSqlNames.checkUserName(user)) + "@'%'";
            String sql = "REVOKE " + checkPrivileges(privileges) + " ON *.* FROM " + account;
            execSql(dataSource, sql, sql);
            return true;
        }
    }
}
