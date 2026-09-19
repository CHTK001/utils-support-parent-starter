package com.chua.datasync.agent.support.sink;

import com.chua.datasync.agent.support.DataSyncAgentSink;
import com.chua.datasync.agent.support.exception.DataSyncErrorCode;
import com.chua.datasync.agent.support.model.Direction;
import com.chua.datasync.agent.support.model.Directional;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.sql.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JDBC 数据同步 Sink，将数据批量写入关系型数据库。
 * <p>
 * 基于 hikaricp 连接池，支持批量写入与事务管理。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class JdbcDataSyncAgentSink implements DataSyncAgentSink, Directional {

    /** 写入端标识 */
    private final String sinkId;
    /** JDBC 连接地址 */
    private final String jdbcUrl;
    /** 用户名 */
    private final String username;
    /** 密码 */
    private final String password;
    /** SQL 语句 */
    private final String sql;
    /** 列名数组 */
    private final String[] columnNames;
    /** 数据源 */
    private final HikariDataSource dataSource;
    /** 批次大小 */
    private final int batchSize;

    /** 是否已初始化 */
    private volatile boolean initialized = false;

    /**
     * 创建 jdbc数据同步Agentsink 实例
     * @param sinkId sinkid
     * @param sinkId 字符串
     * @param sinkId 字符串
     * @param sinkId 字符串
     * @param sinkId 字符串
     * @param sinkId 字符串
     * @param columnNames column名称
     * @param jdbcUrl jdbcurl
     * @param username 用户名
     * @param password 密码
     * @param sql SQL
     */
    public JdbcDataSyncAgentSink(String sinkId, String jdbcUrl, String username, String password, String sql, String... columnNames) {
        this(sinkId, jdbcUrl, username, password, sql, 100, columnNames);
    }

    /**
     * 创建 jdbc数据同步Agentsink 实例
     * @param sinkId sinkid
     * @param sinkId 字符串
     * @param sinkId 字符串
     * @param sinkId 字符串
     * @param sinkId 字符串
     * @param batchSize int
     * @param sinkId 字符串
     * @param columnNames column名称
     * @param jdbcUrl jdbcurl
     * @param username 用户名
     * @param password 密码
     * @param sql SQL
     * @param batchSize 批量大小
     */
    public JdbcDataSyncAgentSink(String sinkId, String jdbcUrl, String username, String password, String sql, int batchSize, String... columnNames) {
        this.sinkId = sinkId;
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.sql = sql;
        this.columnNames = columnNames;
        this.batchSize = batchSize;
        this.dataSource = createDataSource(jdbcUrl, username, password);
    }

    /**
     * 创建数据源
     *
     * @param url url
     * @param user 用户
     * @param pass 通过
     * @return 创建数据源的结果
     */
    private HikariDataSource createDataSource(String url, String user, String pass) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(pass);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        return new HikariDataSource(config);
    }

    @Override
    /** sinkid */
    public String sinkId() {
        return sinkId;
    }

    @Override
    /** 写入 */
    public void write(Flux<Map<String, Object>> data) {
        log.info("[JdbcDataSyncAgentSink] 开始写入数据, sinkId={}, batchSize={}, sql={}", sinkId, batchSize, sql);

        AtomicLong totalCount = new AtomicLong(0);
        try {
            Connection connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            PreparedStatement ps = connection.prepareStatement(sql);

            data.buffer(batchSize).subscribe(
                    batch -> processBatch(batch, ps, connection, totalCount),
                    error -> handleError(error, connection),
                    () -> handleComplete(connection, ps, totalCount.get())
            );
        } catch (SQLException e) {
            log.error("[JdbcDataSyncAgentSink] 获取连接失败, sinkId={}", sinkId, e);
        }
    }

    /**
     * 处理批量
     *
     * @param batch 批量
     * @param ps ps
     * @param conn conn
     * @param totalCount total数量
     */
    private void processBatch(java.util.List<Map<String, Object>> batch, PreparedStatement ps, Connection conn, AtomicLong totalCount) {
        try {
            for (Map<String, Object> row : batch) {
                for (int i = 0; i < columnNames.length; i++) {
                    Object value = row.get(columnNames[i]);
                    ps.setObject(i + 1, value);
                }
                ps.addBatch();
            }
            int[] results = ps.executeBatch();
            conn.commit();

            long count = results.length;
            totalCount.addAndGet(count);
            log.debug("[JdbcDataSyncAgentSink] 批次提交成功, sinkId={}, count={}", sinkId, count);
        } catch (SQLException e) {
            log.error("[JdbcDataSyncAgentSink] " + DataSyncErrorCode.DB_WRITE_FAILED.formatWithCode(sinkId, sql, e.getMessage()), e);
            rollbackQuietly(conn);
        }
    }

    /**
     * 处理记录错误
     *
     * @param error 错误
     * @param conn conn
     */
    private void handleError(Throwable error, Connection conn) {
        log.error("[JdbcDataSyncAgentSink] " + DataSyncErrorCode.DB_WRITE_FAILED.formatWithCode(sinkId, sql, error.getMessage()), error);
        rollbackQuietly(conn);
    }

    /**
     * 处理完成
     *
     * @param conn conn
     * @param ps ps
     * @param totalCount total数量
     */
    private void handleComplete(Connection conn, PreparedStatement ps, long totalCount) {
        log.info("[JdbcDataSyncAgentSink] 写入完成, sinkId={}, totalCount={}", sinkId, totalCount);
        closeQuietly(ps);
        closeQuietly(conn);
    }

    /**
     * 回滚Quietly
     *
     * @param conn conn
     */
    private void rollbackQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
            }
        }
    }

    /**
     * 关闭Quietly
     *
     * @param stmt stmt
     */
    private void closeQuietly(Statement stmt) {
        if (stmt != null) {
            try {
                stmt.close();
            } catch (SQLException ignored) {
            }
        }
    }

    /**
     * 关闭Quietly
     *
     * @param conn conn
     */
    private void closeQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.setAutoCommit(true);
                conn.close();
            } catch (SQLException ignored) {
            }
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("[JdbcDataSyncAgentSink] 连接池已关闭, sinkId={}", sinkId);
        }
    }

    @Override
    /** Direction */
    public Direction direction() {
        return Direction.OUTPUT;
    }
}
