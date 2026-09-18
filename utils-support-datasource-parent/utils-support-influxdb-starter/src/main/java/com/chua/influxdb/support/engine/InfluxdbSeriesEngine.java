package com.chua.influxdb.support.engine;

import com.chua.common.support.lang.datasource.series.SeriesEngine;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 InfluxDB 的时序存储实现。
 *
 * <p>当前为内存简化实现，后续可对接真正的 InfluxDB 客户端。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("influxdb")
public class InfluxdbSeriesEngine implements SeriesEngine {

    /** 单次曲线返回点数上限 */
    private static final int MAX_SERIES_POINTS = 1440;

    /** 内存存储：key -> (timestamp, value) */
    private final Map<String, List<Map.Entry<Long, Double>>> store = new ConcurrentHashMap<>();

    /** 是否可用 */
    private final boolean available = true;

    @Override
    public String engine() {
        return "influxdb";
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public void writePoint(String target, Long monitorId, String metricName, double value, long timestamp) {
        String key = target + ":" + monitorId + ":" + metricName;
        store.computeIfAbsent(key, k -> new ArrayList<>())
                .add(Map.entry(timestamp, value));
    }

    @Override
    public List<List<Object>> series(String target, Long monitorId, int hours, String window) {
        long nowMs = System.currentTimeMillis();
        long startMs = nowMs - (long) Math.max(1, Math.min(hours, 24 * 7)) * 3600_000L;

        List<List<Object>> points = new ArrayList<>();
        // 简化：遍历所有匹配的 key
        for (Map.Entry<String, List<Map.Entry<Long, Double>>> storeEntry : store.entrySet()) {
            String key = storeEntry.getKey();
            if (!key.startsWith(target + ":" + monitorId + ":")) {
                continue;
            }
            for (Map.Entry<Long, Double> entry : storeEntry.getValue()) {
                if (entry.getKey() >= startMs && entry.getKey() <= nowMs) {
                    List<Object> pair = new ArrayList<>(2);
                    pair.add(entry.getKey());
                    pair.add(entry.getValue());
                    points.add(pair);
                }
            }
        }
        points.sort((a, b) -> Long.compare(((Number) a.getFirst()).longValue(), ((Number) b.getFirst()).longValue()));
        // 降采样
        if (points.size() > MAX_SERIES_POINTS) {
            int step = (int) Math.ceil((double) points.size() / MAX_SERIES_POINTS);
            List<List<Object>> sampled = new ArrayList<>();
            for (int i = 0; i < points.size(); i += step) {
                sampled.add(points.get(i));
            }
            points = sampled;
        }
        return points;
    }
}
