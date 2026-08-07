package com.chua.gateway.server.store;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.gateway.server.config.GatewayProperties;
import lombok.extern.slf4j.Slf4j;

import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 默认连接存储（嵌入式 sqlite）。
 *
 * <p>表结构 {@code connections}：
 *   - id INTEGER PRIMARY KEY AUTOINCREMENT
 *   - key TEXT UNIQUE  (服务端预配置 key，可空)
 *   - protocol TEXT NOT NULL
 *   - host TEXT NOT NULL
 *   - port INTEGER NOT NULL
 *   - user TEXT
 *   - password TEXT
 *   - updated_at INTEGER DEFAULT (strftime('%s','now'))
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi(value = "sqlite", order = 100)
public final class SqliteConnectionStore implements ConnectionStore {

    /**
     * sqlite JDBC 前缀
     */
    private static final String JDBC_PREFIX = "jdbc:sqlite:";

    /**
     * 建表 SQL
     */
    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS connections (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                key TEXT UNIQUE,
                protocol TEXT NOT NULL,
                host TEXT NOT NULL,
                port INTEGER NOT NULL,
                user TEXT,
                password TEXT,
                updated_at INTEGER DEFAULT (strftime('%s','now'))
            )
            """;

    /**
     * 按 key 查找
     */
    private static final String SQL_FIND_BY_KEY =
            "SELECT key,protocol,host,port,user,password FROM connections WHERE key = ?";

    /**
     * 按 (protocol,host,port) 查找
     */
    private static final String SQL_FIND_BY_TARGET =
            "SELECT key,protocol,host,port,user,password FROM connections " +
                    "WHERE protocol = ? AND host = ? AND port = ? LIMIT 1";

    /**
     * 插入或更新
     */
    private static final String SQL_UPSERT =
            "INSERT INTO connections (key,protocol,host,port,user,password) VALUES (?,?,?,?,?,?) " +
                    "ON CONFLICT(protocol,host,port) DO UPDATE SET " +
                    "user=excluded.user, password=excluded.password, updated_at=strftime('%s','now')";

    /**
     * 列出全部 key
     */
    private static final String SQL_LIST_KEYS =
            "SELECT key FROM connections WHERE key IS NOT NULL AND key <> '' ORDER BY id";

    /**
     * 检查表列是否存在：user
     */
    private static final String SQL_CHECK_USER_COLUMN =
            "SELECT COUNT(*) FROM pragma_table_info('connections') WHERE name='user'";

    /**
     * 检查表列是否存在：password
     */
    private static final String SQL_CHECK_PASSWORD_COLUMN =
            "SELECT COUNT(*) FROM pragma_table_info('connections') WHERE name='password'";

    /**
     * 添加 user 列
     */
    private static final String SQL_ALTER_USER =
            "ALTER TABLE connections ADD COLUMN user TEXT";

    /**
     * 添加 password 列
     */
    private static final String SQL_ALTER_PASSWORD =
            "ALTER TABLE connections ADD COLUMN password TEXT";

    /**
     * JDBC 连接字符串
     */
    private final String jdbcUrl;

    public SqliteConnectionStore() {
        this.jdbcUrl = GatewayProperties.dbUrl();
    }

    /**
     * 初始化（建表 + 列兼容）。
     */
    @Override
    public void init() {
        try (java.sql.Connection conn = openConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE_SQL);
            ensureColumn(conn, SQL_CHECK_USER_COLUMN, SQL_ALTER_USER);
            ensureColumn(conn, SQL_CHECK_PASSWORD_COLUMN, SQL_ALTER_PASSWORD);
            log.info("SqliteConnectionStore 初始化完成: {}", jdbcUrl);
        } catch (SQLException e) {
            throw new IllegalStateException("初始化连接存储失败: " + jdbcUrl, e);
        }
    }

    /**
     * 检查表列是否存在；不存在则补建。
     *
     * @param conn          sqlite 连接（完整 java.sql.Connection FQN）
     * @param checkSql      列检查 SQL
     * @param alterSql      列添加 SQL
     * @throws SQLException 查询或修改失败
     */
    private void ensureColumn(java.sql.Connection conn, String checkSql, String alterSql) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(checkSql)) {
            if (rs.next() && rs.getInt(1) > 0) {
                return;
            }
            stmt.execute(alterSql);
            log.info("已添加列: {}", alterSql);
        }
    }

    /**
     * 打开一个 sqlite 连接。
     *
     * @return JDBC 连接
     * @throws SQLException 连接失败
     */
    private java.sql.Connection openConnection() throws SQLException {
        if (!jdbcUrl.startsWith(JDBC_PREFIX)) {
            throw new SQLException("不支持的 JDBC URL: " + jdbcUrl);
        }
        return DriverManager.getConnection(jdbcUrl);
    }

    /**
     * 将 ResultSet 当前行映射为 Connection record。
     *
     * @param rs 已就位的 ResultSet
     * @return Connection 实例
     * @throws SQLException 读取失败
     */
    private Connection mapRow(ResultSet rs) throws SQLException {
        return new Connection(
                rs.getString("protocol"),
                rs.getString("host"),
                rs.getInt("port"),
                rs.getString("user"),
                rs.getString("password"),
                rs.getString("key"));
    }

    @Override
    public Optional<Connection> findByKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        try (java.sql.Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_FIND_BY_KEY)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            log.warn("按 key 查找连接失败: key={} err={}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Connection upsertByTarget(String protocol, String host, int port, String user, String password) {
        Connection existing = findByTarget(protocol, host, port);
        if (existing != null) {
            return existing;
        }
        try (java.sql.Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_UPSERT)) {
            ps.setString(1, null);
            ps.setString(2, protocol);
            ps.setString(3, host);
            ps.setInt(4, port);
            ps.setString(5, user);
            ps.setString(6, password);
            ps.executeUpdate();
            log.info("新建连接: protocol={} host={} port={}", protocol, host, port);
        } catch (SQLException e) {
            log.warn("写入连接失败: protocol={} host={} port={} err={}",
                    protocol, host, port, e.getMessage());
        }
        Connection persisted = findByTarget(protocol, host, port);
        if (persisted == null) {
            throw new IllegalStateException("写入连接后无法读回: " + protocol + "@" + host + ":" + port);
        }
        return persisted;
    }

    /**
     * 按 (protocol,host,port) 查找连接。
     *
     * @param protocol 协议
     * @param host     主机
     * @param port     端口
     * @return 命中返回连接；未命中返回 {@code null}
     */
    private Connection findByTarget(String protocol, String host, int port) {
        try (java.sql.Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_FIND_BY_TARGET)) {
            ps.setString(1, protocol);
            ps.setString(2, host);
            ps.setInt(3, port);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        } catch (SQLException e) {
            log.warn("按 target 查找连接失败: {}://{}:{} err={}", protocol, host, port, e.getMessage());
        }
        return null;
    }

    @Override
    public List<String> listKeys() {
        List<String> keys = new ArrayList<>();
        try (java.sql.Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_LIST_KEYS);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                keys.add(rs.getString(1));
            }
        } catch (SQLException e) {
            log.warn("列出预配置 key 失败: {}", e.getMessage());
        }
        return keys;
    }
}
