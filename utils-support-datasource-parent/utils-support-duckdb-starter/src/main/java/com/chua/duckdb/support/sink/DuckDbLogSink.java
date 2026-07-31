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


@Slf4j
@Spi("duckdb-log")
public class DuckDbLogSink implements DataSink {

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

    private static final String INSERT_SQL = """
            INSERT INTO datalake_log (trace_id, pipeline_id, topic, log_key, log_value, log_timestamp)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private String jdbcUrl = "jdbc:duckdb:";
    private Connection connection;

    public DuckDbLogSink() {
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    @Override
    public String type() {
        return "duckdb-log";
    }

    @Override
    public void start() {
        try {
            connection = DriverManager.getConnection(jdbcUrl);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(TABLE_DDL);
            }
            log.info("[DuckDbLogSink] connected: {}", jdbcUrl);
        } catch (Exception e) {
            throw new RuntimeException("DuckDbLogSink start failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void stop() {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                log.warn("[DuckDbLogSink] close error", e);
            }
        }
    }

    @Override
    public boolean write(DataEnvelope envelope, Map<String, Object> config) {
        if (connection == null) {
            log.warn("[DuckDbLogSink] not connected");
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
            log.error("[DuckDbLogSink] write error", e);
            return false;
        }
    }

    @Override
    public EngineDataSource<?> getDataSource() {
        return null;
    }
}
