package com.chua.common.support.lang.datasource.series;

import java.util.List;
import java.util.Map;

/**
 * 时序（Series）引擎统一契约。
 *
 * <p>该接口定义了与具体后端无关的时序数据操作集合，
 * 涵盖 MySQL（DataSource）、Redis（ZSET）、InfluxDB 等后端的通用能力：
 * 单指标点写入、批量指标点写入、按时间窗口查询降采样曲线。</p>
 *
 * <p><b>写入</b>：按 {@code (target, monitorId, metricName, value, timestamp)} 五元组写入，
 * 后端可自行选择存储结构（MySQL 明细表、Redis ZSET、InfluxDB measurement）。</p>
 *
 * <p><b>查询</b>：按 {@code (target, monitorId, hours, window)} 查询降采样曲线，
 * 返回 {@code [[ts, value], ...]} 升序数组，前端直接用于折线图。</p>
 *
 * <h2>SPI 契约</h2>
 * <pre>{@code
 * // 注册文件：META-INF/extensions/com.chua.common.support.lang.datasource.series.SeriesEngine
 * // 内容格式：别名=实现类全限定名，例如
 * // datasource=com.chua.common.support.lang.datasource.series.impl.DataSourceSeriesEngine
 * // redis=com.chua.redis.support.engine.RedisSeriesEngine
 *
 * Properties props = new Properties();
 * props.setProperty("engine", "datasource");
 * SeriesEngine engine = ServiceProvider.of(SeriesEngine.class).getNewExtension("datasource", props);
 * engine.writePoint("ms_monitor_metric_cpu", 1L, "cpu_usage", 25.5, System.currentTimeMillis());
 * List<List<Object>> curve = engine.series("ms_monitor_metric_cpu", 1L, 1, "5m");
 * }</pre>
 *
 * <h2>Spring 注入</h2>
 * <pre>{@code
 * @Autowired
 * private SeriesEngine seriesEngine;
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface SeriesEngine {

    /**
    * 引擎标识（与配置 {@code monitor.timeseries.engine} 对应）
    *
    * @return 引擎名，如 datasource / redis / influxdb
    */
    String engine();

    /**
    * 是否可用（依赖装配成功且服务可达）
    *
    * @return true 表示可写入/可查询
    */
    boolean isAvailable();

    /**
    * 写入单个指标点。
    *
    * @param target    目标表/测量名，如 ms_monitor_metric_cpu
    * @param monitorId 监控目标标识
    * @param metricName 指标名，如 cpu_usage
    * @param value     指标值
    * @param timestamp 时间戳（毫秒）
    */
    void writePoint(String target, Long monitorId, String metricName, double value, long timestamp);

    /**
    * 批量写入指标点。
    *
    * @param target    目标表/测量名
    * @param monitorId 监控目标标识
    * @param metrics   指标名 → 指标值
    * @param timestamp 时间戳（毫秒）
    */
    default void writeBatch(String target, Long monitorId, Map<String, Double> metrics, long timestamp) {
        if (metrics == null || metrics.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Double> entry : metrics.entrySet()) {
            writePoint(target, monitorId, entry.getKey(), entry.getValue(), timestamp);
        }
    }

    /**
    * 均值曲线查询（前端折线图）。
    *
    * @param target    目标表/测量名，如 ms_monitor_metric_cpu
    * @param monitorId  监控目标标识
    * @param hours     回溯小时数
    * @param window    聚合窗口，如 1m/5m/1h
    * @return [[ts, value], ...] 升序
    */
    List<List<Object>> series(String target, Long monitorId, int hours, String window);
}
