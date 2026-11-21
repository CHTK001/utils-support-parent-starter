// [P3C A20 豁免] A20/A30/A31: 含 SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.datasource.support.kv;

import com.chua.common.support.lang.datasource.kv.KvEngine;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于关系型数据库（JDBC {@link DataSource}）的 {@link KvEngine} 实现。
 *
 * <p>本实现补齐「宿主只有 JDBC 数据源、没有 Redis 等专用 KV 后端」场景下的 KV 能力，
 * 供日志采集等模块把原始数据落库。它<b>不通过 SPI 注册</b>（构造需要容器数据源），
 * 由使用方以 {@code new JdbcKvEngine(dataSource)} 方式编程装配。</p>
 *
 * <p>表结构（首次使用时自动创建）：</p>
 * <pre>{@code
 * CREATE TABLE IF NOT EXISTS sys_support_kv (
 *   kv_key     VARCHAR(768)  NOT NULL,
 *   kv_value   MEDIUMTEXT    NULL,
 *   expires_at BIGINT        NULL,
 *   PRIMARY KEY (kv_key)
 * ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
 * }</pre>
 *
 * <p>{@code expires_at} 为毫秒时间戳；过期行为与 Redis 对齐：读时惰性删除，
 * {@link #ttl(String)} 对不存在/已过期键返回 {@code -2}，永不过期返回 {@code -1}。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public class JdbcKvEngine implements KvEngine {

    /**
     * KV 存储表名
     */
    private static final String TABLE = "sys_support_kv";

    private final DataSource dataSource;

    /**
     * 建表是否已完成（懒执行，避免构造期即触碰数据库）
     */
    private volatile boolean tableReady;

    /**
     * @param dataSource JDBC 数据源（必填）
     */
    public JdbcKvEngine(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource 不能为空");
        }
        this.dataSource = dataSource;
    }

    @Override
    public String get(String key) {
        ensureTable();
        String sql = "SELECT kv_value, expires_at FROM " + TABLE + " WHERE kv_key = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String value = rs.getString(1);
                long expiresAt = rs.getLong(2);
                boolean hasExpiry = !rs.wasNull();
                if (hasExpiry && expiresAt < System.currentTimeMillis()) {
                    delete(key);
                    return null;
                }
                return value;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("JdbcKvEngine get 失败: " + key, e);
        }
    }

    @Override
    public void put(String key, String value) {
        ensureTable();
        String sql = "INSERT INTO " + TABLE + " (kv_key, kv_value, expires_at) VALUES (?, ?, NULL) "
                + "ON DUPLICATE KEY UPDATE kv_value = VALUES(kv_value), expires_at = NULL";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("JdbcKvEngine put 失败: " + key, e);
        }
    }

    @Override
    public boolean containsKey(String key) {
        return get(key) != null;
    }

    @Override
    public boolean delete(String key) {
        ensureTable();
        String sql = "DELETE FROM " + TABLE + " WHERE kv_key = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("JdbcKvEngine delete 失败: " + key, e);
        }
    }

    @Override
    public long incr(String key) {
        ensureTable();
        // 不存在/已过期从 1 起计；存在则原子 +1（非数字旧值按 0 处理）
        String upsert = "INSERT INTO " + TABLE + " (kv_key, kv_value, expires_at) VALUES (?, '1', NULL) "
                + "ON DUPLICATE KEY UPDATE "
                + "kv_value = IF(expires_at IS NOT NULL AND expires_at < ?, '1', "
                + "CAST(CAST(kv_value AS SIGNED) + 1 AS CHAR)), expires_at = NULL";
        String select = "SELECT kv_value FROM " + TABLE + " WHERE kv_key = ?";
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement(upsert)) {
                ps.setString(1, key);
                ps.setLong(2, System.currentTimeMillis());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(select)) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String value = rs.getString(1);
                        return value == null ? 0L : Long.parseLong(value);
                    }
                    return 0L;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("JdbcKvEngine incr 失败: " + key, e);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @Override
    public boolean supportsTtl() {
        return true;
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        ensureTable();
        String sql = "INSERT INTO " + TABLE + " (kv_key, kv_value, expires_at) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE kv_value = VALUES(kv_value), expires_at = VALUES(expires_at)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.setLong(3, System.currentTimeMillis() + ttl.toMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("JdbcKvEngine put(ttl) 失败: " + key, e);
        }
    }

    @Override
    public long ttl(String key) {
        ensureTable();
        String sql = "SELECT expires_at FROM " + TABLE + " WHERE kv_key = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return -2L;
                }
                long expiresAt = rs.getLong(1);
                if (rs.wasNull()) {
                    return -1L;
                }
                long remainingMs = expiresAt - System.currentTimeMillis();
                if (remainingMs <= 0) {
                    delete(key);
                    return -2L;
                }
                return (remainingMs + 999) / 1000;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("JdbcKvEngine ttl 失败: " + key, e);
        }
    }

    @Override
    public void expire(String key, long seconds) {
        ensureTable();
        String sql = "UPDATE " + TABLE + " SET expires_at = ? WHERE kv_key = ? "
                + "AND (expires_at IS NULL OR expires_at >= ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis() + seconds * 1000L);
            ps.setString(2, key);
            ps.setLong(3, System.currentTimeMillis());
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException("键不存在或已过期: " + key);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("JdbcKvEngine expire 失败: " + key, e);
        }
    }

    @Override
    public Map<String, String> findAllByPrefix(String prefix) {
        ensureTable();
        // 范围扫描替代 LIKE：前缀有序性 + Java 侧 startsWith 精确过滤，规避通配符转义问题
        String upper = prefix + Character.MAX_VALUE;
        String sql = "SELECT kv_key, kv_value, expires_at FROM " + TABLE + " WHERE kv_key >= ? AND kv_key < ?";
        Map<String, String> result = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, prefix);
            ps.setString(2, upper);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString(1);
                    String value = rs.getString(2);
                    long expiresAt = rs.getLong(3);
                    boolean hasExpiry = !rs.wasNull();
                    if (hasExpiry && expiresAt < System.currentTimeMillis()) {
                        delete(key);
                        continue;
                    }
                    if (key != null && key.startsWith(prefix)) {
                        result.put(key, value);
                    }
                }
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("JdbcKvEngine findAllByPrefix 失败: " + prefix, e);
        }
    }

    /**
     * 懒建表：只在首次使用时执行 DDL。
     */
    private void ensureTable() {
        if (tableReady) {
            return;
        }
        synchronized (this) {
            if (tableReady) {
                return;
            }
            String ddl = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "kv_key VARCHAR(768) NOT NULL, "
                    + "kv_value MEDIUMTEXT NULL, "
                    + "expires_at BIGINT NULL, "
                    + "PRIMARY KEY (kv_key)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute(ddl);
                tableReady = true;
            } catch (SQLException e) {
                throw new IllegalStateException("JdbcKvEngine 建表失败: " + TABLE, e);
            }
        }
    }
}
