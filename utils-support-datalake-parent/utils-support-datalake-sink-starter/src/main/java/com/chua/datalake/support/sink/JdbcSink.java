package com.chua.datalake.support.sink;

import com.chua.common.support.lang.datasource.dialect.SqlName;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.spi.sink.DataSink;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JDBC Sink — 落盘到关系型数据库。
 *
 * <p>{@link #write(DataEnvelope, Map)} 把 {@code envelope.parsed} 的一层字段拼成
 * 参数化 {@code INSERT} 写入目标表：表名与列名一律走
 * {@link SqlName} 的保守白名单，
 * 值全部用占位符绑定，配置值不会拼进 SQL 文本。</p>
 *
 * <p>目标有两种给法，优先级为「本次 config &gt; 实例配置」：</p>
 * <ul>
 *   <li>{@code table} — 目标表名；{@code dataSource} — {@link DataSource} 实例</li>
 *   <li>实例级 {@link #withTable(String)} / {@link #withDataSource(DataSource)}</li>
 * </ul>
 *
 * <p>两者任一缺失时 {@link #write} 返回 {@code false} 并记录错误，不再谎报落库成功。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("jdbc")
public class JdbcSink implements DataSink {

    /**
     * 实例级目标表
     */
    private volatile String table;
    /**
     * 实例级数据源
     */
    private volatile DataSource dataSource;

    /**
     * 写入一条数据到目标表。
     *
     * @param envelope 数据信封
     * @param config   可携带 {@code table} 与 {@code dataSource} 覆盖实例配置
     * @return true 表示影响行数为 1
     */
    @Override
    /**
     * 写入
    */
    public boolean write(DataEnvelope envelope, Map<String, Object> config) {
        Map<String, Object> row = envelope == null ? null : envelope.getParsed();
        if (row == null || row.isEmpty()) {
            log.warn("[datalake-sink] JdbcSink 跳过空数据: traceId={}",
                    envelope == null ? null : envelope.getTraceId());
            return false;
        }
        DataSource target = resolveDataSource(config);
        String targetTable = resolveTable(config);
        if (target == null || targetTable == null) {
            log.error("[datalake-sink] JdbcSink 未配置落库目标（需要 table 与 dataSource），数据未写入: traceId={}",
                    envelope.getTraceId());
            return false;
        }

        List<String> columns = new ArrayList<>(row.size());
        List<Object> values = new ArrayList<>(row.size());
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            // CDC 元数据列（_cdc_op 等）不属于目标表结构
            if (entry.getKey().startsWith("_cdc_")) {
                continue;
            }
            columns.add(SqlName.check(entry.getKey(), "列名"));
            values.add(toColumnValue(entry.getValue()));
        }
        if (columns.isEmpty()) {
            log.warn("[datalake-sink] JdbcSink 无可写入列: traceId={}", envelope.getTraceId());
            return false;
        }

        String safeTable = SqlName.check(targetTable, "表名");
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(safeTable).append(" (");
        sql.append(String.join(", ", columns));
        sql.append(") VALUES (");
        for (int i = 0; i < columns.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        sql.append(')');

        try (Connection connection = target.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < values.size(); i++) {
                statement.setObject(i + 1, values.get(i));
            }
            int affected = statement.executeUpdate();
            if (affected != 1) {
                log.error("[datalake-sink] JdbcSink 写入影响行数异常: table={}, affected={}, traceId={}",
                        safeTable, affected, envelope.getTraceId());
                return false;
            }
            return true;
        } catch (SQLException e) {
            log.error("[datalake-sink] JdbcSink 写入失败: table={}, traceId={}, cause={}",
                    safeTable, envelope.getTraceId(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * 实例级目标表。
     *
     * @param table 目标表名
     * @return 当前 sink
     */
    public JdbcSink withTable(String table) {
        this.table = table;
        return this;
    }

    /**
     * 实例级数据源。
     *
     * @param dataSource 目标数据源
     * @return 当前 sink
     */
    public JdbcSink withDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
        return this;
    }

    @Override
    /**
     * 类型
    */
    public String type() {
        return "jdbc";
    }

    @Override
    /**
     * 开始
    */
    public void start() {
        log.info("[datalake-sink] JdbcSink 启动, table={}", table);
    }

    @Override
    /**
     * 停止
    */
    public void stop() {
        log.info("[datalake-sink] JdbcSink 停止");
    }

    @Override
    public EngineDataSource<?> getDataSource() {
        return null;
    }

    /**
     * 取本次写入的目标表：config 覆盖实例配置
     *
     * @param config 附加参数
     * @return 表名，缺失时 null
     */
    private String resolveTable(Map<String, Object> config) {
        Object fromConfig = config == null ? null : config.get("table");
        if (fromConfig instanceof String text && !text.isBlank()) {
            return text;
        }
        return table;
    }

    /**
     * 取本次写入的数据源：config 覆盖实例配置
     *
     * @param config 附加参数
     * @return 数据源，缺失时 null
     */
    private DataSource resolveDataSource(Map<String, Object> config) {
        Object fromConfig = config == null ? null : config.get("dataSource");
        if (fromConfig instanceof DataSource source) {
            return source;
        }
        return dataSource;
    }

    /**
     * 嵌套结构没有对应的列类型可放，按 JSON 文本落列
     *
     * @param value 原始值
     * @return 绑定值
     */
    private static Object toColumnValue(Object value) {
        if (value instanceof Map || value instanceof Iterable || value instanceof Object[]) {
            return Json.toJson(value);
        }
        return value;
    }
}
