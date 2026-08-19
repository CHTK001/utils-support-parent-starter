package com.chua.datasync.agent.support.source;

import com.chua.datasync.agent.support.DataSyncAgentSource;
import com.chua.datasync.agent.support.model.Direction;
import com.chua.datasync.agent.support.model.Directional;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;

/**
 * JDBC 数据同步 Source，从关系型数据库读取数据。
 * <p>
 * 基于 HikariCP 连接池，支持增量读取：
 * 当 params["offset"] 有值时，追加 WHERE id > offset 条件过滤已读行。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class JdbcDataSyncAgentSource implements DataSyncAgentSource, Directional {

    /** 数据源标识 */
    /** 来源ID */
    private final String sourceId;
    /** 输入标识 */
    /** 输入ID */
    private final String inputId;
    /** JDBC 连接地址 */
    /** JDBCURL */
    private final String jdbcUrl;
    /** 用户名 */
    /** Username */
    private final String username;
    /** 密码 */
    private final String password;
    /** SQL 语句 */
    /** SQL */
    private final String sql;
    /** 列名数组 */
    /** 列names */
    private final String[] columnNames;
    /** 数据源 */
    /** 数据来源 */
    private final HikariDataSource dataSource;

    public JdbcDataSyncAgentSource(String sourceId, String inputId, String jdbcUrl, String username, String password, String sql, String... columnNames) {
        this.sourceId = sourceId;
        this.inputId = inputId;
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.sql = sql;
        this.columnNames = columnNames;
        this.dataSource = createDataSource(jdbcUrl, username, password);
    }

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
    public String sourceId() {
        return sourceId;
    }

    @Override
    public String inputId() {
        return inputId;
    }

    @Override
    public Flux<Map<String, Object>> read(Map<String, Object> params) {
        Object offsetObj = null;
        if (params != null) {
            offsetObj = params.get("offset");
        }
        String sqlWithOffset = buildSqlWithOffset(offsetObj);

        log.info("[JdbcDataSyncAgentSource] 开始读取数据, sourceId={}, sql={}", sourceId, sqlWithOffset);

        // Connection/PreparedStatement 需在订阅时创建，避免 Flux 惰性执行时语句已被关闭
        return Flux.create(sink -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sqlWithOffset,
                         ResultSet.TYPE_FORWARD_ONLY,
                         ResultSet.CONCUR_READ_ONLY)) {
                ps.setFetchSize(500);
                ps.setFetchDirection(ResultSet.FETCH_FORWARD);
                try (ResultSet rs = ps.executeQuery()) {
                    int count = 0;
                    int colCount = columnNames.length;
                    while (rs.next()) {
                        Map<String, Object> row = new HashMap<>();
                        for (int i = 0; i < colCount; i++) {
                            row.put(columnNames[i], rs.getObject(columnNames[i]));
                        }
                        sink.next(row);
                        count++;
                    }
                    sink.complete();
                    log.info("[JdbcDataSyncAgentSource] 读取完成, sourceId={}, count={}", sourceId, count);
                } catch (Exception e) {
                    sink.error(e);
                    log.error("[JdbcDataSyncAgentSource] 读取异常, sourceId={}", sourceId, e);
                }
            } catch (SQLException e) {
                sink.error(e);
                log.error("[JdbcDataSyncAgentSource] 连接数据库失败, sourceId={}", sourceId, e);
            }
        });
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("[JdbcDataSyncAgentSource] 连接池已关闭, sourceId={}", sourceId);
        }
    }

    @Override
    public Direction direction() {
        return Direction.INPUT;
    }

    private String buildSqlWithOffset(Object offsetValue) {
        if (offsetValue == null) {
            return sql;
        }
        if (sql.trim().toUpperCase().startsWith("SELECT")) {
            if (offsetValue instanceof Number) {
                return sql + " WHERE id > " + offsetValue + " ORDER BY id ASC";
            }
            return sql + " WHERE id > '" + offsetValue.toString().replace("'", "''") + "' ORDER BY id ASC";
        }
        return sql;
    }
}