package com.chua.greptimedb.support.client;

import com.chua.common.support.lang.datasource.dialect.SqlName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * greptimedb JDBC 客户端：经 MySQL 协议（默认 {@value #DEFAULT_MYSQL_PORT} 端口）执行真实 SQL。
 * <p>
 * 这是官方文档推荐的查询方式——"支持 MySQL 或 PostgreSQL 的成熟 SQL Driver"。
 * 使用 {@link PreparedStatement} 参数绑定，杜绝 SQL 注入；
 * 连接异常时自动重建，单连接串行执行（公开方法加锁，避免同一连接上的报文交叉）。
 * </p>
 * <p>
 * 生命周期：{@link #close()} 之后本实例不可再用，再次执行会抛出 {@link SQLException}；
 * 需要重新访问请由上层引擎新建实例。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class GreptimeJdbcClient implements AutoCloseable {

    /**
     * MySQL 协议默认端口
     */
    private static final int DEFAULT_MYSQL_PORT = 4002;

    /**
     * 缺省数据库名
     */
    private static final String DEFAULT_DATABASE = "public";

    /**
     * 合法主机字面量（域名 / IPv4 / 带方括号的 IPv6），排除 {@code ? & # /} 等 URL 分隔符，
     * 防止端点串中夹带额外连接参数
     */
    private static final Pattern SAFE_HOST = Pattern.compile("^[A-Za-z0-9_.:\\[\\]-]+$");

    /**
     * 连接参数：关闭 SSL 探测、允许公钥获取、超时控制；
     * 会话时区固定 UTC —— greptimedb 经 MySQL 协议返回的 时间戳 为 UTC 墙钟值，
     * 固定后驱动解析出的 轮次 与写入值严格一致，不受应用机时区影响。
     */
    private static final String JDBC_PARAMS = "?useSSL=false&allowPublicKeyRetrieval=true"
            + "&connectTimeout=10000&socketTimeout=60000"
            + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";

    /**
     * 查询结果集。
     *
     * @param columns 列名列表（按 选择 顺序）
     * @param rows    已物化的行数据，每行为与 columns 等长的数组
     */
    public record JdbcResult(List<String> columns, List<Object[]> rows) {
    }

    /**
     * 数据库连接
     */
    private Connection connection;

    /**
     * 连接地址
     */
    private final String jdbcUrl;

    /**
     * 用户名（空表示无鉴权）
     */
    private final String user;

    /**
     * 密码
     */
    private final String password;

    /**
     * 是否已关闭：关闭后禁止重建连接，避免连接泄漏到生命周期之外
     */
    private boolean closed;

    /**
     * 构造客户端实例（延迟建连：首次执行 SQL 时才真正连接）。
     *
     * @param jdbcUrl  形如 {@code jdbc:mysql://host:4002/public?useSSL=false}，不可为 空
     * @param user     用户名，可为空
     * @param password 密码，可为空
     * @throws IllegalArgumentException JDBC 地址为空时抛出
     */
    public GreptimeJdbcClient(String jdbcUrl, String user, String password) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("GreptimeDB JDBC URL 不能为空");
        }
        this.jdbcUrl = jdbcUrl.trim();
        this.user = user;
        this.password = password;
    }

    /**
     * 由 gRPC 端点推导 JDBC URL（同主机 + {@value #DEFAULT_MYSQL_PORT} 端口）。
     *
     * @param endpoint 形如 {@code 172.16.0.40:4001}，兼容 {@code http://host:4001/path} 与 IPv6 写法
     * @param database 数据库名，为空时取 {@code public}
     * @return 形如 {@code jdbc:mysql://host:4002/db?useSSL=false...}
     * @throws IllegalArgumentException 端点为空、主机字面量非法或数据库名含非法字符时抛出
     */
    public static String jdbcUrlFromEndpoint(String endpoint, String database) {
        String host = hostOf(endpoint);
        String db = database == null || database.trim().isEmpty() ? DEFAULT_DATABASE : database.trim();
        if (!SqlName.isWord(db)) {
            throw new IllegalArgumentException("非法的 GreptimeDB 数据库名: " + database);
        }
        return "jdbc:mysql://" + host + ":" + DEFAULT_MYSQL_PORT + "/" + db + JDBC_PARAMS;
    }

    /**
     * 从端点串中解析主机部分：剥离协议前缀与路径段，端口部分丢弃（MySQL 端口固定推导）。
     *
     * @param endpoint 原始端点串
     * @return 主机字面量（IPv6 保留方括号）
     * @throws IllegalArgumentException 端点非法时抛出
     */
    public static String hostOf(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("GreptimeDB 端点不能为空");
        }
        String rest = endpoint.trim().replaceFirst("(?i)^https?://", "");
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash);
        }
        String host;
        if (rest.startsWith("[")) {
            int close = rest.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("GreptimeDB 端点 IPv6 字面量不完整: " + endpoint);
            }
            host = rest.substring(0, close + 1);
        } else {
            int colon = rest.indexOf(':');
            host = colon >= 0 ? rest.substring(0, colon) : rest;
        }
        host = host.trim();
        if (host.isEmpty() || !SAFE_HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("GreptimeDB 端点缺少合法主机部分: " + endpoint);
        }
        return host;
    }

    /**
     * 获取可用连接，连接缺失或已关闭时自动重建。
     * <p>本实例已 {@link #close()} 时直接拒绝，避免关闭后复活连接。</p>
     *
     * @return JDBC 连接
     * @throws SQLException 建连失败或客户端已关闭
     */
    private synchronized Connection conn() throws SQLException {
        if (closed) {
            throw new SQLException("GreptimeDB JDBC 客户端已关闭，拒绝继续执行 SQL: " + jdbcUrl);
        }
        if (connection == null || connection.isClosed()) {
            if (user != null && !user.isEmpty()) {
                connection = DriverManager.getConnection(jdbcUrl, user, password);
            } else {
                connection = DriverManager.getConnection(jdbcUrl);
            }
        }
        return connection;
    }

    /**
     * 连接级异常后丢弃旧连接，下次调用重建。
     *
     * @param e 触发判断的 SQL 异常
     */
    private synchronized void invalidate(SQLException e) {
        if (isConnectionError(e)) {
            try {
                if (connection != null) {
                    connection.close();
                }
            } catch (SQLException ignored) {
                // 关闭失败无需处理：连接已被判定失效，下一步即置空重建
            }
            connection = null;
        }
    }

    /**
     * 判断是否为连接类异常（可安全通过重建连接恢复）。
     *
     * @param e 待判断的 SQL 异常
     * @return true 表示连接类异常
     */
    private static boolean isConnectionError(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLRecoverableException
                    || t instanceof java.sql.SQLNonTransientConnectionException
                    || t instanceof java.net.SocketException
                    || t instanceof java.io.EOFException) {
                return true;
            }
        }
        // SQL状态 08xxx 为连接类异常
        String state = e.getSQLState();
        return state != null && state.startsWith("08");
    }

    /**
     * 执行查询并物化结果集。
     *
     * @param sql    含 {@code ?} 占位符的 SQL，不可为 空
     * @param params 占位符参数，可为 空
     * @return 列名与行数据，非 空
     * @throws SQLException 执行失败
     */
    public synchronized JdbcResult query(String sql, List<Object> params) throws SQLException {
        if (sql == null || sql.isBlank()) {
            throw new SQLException("GreptimeDB 查询 SQL 不能为空");
        }
        try {
            try (PreparedStatement ps = conn().prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    List<String> cols = new ArrayList<>(n);
                    for (int i = 1; i <= n; i++) {
                        cols.add(md.getColumnLabel(i));
                    }
                    List<Object[]> rows = new ArrayList<>();
                    while (rs.next()) {
                        Object[] row = new Object[n];
                        for (int i = 1; i <= n; i++) {
                            row[i - 1] = rs.getObject(i);
                        }
                        rows.add(row);
                    }
                    return new JdbcResult(cols, rows);
                }
            }
        } catch (SQLException e) {
            invalidate(e);
            throw e;
        }
    }

    /**
     * 执行 DML/DDL 语句。
     *
     * @param sql    含 {@code ?} 占位符的 SQL，不可为 空
     * @param params 占位符参数，可为 空
     * @return 服务端报告的影响行数
     * @throws SQLException 执行失败
     */
    public synchronized int update(String sql, List<Object> params) throws SQLException {
        if (sql == null || sql.isBlank()) {
            throw new SQLException("GreptimeDB 执行 SQL 不能为空");
        }
        try {
            try (PreparedStatement ps = conn().prepareStatement(sql)) {
                bind(ps, params);
                return ps.executeUpdate();
            }
        } catch (SQLException e) {
            invalidate(e);
            throw e;
        }
    }

    /**
     * 批量执行同构 DML 语句。
     *
     * @param sql       含 {@code ?} 占位符的 SQL，不可为 空
     * @param paramList 每行参数数组，空集合直接返回零长度数组
     * @return 每行影响行数
     * @throws SQLException 执行失败
     */
    public synchronized int[] batch(String sql, List<Object[]> paramList) throws SQLException {
        if (sql == null || sql.isBlank()) {
            throw new SQLException("GreptimeDB 批量执行 SQL 不能为空");
        }
        if (paramList == null || paramList.isEmpty()) {
            return new int[0];
        }
        try {
            try (PreparedStatement ps = conn().prepareStatement(sql)) {
                for (Object[] row : paramList) {
                    ps.clearParameters();
                    if (row != null) {
                        for (int i = 0; i < row.length; i++) {
                            ps.setObject(i + 1, row[i]);
                        }
                    }
                    ps.addBatch();
                }
                return ps.executeBatch();
            }
        } catch (SQLException e) {
            invalidate(e);
            throw e;
        }
    }

    /**
     * 按序绑定占位符参数。
     *
     * @param ps     预编译语句
     * @param params 参数列表，可为 空
     * @throws SQLException 绑定失败
     */
    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    /**
     * 关闭底层连接并释放资源；置关闭标志，重复调用无副作用。
     */
    @Override
    public synchronized void close() {
        closed = true;
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException ignored) {
            // 关闭失败无需处理：连接已废弃，交由服务端超时回收
        } finally {
            connection = null;
        }
    }
}
