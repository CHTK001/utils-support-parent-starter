package com.chua.greptimedb.support.client;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
* greptimedb JDBC 客户端：经 MySQL 协议（默认 {@value #DEFAULT_MYSQL_PORT} 端口）执行真实 SQL。
* <p>
* 这是官方文档推荐的查询方式——"支持 MySQL 或 PostgreSQL 的成熟 SQL Driver"。
* 使用 {@link PreparedStatement} 参数绑定，杜绝 SQL 注入；
* 连接异常时自动重建，单连接串行执行。
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
    * @return jdbc结果的结果
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
    * 构造客户端实例。
    *
    * @param jdbcUrl  形如 {@code jdbc:mysql://host:4002/public?useSSL=false}
    * @param user     用户名，可为空
    * @param password 密码，可为空
     */
    public GreptimeJdbcClient(String jdbcUrl, String user, String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    /**
    * 由 gRPC 端点推导 JDBC URL（同主机 + {@value #DEFAULT_MYSQL_PORT} 端口）。
    *
    * @param endpoint 形如 {@code 172.16.0.40:4001}
    * @param database 数据库名
    * @return 形如 {@code jdbc:mysql://host:4002/db?useSSL=false...}
     */
    public static String jdbcUrlFromEndpoint(String endpoint, String database) {
        String hostPart = endpoint == null ? "" : endpoint.replaceFirst("^https?://", "");
        int colon = hostPart.indexOf(':');
        String host = colon >= 0 ? hostPart.substring(0, colon) : hostPart;
        String db = database == null || database.isEmpty() ? "public" : database;
        return "jdbc:mysql://" + host + ":" + DEFAULT_MYSQL_PORT + "/" + db + JDBC_PARAMS;
    }

    /**
    * 获取可用连接，连接缺失或已关闭时自动重建。
    *
    * @return JDBC 连接
    * @throws SQLException 建连失败
     */
    private synchronized Connection conn() throws SQLException {
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
                // 关闭失败无需处理
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
    * @param sql    含 {@code ?} 占位符的 SQL
    * @param params 占位符参数，可为 空
    * @return 列名与行数据
    * @throws SQLException 执行失败
     */
    public JdbcResult query(String sql, List<Object> params) throws SQLException {
        try {
            PreparedStatement ps = conn().prepareStatement(sql);
            try {
                bind(ps, params);
                ResultSet rs = ps.executeQuery();
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
            } finally {
                ps.close();
            }
        } catch (SQLException e) {
            invalidate(e);
            throw e;
        }
    }

    /**
    * 执行 DML/DDL 语句。
    *
    * @param sql    含 {@code ?} 占位符的 SQL
    * @param params 占位符参数，可为 空
    * @return 服务端报告的影响行数
    * @throws SQLException 执行失败
     */
    public int update(String sql, List<Object> params) throws SQLException {
        try {
            PreparedStatement ps = conn().prepareStatement(sql);
            try {
                bind(ps, params);
                return ps.executeUpdate();
            } finally {
                ps.close();
            }
        } catch (SQLException e) {
            invalidate(e);
            throw e;
        }
    }

    /**
    * 批量执行同构 DML 语句。
    *
    * @param sql        含 {@code ?} 占位符的 SQL
    * @param paramList  每行参数数组
    * @return 每行影响行数
    * @throws SQLException 执行失败
     */
    public int[] batch(String sql, List<Object[]> paramList) throws SQLException {
        if (paramList == null || paramList.isEmpty()) {
            return new int[0];
        }
        try {
            PreparedStatement ps = conn().prepareStatement(sql);
            try {
                for (Object[] row : paramList) {
                    ps.clearParameters();
                    for (int i = 0; i < row.length; i++) {
                        ps.setObject(i + 1, row[i]);
                    }
                    ps.addBatch();
                }
                return ps.executeBatch();
            } finally {
                ps.close();
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
    * 关闭底层连接并释放资源。
     */
    @Override
    public synchronized void close() {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException ignored) {
            // 关闭失败无需处理
        } finally {
            connection = null;
        }
    }
}
