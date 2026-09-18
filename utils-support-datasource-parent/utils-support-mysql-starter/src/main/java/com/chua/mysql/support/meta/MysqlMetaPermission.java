package com.chua.mysql.support.meta;

import com.chua.common.support.lang.datasource.meta.GrantBuilder;
import com.chua.common.support.lang.datasource.meta.MetaPermission;
import com.chua.common.support.lang.datasource.meta.RevokeBuilder;
import com.chua.common.support.lang.datasource.meta.model.PermissionDef;
import com.chua.common.support.utils.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
* MySQL 权限元数据操作实现。
*
* @author CH
* @since 4.0.0.42
* @param ds ds
* @param sql SQL
* @param privileges privileges
* @return 方法的结果
* @param username 用户名
 */
public class MysqlMetaPermission implements MetaPermission {
/**
* mysqlmeta权限。
* @param dataSource 数据源
 */

    private final DataSource dataSource; // 数据源
    private String user; // 用户
    private String table; // table

    /**
    * 列表。
    * @return 列表的结果
    * @param dataSource 数据源
    */
    public MysqlMetaPermission(DataSource dataSource) {
        this.dataSource = dataSource;
    }

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
                /**
                * 转为用户。
                * @param username 用户名
                * @return 转为用户的结果
                */
                .toList();
    }

    @Override
    public MetaPermission toUser(String username) {
        this.user = username;
        /**
        * ontable。
        * @param tableName table名称
        * @return onTable的结果
        */
        return this;
    }

    @Override
    public MetaPermission onTable(String tableName) {
        this.table = tableName;
        /**
        * oncolumn。
        * @param tableName table名称
        * @param columnName column名称
        * @return onColumn的结果
        */
        return this;
    }

    @Override
    public MetaPermission onColumn(String tableName, String columnName) {
        /**
        * grant。
        * @param privileges privileges
        * @return grant的结果
        */
        return this;
    }

    @Override
    public GrantBuilder grant(String privileges) {
        return new GrantStep(dataSource, privileges, user);
    }

    @Override
    public RevokeBuilder revoke(String privileges) {
        return new RevokeStep(dataSource, privileges, user);
    /**
    * strip引述。
    * @param raw raw
    * @return strip引述的结果
    * @param ds ds
    * @param sql sql
    */
    }

    private static String stripQuote(String raw) {
        if (raw == null || !raw.contains("@")) {
            return raw;
        }
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

    // ==================== Inner Steps ====================
    /**
    * GrantStep类。
    *
    * @author CH
    * @since 4.0.0
    */

    private static class GrantStep implements GrantBuilder {
        private final DataSource dataSource; // 数据源
        private final String privileges; // privileges
        private String user = null; // 用户

        GrantStep(DataSource dataSource, String privileges, String user) {
            this.dataSource = dataSource;
            this.privileges = privileges;
            this.user = user;
        }

        @Override
        public GrantBuilder toUser(String username) {
            this.user = username;
            return this;
        }

        @Override
        public boolean execute() {
            if (user == null) {
                throw new IllegalStateException("必须指定 toUser()");
            }
            execSql(dataSource, "GRANT " + privileges + " ON *.* TO '" + StringUtils.replace(user, "'", "''") + "'@'%'");
            return true;
        }
    }

    /**
    * RevokeStep类。
    */
    private static class RevokeStep implements RevokeBuilder {
        private final DataSource dataSource; // 数据源
        private final String privileges; // privileges
        private String user = null; // 用户

        RevokeStep(DataSource dataSource, String privileges, String user) {
            this.dataSource = dataSource;
            this.privileges = privileges;
            this.user = user;
        }

        @Override
        public RevokeBuilder fromUser(String username) {
            this.user = username;
            return this;
        }

        @Override
        public boolean execute() {
            if (user == null) {
                throw new IllegalStateException("未指定 fromUser()");
            }
            execSql(dataSource, "REVOKE " + privileges + " ON *.* FROM '" + StringUtils.replace(user, "'", "''") + "'@'%'");
            return true;
        }
    }
}
