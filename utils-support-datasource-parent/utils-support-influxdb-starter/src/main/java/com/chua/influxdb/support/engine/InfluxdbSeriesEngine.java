package com.chua.influxdb.support.engine;

import com.chua.common.support.lang.datasource.dialect.SqlName;
import com.chua.common.support.lang.datasource.series.SeriesEngine;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.Point;
import org.influxdb.dto.Query;
import org.influxdb.dto.QueryResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * 基于 InfluxDB（influxdb-java 官方客户端）的时序存储实现。
 *
 * <p>measurement = target，tag = {@code monitor_id}，每个指标名一个 field，
 * 时间戳为毫秒精度。曲线查询使用 {@code SELECT *} 拉取原始点后在 Java 侧
 * 按统一窗口均值降采样（{@link SeriesEngine#downsample}），
 * 与 DataSource/Redis 实现保持同一聚合语义。</p>
 *
 * <h2>SPI 配置</h2>
 * <pre>{@code
 * Properties props = new Properties();
 * props.setProperty("url", "http://127.0.0.1:8086");
 * props.setProperty("database", "monitor");        // 必填
 * props.setProperty("username", "admin");          // InfluxDB 1.x 可选；2.x 填 token
 * props.setProperty("password", "secret");         // InfluxDB 1.x 可选；2.x 留空
 * SeriesEngine engine = ServiceProvider.of(SeriesEngine.class).getNewExtension("influxdb", props);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("influxdb")
public class InfluxdbSeriesEngine implements SeriesEngine, AutoCloseable {

    /**
     * 单次曲线返回点数上限
    */
    private static final int MAX_SERIES_POINTS = 1440;

    /**
     * 监控目标 tag 名
    */
    private static final String TAG_MONITOR_ID = "monitor_id";

    /**
     * InfluxDB 客户端，可为空（配置缺失时不可用）
    */
    private final InfluxDB influxDB;

    /**
     * 数据库名
    */
    private final String database;

    /**
     * 是否可用
    */
    private final boolean configured;

    /**
     * 是否已关闭：关闭后 isAvailable 必须为 false，避免向已释放的客户端继续提交读写
     */
    private volatile boolean closed;

    /**
     * 通过 SPI 配置构造引擎。
     *
     * @param properties 配置：url（必填）、database（必填）、username/password（1.x；2.x 可将 token 作为 username）
     */
    public InfluxdbSeriesEngine(Properties properties) {
        this(properties == null ? null : properties.getProperty("url"),
                properties == null ? null : properties.getProperty("database"),
                properties == null ? null : properties.getProperty("username"),
                properties == null ? null : properties.getProperty("password"));
    }

    /**
     * 通过完整参数构造引擎。
     *
     * @param url      InfluxDB 服务地址
     * @param database 数据库名
     * @param username 用户名（可为空；InfluxDB 2.x 传 token）
     * @param password 密码（可为空；InfluxDB 2.x 传空串）
     */
    public InfluxdbSeriesEngine(String url, String database, String username, String password) {
        if (url == null || url.isEmpty() || database == null || database.isEmpty()) {
            this.influxDB = null;
            this.database = database;
            this.configured = false;
            log.warn("[SeriesEngine] InfluxdbSeriesEngine 不可用：url/database 未配置");
            return;
        }
        InfluxDB client;
        if (username != null && !username.isEmpty()) {
            client = InfluxDBFactory.connect(url, username, password);
        } else {
            client = InfluxDBFactory.connect(url);
        }
        client.setDatabase(database);
        this.influxDB = client;
        this.database = database;
        this.configured = true;
    }

    /**
     * 直接注入已构造的客户端（测试或外部生命周期管理）。
     *
     * @param influxDB 客户端，为空则引擎不可用
     */
    public InfluxdbSeriesEngine(InfluxDB influxDB) {
        this(influxDB, "monitor");
    }

    /**
     * 直接注入已构造的客户端与数据库名。
     *
     * @param influxDB 客户端，为空则引擎不可用
     * @param database 数据库名
     */
    public InfluxdbSeriesEngine(InfluxDB influxDB, String database) {
        this.influxDB = influxDB;
        this.database = database;
        this.configured = influxDB != null && database != null && !database.isEmpty();
        if (!configured) {
            log.warn("[SeriesEngine] InfluxdbSeriesEngine 不可用：客户端或数据库为空");
        }
    }

    @Override
    public String engine() {
        return "influxdb";
    }

    @Override
    public boolean isAvailable() {
        return configured && !closed;
    }

    @Override
    public void writePoint(String target, Long monitorId, String metricName, double value, long timestamp) {
        if (target == null || monitorId == null || metricName == null) {
            throw new IllegalArgumentException("时序写入缺少必填参数: target/monitorId/metricName");
        }
        if (!SqlName.isWord(target) || !SqlName.isWord(metricName)) {
            throw new IllegalArgumentException("非法标识符 target=" + target + " metric=" + metricName);
        }
        if (!isAvailable()) {
            throw new IllegalStateException("InfluxDB 引擎不可用（未配置或已关闭），拒绝写入 target="
                    + target + " metric=" + metricName);
        }
        Point point = Point.measurement(target)
                .tag(TAG_MONITOR_ID, String.valueOf(monitorId))
                .addField(metricName, value)
                .time(timestamp, TimeUnit.MILLISECONDS)
                .build();
        try {
            influxDB.write(point);
        } catch (RuntimeException e) {
            log.error("[SeriesEngine] InfluxDB 写入失败 target={} metric={}", target, metricName, e);
            throw new IllegalStateException("InfluxDB 时序写入失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<List<Object>> series(String target, Long monitorId, int hours, String window) {
        if (target == null || monitorId == null) {
            throw new IllegalArgumentException("时序查询缺少必填参数: target/monitorId");
        }
        if (!SqlName.isWord(target)) {
            throw new IllegalArgumentException("非法 target: " + target);
        }
        if (!isAvailable()) {
            return List.of();
        }
        long nowMs = System.currentTimeMillis();
        long startMs = nowMs - (long) Math.max(1, Math.min(hours, 24 * 7)) * 3600_000L;
        // identifier 已经白名单校验，monitorId 为 Long，无注入面
        String influxQl = "SELECT * FROM \"" + target + "\" WHERE \"" + TAG_MONITOR_ID + "\" = '" + monitorId
                + "' AND time >= " + startMs + "ms AND time <= " + nowMs + "ms";
        List<Object[]> points = new ArrayList<>();
        try {
            QueryResult result = influxDB.query(new Query(influxQl, database));
            if (result == null) {
                throw new IllegalStateException("InfluxDB 返回空结果 target=" + target);
            }
            if (result.hasError()) {
                throw new IllegalStateException("InfluxDB 查询错误 target=" + target
                        + ": " + result.getError());
            }
            if (result.getResults() == null) {
                return List.of();
            }
            for (QueryResult.Result resultItem : result.getResults()) {
                if (resultItem == null) {
                    continue;
                }
                if (resultItem.hasError()) {
                    throw new IllegalStateException("InfluxDB 查询错误 target=" + target
                            + ": " + resultItem.getError());
                }
                if (resultItem.getSeries() == null) {
                    continue;
                }
                for (QueryResult.Series series : resultItem.getSeries()) {
                    points.addAll(toPoints(series));
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[SeriesEngine] InfluxDB 查询失败 target={} monitor={}: {}", target, monitorId, e.getMessage());
            throw new IllegalStateException("InfluxDB 时序曲线查询失败 target=" + target + ": " + e.getMessage(), e);
        }
        return SeriesEngine.downsample(points, SeriesEngine.windowMillis(window), MAX_SERIES_POINTS);
    }

    /**
     * 将 InfluxDB 结果集展平为 [ts, value] 点列表（每行除时间列外的每个非空 field 一个点）。
     *
     * @param series 结果序列
     * @return 点列表
     */
    private List<Object[]> toPoints(QueryResult.Series series) {
        List<Object[]> points = new ArrayList<>();
        List<String> columns = series.getColumns();
        List<List<Object>> values = series.getValues();
        if (columns == null || values == null || columns.size() < 2) {
            return points;
        }
        for (List<Object> row : values) {
            Long ts = toEpochMillis(row.get(0));
            if (ts == null) {
                continue;
            }
            for (int i = 1; i < row.size() && i < columns.size(); i++) {
                Object cell = row.get(i);
                if (cell instanceof Number number) {
                    points.add(new Object[]{ts, number.doubleValue()});
                }
            }
        }
        return points;
    }

    /**
     * 时间列值转毫秒（字符串为纳秒精度 RFC3339，数字按毫秒处理）。
     *
     * @param value 时间列值
     * @return 毫秒时间戳，无法识别返回 空
     */
    private Long toEpochMillis(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String s && !s.isEmpty()) {
            try {
                return java.time.Instant.parse(s).toEpochMilli();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 关闭底层客户端。
     */
    @Override
    public void close() {
        closed = true;
        if (influxDB != null) {
            try {
                influxDB.close();
            } catch (Exception e) {
                log.warn("[SeriesEngine] InfluxDB 客户端关闭失败: {}", e.getMessage());
            }
        }
    }
}
