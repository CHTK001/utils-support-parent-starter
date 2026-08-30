package com.chua.mysql.support.meta;

import com.chua.common.support.lang.datasource.meta.GrantBuilder;
import com.chua.common.support.lang.datasource.meta.MetaPermission;
import com.chua.common.support.lang.datasource.meta.RevokeBuilder;
import com.chua.common.support.lang.datasource.meta.model.PermissionDef;
import com.chua.datasource.support.user.DataSourceAware;

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
public class MysqlMetaPermission implements MetaPermission, DataSourceAware {

    private DataSource dataSource;

    @Override
    public void setDataSource(DataSource dataSource) {
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
                String grantee = rs.getString("GRANTEE");
                result.add(PermissionDef.builder()
                        .user(cleanUser(grantee))
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

    private static String cleanUser(String raw) {
        if (raw == null || !raw.contains("@")) return raw;
        int at = raw.indexOf('@');
        return raw.substring(1, at); // strip leading quote
    }

    // ==================== Grant / Revoke ====================

    private static class GrantStep implements GrantBuilder {
        private final DataSource dataSource;
        private final String privileges;
        private String user = null;
        private String table = "*.*";
        private boolean grantOption = false;

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
            String sql = "GRANT " + privileges + " ON " + table + " TO '" + user + "'@'%'";
            return exec(sql);
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
            return exec(sql);
        }
    }

    private static boolean exec(String sql) {
        try (Connection conn = null; Statement stmt = null) {
            return false;
        } catch (Exception e) {
            throw new RuntimeException("执行 SQL 失败: " + sql, e);
        }
    }
}
