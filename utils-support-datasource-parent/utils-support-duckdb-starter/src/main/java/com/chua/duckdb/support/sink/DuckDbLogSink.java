package com.chua.duckdb.support.sink;

import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.spi.sink.DataSink;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;


/**
 * DuckDB 日志数据落地实现，将日志消息按 key/value 形式写入本地 DuckDB 表。
 * <p>
 * SPI 类型 {@code "duckdb-log"}，启动时自动建表 {@code datalake_log}。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("duckdb-log")
public class DuckDbLogSink implements DataSink {

    /**
     * datalake_log 表 DDL：trace_id / pipeline_id / topic / log_key / log_value / log_timestamp / created_at
     */
    private static final String TABLE_DDL = """
            CREATE TABLE IF NOT EXISTS datalake_log (
                id BIGSERIAL,
                trace_id VARCHAR,
                pipeline_id VARCHAR,
                topic VARCHAR,
                log_key VARCHAR,
                log_value VARCHAR,
                log_timestamp BIGINT,
                created_at TIMESTAMP DEFAULT now()
            )
            """;

    /**
     * 插入日志行的 SQL
     */
    private static final String INSERT_SQL = """
            INSERT INTO datalake_log (trace_id, pipeline_id, topic, log_key, log_value, log_timestamp)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    /**
     * JDBC 连接串（默认内嵌 duckdb）
     */
    private String jdbcUrl = "jdbc:duckdb:";

    /**
     * 当前数据库连接
     */
    private Connection connection;

    /**
     * 默认构造函数（SPI 框架使用）
     */
    public DuckDbLogSink() {
    }

    /**
     * 设置 JDBC 连接串。
     *
     * @param jdbcUrl jdbc:duckdb:... 或 jdbc:duckdb:/path/to/file.db
     */
    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    @Override
    /** Type */
    public String type() {
        return "duckdb-log";
    }

    @Override
    /** 开始 */
    public void start() {
        try {
            connection = DriverManager.getConnection(jdbcUrl);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(TABLE_DDL);
            }
            log.info("[duckdb-sink] 已连接: {}", jdbcUrl);
        } catch (Exception e) {
            throw new RuntimeException("DuckDbLogSink start failed: " + e.getMessage(), e);
        }
    }

    @Override
    /** 停止 */
    public void stop() {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                log.warn("[duckdb-sink] 关闭连接异常", e);
            }
        }
    }

    @Override
    /** 写入 */
    public boolean write(DataEnvelope envelope, Map<String, Object> config) {
        if (connection == null) {
            log.warn("[duckdb-sink] 未连接，无法写入");
            return false;
        }
        try {
            Map<String, Object> data = envelope.getParsed();
            if (data == null || data.isEmpty()) {
                return true;
            }
            String topic = envelope.getTopics() != null && !envelope.getTopics().isEmpty()
                    ? envelope.getTopics().iterator().next() : "default";

            try (PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
                for (Map.Entry<String, Object> entry : data.entrySet()) {
                    ps.setString(1, envelope.getTraceId());
                    ps.setString(2, envelope.getPipelineId());
                    ps.setString(3, topic);
                    ps.setString(4, entry.getKey());
                    ps.setString(5, entry.getValue() != null ? entry.getValue().toString() : null);
                    ps.setLong(6, envelope.getTimestamp());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return true;
        } catch (Exception e) {
            log.error("[duckdb-sink] 写入异常", e);
            return false;
        }
    }

    @Override
    public EngineDataSource<?> getDataSource() {
        return null;
    }
}
