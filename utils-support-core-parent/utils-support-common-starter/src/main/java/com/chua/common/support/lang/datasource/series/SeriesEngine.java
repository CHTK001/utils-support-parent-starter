package com.chua.common.support.lang.datasource.series;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
     * <p>契约：任何失败都必须抛出，实现不得静默丢弃数据点。调用方若要 best-effort
     * 语义（如采集主链路旁路双写），自行捕获并记录。</p>
     *
     * @param target    目标表/测量名，如 ms_monitor_metric_cpu
     * @param monitorId 监控目标标识
     * @param metricName 指标名，如 cpu_usage
     * @param value     指标值
     * @param timestamp 时间戳（毫秒）
     * @throws IllegalArgumentException 参数为空或标识符含非法字符
     * @throws IllegalStateException    引擎不可用或已关闭
     */
    void writePoint(String target, Long monitorId, String metricName, double value, long timestamp);

    /**
     * 批量写入指标点。
     * <p>逐个委托 {@link #writePoint}，失败语义与其一致。</p>
     *
     * @param target    目标表/测量名
     * @param monitorId 监控目标标识
     * @param metrics   指标名 → 指标值
     * @param timestamp 时间戳（毫秒）
     * @throws IllegalArgumentException 参数为空或标识符含非法字符
     * @throws IllegalStateException    引擎不可用或已关闭
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
     * <p>契约：查询失败必须抛出，不得降级成空列表——空曲线与后端故障对使用者是
     * 两件事。仅在引擎明确不可用（{@link #isAvailable()} 为 false）时返回空列表。</p>
     *
     * @param target    目标表/测量名，如 ms_monitor_metric_cpu
     * @param monitorId  监控目标标识
     * @param hours     回溯小时数
     * @param window    聚合窗口，如 1m/5m/1h
     * @return [[ts, value], ...] 升序
     * @throws IllegalArgumentException target 非法或无法解析
     * @throws IllegalStateException    后端查询失败
     */
    List<List<Object>> series(String target, Long monitorId, int hours, String window);

    /**
     * 解析聚合窗口为毫秒数，供各实现类的降采样统一使用。
     *
     * @param window 窗口字符串，如 30s/1m/5m/1h/2d；空或非法回退 5 分钟
     * @return 窗口毫秒数（至少 1 毫秒）
     */
    static long windowMillis(String window) {
        long millis = 300_000L;
        if (window != null && !window.isEmpty()) {
            String lower = window.toLowerCase().trim();
            try {
                char unit = lower.charAt(lower.length() - 1);
                long n = Long.parseLong(lower.substring(0, lower.length() - 1));
                millis = switch (unit) {
                    case 's' -> n * 1000L;
                    case 'm' -> n * 60_000L;
                    case 'h' -> n * 3600_000L;
                    case 'd' -> n * 86400_000L;
                    default -> throw new NumberFormatException("未知窗口单位: " + unit);
                };
            } catch (NumberFormatException e) {
                // 纯数字视为毫秒
                try {
                    millis = Long.parseLong(lower);
                } catch (NumberFormatException ignored) {
                    millis = 300_000L;
                }
            }
        }
        return Math.max(1L, millis);
    }

    /**
     * 按窗口均值降采样并封顶点数。
     *
     * @param points   原始点列表，元素为 {@code [timestampMillis(Number), value(Number)]}，无需预排序
     * @param windowMs 聚合窗口毫秒数
     * @param maxPoints 返回点数上限，超出时等距抽样
     * @return [[bucketStartTs, avg], ...] 升序
     */
    static List<List<Object>> downsample(List<Object[]> points, long windowMs, int maxPoints) {
        List<List<Object>> result = new ArrayList<>();
        if (points == null || points.isEmpty()) {
            return result;
        }
        Map<Long, double[]> buckets = new LinkedHashMap<>();
        for (Object[] point : points) {
            long bucket = ((Number) point[0]).longValue() / windowMs;
            double[] acc = buckets.computeIfAbsent(bucket, k -> new double[2]);
            acc[0] += ((Number) point[1]).doubleValue();
            acc[1] += 1;
        }
        List<Long> keys = new ArrayList<>(buckets.keySet());
        keys.sort(Long::compare);
        for (Long bucket : keys) {
            double[] acc = buckets.get(bucket);
            List<Object> pair = new ArrayList<>(2);
            pair.add(bucket * windowMs);
            pair.add(acc[1] > 0 ? acc[0] / acc[1] : 0D);
            result.add(pair);
        }
        if (maxPoints > 0 && result.size() > maxPoints) {
            int step = (int) Math.ceil((double) result.size() / maxPoints);
            List<List<Object>> sampled = new ArrayList<>();
            for (int i = 0; i < result.size(); i += step) {
                sampled.add(result.get(i));
            }
            return sampled;
        }
        return result;
    }
}
