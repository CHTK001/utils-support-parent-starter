package com.chua.common.support.lang.datasource.series.impl;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.series.SeriesEngine;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 JDBC DataSource 的时序存储实现。
 *
 * <p>通过 {@link Engine} 获取 {@link SqlExecutor}，将时序点写入关系型数据库明细表，
 * 查询时按时间窗口聚合均值降采样。</p>
 *
 * <p><b>列名约定（前缀列）</b>：明细表采用「表名前缀 + 字段名」列规约，
 * 指标列 = {@code {target}_monitor_id}，时间列 = {@code {target}_collect_time}，
 * 数值列 = {@code {target}_{metricName}}，其中 {@code metricName} 为去掉表名前缀后的
 * 物理列后缀（如 CPU 表传 {@code cpu_usage}、内存/磁盘表传 {@code usage_percent}）。
 * 各已知表的取值列映射见 {@link #TARGET_VALUE_COLUMN}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("datasource")
public class DataSourceSeriesEngine implements SeriesEngine {

    /**
     * 默认数据库引擎名
    */
    private static final String DEFAULT_ENGINE = "datasource";

    /**
     * 单次曲线返回点数上限
    */
    private static final int MAX_SERIES_POINTS = 1440;

    /**
     * 合法标识符（表名/列名后缀），防止 SQL 注入
    */
    private static final String IDENTIFIER_REGEX = "[a-zA-Z0-9_]+";

    /**
     * 已知明细表的数值列后缀（不含表名前缀），未登记的表无法推断数值列
    */
    private static final Map<String, String> TARGET_VALUE_COLUMN = Map.of(
            "ms_monitor_metric_cpu", "cpu_usage",
            "ms_monitor_metric_memory", "usage_percent",
            "ms_monitor_metric_disk", "usage_percent");

    /**
     * SQL 执行器
    */
    private final SqlExecutor executor;

    /**
     * 是否可用
    */
    private final boolean available;

    /**
     * 构造 JDBC 时序引擎。
     *
     * @param engine 数据源引擎（已注册 DataSource）
     */
    public DataSourceSeriesEngine(Engine engine) {
        this.executor = engine != null ? engine.getExecutor() : null;
        this.available = this.executor != null;
        if (!available) {
            log.warn("[SeriesEngine] DataSourceSeriesEngine 不可用：SqlExecutor 为空");
        }
    }

    @Override
    public String engine() {
        return DEFAULT_ENGINE;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public void writePoint(String target, Long monitorId, String metricName, double value, long timestamp) {
        if (!isAvailable() || target == null || monitorId == null || metricName == null) {
            return;
        }
        if (!target.matches(IDENTIFIER_REGEX) || !metricName.matches(IDENTIFIER_REGEX)) {
            log.error("[SeriesEngine] 非法标识符 target={} metric={}", target, metricName);
            return;
        }
        String sql = String.format("INSERT INTO %s (%s, %s, %s) VALUES (?, ?, ?)",
                target, monitorColumn(target), valueColumn(target, metricName), timeColumn(target));
        try {
            executor.execute(sql, monitorId, value, new Timestamp(timestamp));
        } catch (Exception e) {
            log.error("[SeriesEngine] JDBC 写入失败 target={} metric={}", target, metricName, e);
        }
    }

    @Override
    public List<List<Object>> series(String target, Long monitorId, int hours, String window) {
        if (!isAvailable() || target == null || monitorId == null) {
            return List.of();
        }
        if (!target.matches(IDENTIFIER_REGEX)) {
            throw new IllegalArgumentException("非法 target: " + target);
        }
        String suffix = TARGET_VALUE_COLUMN.get(target);
        if (suffix == null) {
            log.error("[SeriesEngine] 未知明细表 {}，无法推断数值列（SeriesEngine.series 缺少 metricName 维度）", target);
            return List.of();
        }
        String timeCol = timeColumn(target);
        String valueCol = target + "_" + suffix;
        long nowMs = System.currentTimeMillis();
        long startMs = nowMs - (long) Math.max(1, Math.min(hours, 24 * 7)) * 3600_000L;
        String querySql = String.format("SELECT %s, %s FROM %s WHERE %s = ? AND %s >= ? AND %s <= ? ORDER BY %s ASC",
                timeCol, valueCol, target, monitorColumn(target), timeCol, timeCol, timeCol);

        List<Object[]> points = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = executor.query(querySql, monitorId, new Timestamp(startMs), new Timestamp(nowMs));
            for (Map<String, Object> row : rows) {
                Long ts = toEpochMillis(pick(row, timeCol));
                Object val = pick(row, valueCol);
                if (ts != null && val instanceof Number) {
                    points.add(new Object[]{ts, ((Number) val).doubleValue()});
                }
            }
        } catch (Exception e) {
            log.error("[SeriesEngine] JDBC 查询失败 target={} monitor={}", target, monitorId, e);
            return List.of();
        }
        return downsample(points, parseWindowMillis(window));
    }

    /**
     * 按窗口对升序点做均值聚合，并封顶返回点数。
     *
     * @param points   原始点列表 [ts, value]
     * @param windowMs 聚合窗口（毫秒）
     * @return [[ts, avg], ...] 升序
     */
    private List<List<Object>> downsample(List<Object[]> points, long windowMs) {
        List<List<Object>> result = new ArrayList<>();
        if (points.isEmpty()) {
            return result;
        }
        Map<Long, double[]> buckets = new LinkedHashMap<>();
        for (Object[] point : points) {
            long bucket = ((Number) point[0]).longValue() / windowMs;
            double[] acc = buckets.computeIfAbsent(bucket, k -> new double[2]);
            acc[0] += (Double) point[1];
            acc[1] += 1;
        }
        for (Map.Entry<Long, double[]> entry : buckets.entrySet()) {
            double[] acc = entry.getValue();
            List<Object> pair = new ArrayList<>(2);
            pair.add(entry.getKey() * windowMs);
            pair.add(acc[1] > 0 ? acc[0] / acc[1] : 0D);
            result.add(pair);
        }
        if (result.size() > MAX_SERIES_POINTS) {
            int step = (int) Math.ceil((double) result.size() / MAX_SERIES_POINTS);
            List<List<Object>> sampled = new ArrayList<>();
            for (int i = 0; i < result.size(); i += step) {
                sampled.add(result.get(i));
            }
            result = sampled;
        }
        return result;
    }

    /**
     * 从结果行中按列名取值（忽略驱动返回键的大小写差异）。
     *
     * @param row 结果行
     * @param column 目标列名
     * @return 列值，取不到返回 空
     */
    private Object pick(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value != null) {
            return value;
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(column)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 将 JDBC 时间对象转换为毫秒时间戳。
     *
     * @param value 时间列值
     * @return 毫秒时间戳，无法识别返回 空
     */
    private Long toEpochMillis(Object value) {
        if (value instanceof Timestamp ts) {
            return ts.getTime();
        }
        if (value instanceof java.util.Date date) {
            return date.getTime();
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (value instanceof Instant instant) {
            return instant.toEpochMilli();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    /**
     * 监控主键列名。
     *
     * @param target 表名
     * @return 前缀列名
     */
    private String monitorColumn(String target) {
        return target + "_monitor_id";
    }

    /**
     * 采集时间列名。
     *
     * @param target 表名
     * @return 前缀列名
     */
    private String timeColumn(String target) {
        return target + "_collect_time";
    }

    /**
     * 指标数值列名。
     *
     * @param target     表名
     * @param metricName 列后缀
     * @return 前缀列名
     */
    private String valueColumn(String target, String metricName) {
        return target + "_" + metricName;
    }

    /**
     * 解析时间窗口为毫秒数。
     *
     * @param window 窗口字符串，如 1m/5m/1h
     * @return 窗口毫秒数，非法输入回退 5 分钟
     */
    private long parseWindowMillis(String window) {
        long minutes = 5;
        if (window != null && !window.isEmpty()) {
            try {
                String lower = window.toLowerCase();
                if (lower.endsWith("m")) {
                    minutes = Long.parseLong(lower.substring(0, lower.length() - 1));
                } else if (lower.endsWith("h")) {
                    minutes = Long.parseLong(lower.substring(0, lower.length() - 1)) * 60;
                } else if (lower.endsWith("s")) {
                    minutes = Math.max(1, Long.parseLong(lower.substring(0, lower.length() - 1)) / 60);
                }
            } catch (NumberFormatException e) {
                log.warn("[SeriesEngine] 非法窗口 {}，回退 5m", window);
            }
        }
        return Math.max(1, minutes) * 60_000L;
    }
}
