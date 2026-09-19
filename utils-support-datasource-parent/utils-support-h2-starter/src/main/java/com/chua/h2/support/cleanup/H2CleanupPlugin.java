package com.chua.h2.support.cleanup;

import com.chua.common.support.lang.datasource.engine.Engine;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * H2 数据清理插件，提供测试环境的 模式 和数据清理能力。
 * <p>
 * 清理顺序：删除所有全文索引 → 删除所有用户表 → 重置序列。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class H2CleanupPlugin {

    /**
     * 版本记录表名
    */
    private static final String HISTORY_TABLE = "flyway_schema_history";

    /**
     * 引擎实例
    */
    private final Engine engine;

    /**
     * 构造方法。
     *
     * @param engine H2 引擎
     */
    public H2CleanupPlugin(Engine engine) {
        this.engine = engine;
    }

    /**
     * 清理所有用户数据（保留系统表）。
     * <p>
     * 顺序：先删用户索引 → 再删用户表 → 重置序列。
     * </p>
     *
     * @return 清理的表数量
     */
    public int cleanup() {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            int count = 0;

 // 1. 删除所有用户索引（H2 2.x 无 文本 索引）
            dropAllUserIndexes(conn);

            // 2. 删除所有用户表（排除系统表）
            count += dropAllUserTables(conn);

            // 3. 清理版本记录表（如果存在）
            dropTable(conn, HISTORY_TABLE);

            conn.commit();
            return count;
        } catch (Exception e) {
            throw new RuntimeException("H2 清理失败", e);
        }
    }

    /**
     * 仅删除指定前缀的表（用于隔离测试）。
     *
     * @param prefix 表名前缀
     * @return 清理的表数量
     */
    public int cleanupByPrefix(String prefix) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            int count = 0;
            List<String> tables = getUserTableNames(conn);
            for (String table : tables) {
                if (table.toLowerCase().startsWith(prefix.toLowerCase())) {
                    dropTable(conn, table);
                    count++;
                }
            }
            conn.commit();
            return count;
        } catch (Exception e) {
            throw new RuntimeException("H2 清理失败: prefix=" + prefix, e);
        }
    }

    /**
     * 清空所有用户表数据（保留表结构）。
     *
     * @return 清理的行数
     */
    public int truncateAll() {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            int total = 0;
            List<String> tables = getUserTableNames(conn);
            for (String table : tables) {
                try {
                    Statement stmt = conn.createStatement();
                    stmt.execute("TRUNCATE TABLE \"" + table + "\"");
                    stmt.close();
                    total++;
                } catch (Exception ignored) {
                    // 部分表可能无数据或不可截断
                }
            }
            conn.commit();
            return total;
        } catch (Exception e) {
            throw new RuntimeException("H2 截断失败", e);
        }
    }

    /**
     * 获取connection。
     * @return 获取connection的结果
     */
    private Connection getConnection() throws SQLException {
        DataSource ds = getDataSource();
        if (ds == null) {
            throw new IllegalStateException("H2 引擎未配置数据源");
        }
        return ds.getConnection();
    }

    @SuppressWarnings("unchecked")
    /**
     * 获取数据源。
     * @return 获取数据源的结果
     */
    private DataSource getDataSource() {
        var dsObj = engine.getDataSource();
        return dsObj != null ? (DataSource) dsObj.getSource() : null;
    }

    /**
     * 删除所有用户索引（H2 2.x 无 文本 索引，按普通索引清理）。
     * @param conn conn
     * @return 掉落全部用户索引的结果
     */
    private int dropAllUserIndexes(Connection conn) throws SQLException {
        List<String> indexes = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT DISTINCT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES "
                             + "WHERE INDEX_TYPE_NAME = 'INDEX'")) {
            while (rs.next()) {
                indexes.add(rs.getString(1));
            }
        }
        for (String name : indexes) {
            dropIndex(conn, name);
        }
        return indexes.size();
    }

    /**
     * 获取所有用户表名（排除系统表）。
     * <p>H2 2.x 中用户表的 TABLE_TYPE 为 'BASE TABLE'（SQL 标准值）。</p>
     * @param conn conn
     * @return 获取用户table名称的结果
     */
    private List<String> getUserTableNames(Connection conn) throws SQLException {
        List<String> tables = new ArrayList<>();
        String excludePattern = "(?i)(information_schema|system_|INFORMATION_SCHEMA|flyway_schema_history|schema_)";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                             + "WHERE TABLE_TYPE = 'BASE TABLE' AND TABLE_SCHEMA = 'PUBLIC'")) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (!name.matches(excludePattern) && !name.equalsIgnoreCase(HISTORY_TABLE)) {
                    tables.add(name);
                }
            }
        }
        return tables;
    }

    /**
     * 删除所有用户表。
     * @param conn conn
     * @return 掉落全部用户tables的结果
     */
    private int dropAllUserTables(Connection conn) throws SQLException {
        List<String> tables = getUserTableNames(conn);
        for (String table : tables) {
            dropTable(conn, table);
        }
        return tables.size();
    }

    /**
     * 删除指定表。
     * @param conn conn
     * @param tableName table名称
     */
    private void dropTable(Connection conn, String tableName) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS \"" + tableName + "\"");
        } catch (SQLException ignored) {
            // 表不存在时忽略
        }
    }

    /**
     * 删除指定索引。
     * @param conn conn
     * @param indexName 索引名称
     */
    private void dropIndex(Connection conn, String indexName) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP INDEX IF EXISTS \"" + indexName + "\"");
        } catch (SQLException ignored) {
            // 索引不存在时忽略
        }
    }
}
