package com.chua.common.support.lang.datasource.series.impl;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.series.SeriesEngine;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 JDBC DataSource 的时序存储实现。
 *
 * <p>通过 {@link Engine} 获取 {@link SqlExecutor}，将时序点写入关系型数据库明细表，
 * 查询时按时间窗口 GROUP BY 降采样。</p>
 *
 * <p>表结构约定：</p>
 * <ul>
 *   <li>{@code ms_monitor_metric_cpu}：id, monitor_id, cpu_usage, collect_time</li>
 *   <li>{@code ms_monitor_metric_memory}：id, monitor_id, mem_usage, collect_time</li>
 *   <li>{@code ms_monitor_metric_disk}：id, monitor_id, disk_usage, collect_time</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("datasource")
public class DataSourceSeriesEngine implements SeriesEngine {

    /** 默认数据库引擎名 */
    private static final String DEFAULT_ENGINE = "datasource";

    /** 单次曲线返回点数上限 */
    private static final int MAX_SERIES_POINTS = 1440;

    /** SQL 执行器 */
    private final SqlExecutor executor;

    /** 是否可用 */
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
        if (!isAvailable() || target == null || monitorId == null) {
            return;
        }
        // target 必须是合法表名（防止 SQL 注入）
        if (!target.matches("[a-zA-Z0-9_]+")) {
            log.warn("[SeriesEngine] 非法表名: {}", target);
            return;
        }
        // metricName 必须是合法列名
        if (!metricName.matches("[a-zA-Z0-9_]+")) {
            log.warn("[SeriesEngine] 非法列名: {}", metricName);
            return;
        }
        String sql = String.format(
                "INSERT INTO %s (monitor_id, %s, collect_time) VALUES (?, ?, ?)",
                target, metricName);
        try {
            executor.execute(sql, monitorId, value, new Timestamp(timestamp));
        } catch (Exception e) {
            log.warn("[SeriesEngine] JDBC 写入失败 target={} metric={}: {}", target, metricName, e.getMessage());
        }
    }

    @Override
    public List<List<Object>> series(String target, Long monitorId, int hours, String window) {
        if (!isAvailable() || target == null || monitorId == null) {
            return List.of();
        }
        if (!target.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("非法 target: " + target);
        }
        // 窗口转 SQL 时间间隔（简化：按分钟 GROUP BY）
        int windowMinutes = parseWindow(window);
        long nowMs = System.currentTimeMillis();
        long startMs = nowMs - (long) Math.max(1, Math.min(hours, 24 * 7)) * 3600_000L;

        // 简化查询：直接取范围内所有点，应用层降采样
        String sql = String.format(
                "SELECT collect_time, %s FROM %s WHERE monitor_id = ? AND collect_time >= ? AND collect_time <= ? ORDER BY collect_time ASC",
                "*", target);
        // 注意：这里需要知道指标列名，简化为查询所有列后取第一列数值
        // 实际使用时应该指定 metricName，这里暂时返回 collect_time + 第一个数值列
        List<List<Object>> result = new ArrayList<>();
        try {
            // 先查列名（简化：直接查 collect_time 和已知指标列）
            String querySql = String.format(
                    "SELECT collect_time, %s FROM %s WHERE monitor_id = ? AND collect_time >= ? AND collect_time <= ? ORDER BY collect_time ASC",
                    resolveMetricColumn(target), target);
            List<Map<String, Object>> rows = executor.query(querySql, monitorId, new Timestamp(startMs), new Timestamp(nowMs));
            for (Map<String, Object> row : rows) {
                Timestamp ts = (Timestamp) row.get("collect_time");
                Object val = row.get(resolveMetricColumn(target));
                if (ts != null && val instanceof Number) {
                    List<Object> pair = new ArrayList<>(2);
                    pair.add(ts.getTime());
                    pair.add(((Number) val).doubleValue());
                    result.add(pair);
                }
            }
        } catch (Exception e) {
            log.warn("[SeriesEngine] JDBC 查询失败 target={} monitor={}: {}", target, monitorId, e.getMessage());
            return List.of();
        }
        // 应用层降采样
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
     * 根据表名推断指标列名。
     *
     * @param target 表名
     * @return 指标列名
     */
    private String resolveMetricColumn(String target) {
        if (target.contains("cpu")) {
            return "cpu_usage";
        } else if (target.contains("memory") || target.contains("mem")) {
            return "mem_usage";
        } else if (target.contains("disk")) {
            return "disk_usage";
        }
        return "value";
    }

    /**
     * 解析时间窗口为分钟数。
     *
     * @param window 窗口字符串，如 1m/5m/1h
     * @return 分钟数
     */
    private int parseWindow(String window) {
        if (window == null || window.isEmpty()) {
            return 5;
        }
        try {
            String lower = window.toLowerCase();
            if (lower.endsWith("m")) {
                return Integer.parseInt(lower.substring(0, lower.length() - 1));
            } else if (lower.endsWith("h")) {
                return Integer.parseInt(lower.substring(0, lower.length() - 1)) * 60;
            } else if (lower.endsWith("s")) {
                return Math.max(1, Integer.parseInt(lower.substring(0, lower.length() - 1)) / 60);
            }
        } catch (NumberFormatException ignored) {
        }
        return 5;
    }
}
