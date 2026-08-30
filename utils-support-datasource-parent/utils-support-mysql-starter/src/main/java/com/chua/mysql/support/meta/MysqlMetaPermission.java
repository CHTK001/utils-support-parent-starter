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
 */
public class MysqlMetaPermission implements MetaPermission {

    private final DataSource dataSource;

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

    // ==================== Inner Steps ====================

    private static class GrantStep implements GrantBuilder {
        private final DataSource dataSource;
        private final String privileges;
        private String user = null;

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
            execSql(dataSource, "GRANT " + privileges + " ON *.* TO '" + StringUtils.replace(user, "'", "''") + "'@'%'");
            return true;
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
            execSql(dataSource, "REVOKE " + privileges + " ON *.* FROM '" + StringUtils.replace(user, "'", "''") + "'@'%'");
            return true;
        }
    }
}
