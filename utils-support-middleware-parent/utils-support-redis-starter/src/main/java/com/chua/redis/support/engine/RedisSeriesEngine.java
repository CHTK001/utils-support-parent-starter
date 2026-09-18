package com.chua.redis.support.engine;

import com.chua.common.support.lang.datasource.series.SeriesEngine;
import com.chua.common.support.spi.annotations.Spi;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Redis（Lettuce）的时序存储实现。
 *
 * <p>每个指标一条 ZSET（member=值:时间戳, score=时间戳），
 * 以 {@code monitor:metric:<target>:<monitorId>:<metricName>} 为键。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("redis")
public class RedisSeriesEngine implements SeriesEngine {

    /** ZSET 键前缀 */
    private static final String KEY_PREFIX = "monitor:metric:";

    /** 单条 ZSET 上限（防止无限增长） */
    private static final int MAX_POINTS_PER_METRIC = 100_000;

    /** 单次曲线返回点数上限 */
    private static final int MAX_SERIES_POINTS = 1440;

    /** Lettuce Redis 客户端 */
    private final RedisClient redisClient;

    /** 是否可用 */
    private final boolean available;

    /**
    * 构造 Redis 时序引擎。
    *
    * @param redisClient Lettuce Redis 客户端
    */
    public RedisSeriesEngine(RedisClient redisClient) {
        this.redisClient = redisClient;
        this.available = redisClient != null;
        if (!available) {
            log.warn("[SeriesEngine] RedisSeriesEngine 不可用：RedisClient 为空");
        }
    }

    @Override
    public String engine() {
        return "redis";
    }

    @Override
    public boolean isAvailable() {
        return available && redisClient != null;
    }

    @Override
    public void writePoint(String target, Long monitorId, String metricName, double value, long timestamp) {
        if (!isAvailable()) {
            return;
        }
        try (StatefulRedisConnection<String, String> conn = redisClient.connect()) {
            RedisCommands<String, String> sync = conn.sync();
            String key = key(target, monitorId, metricName);
            // member = 值:时间戳（避免同 ts 覆盖），score = 时间戳
            String member = value + ":" + timestamp;
            sync.zadd(key, timestamp, member);
            // 裁剪：删除最旧的超限记录
            Long size = sync.zcard(key);
            if (size != null && size > MAX_POINTS_PER_METRIC) {
                long remove = size - MAX_POINTS_PER_METRIC;
                sync.zremrangebyrank(key, 0, (int) (remove - 1));
            }
        } catch (Exception e) {
            log.warn("[SeriesEngine] Redis 写入失败 metric={}: {}", metricName, e.getMessage());
        }
    }

    @Override
    public List<List<Object>> series(String target, Long monitorId, int hours, String window) {
        if (!isAvailable()) {
            return List.of();
        }
        if (target == null || !target.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("非法 target: " + target);
        }
        long nowMs = System.currentTimeMillis();
        long startMs = nowMs - (long) Math.max(1, Math.min(hours, 24 * 7)) * 3600_000L;

        List<List<Object>> points = new ArrayList<>(Math.min(MAX_SERIES_POINTS, 128));
        try (StatefulRedisConnection<String, String> conn = redisClient.connect()) {
            RedisCommands<String, String> sync = conn.sync();
            // 简化：遍历已知 metric
            String[] metrics = {"cpu_usage", "mem_usage", "disk_usage"};
            for (String metric : metrics) {
                String key = key(target, monitorId, metric);
                // ZRANGEBYSCORE key min max
                List<String> members = sync.zrangebyscore(key, startMs, nowMs);
                if (members == null) {
                    continue;
                }
                for (String member : members) {
                    int sep = member.lastIndexOf(':');
                    if (sep <= 0) {
                        continue;
                    }
                    try {
                        double value = Double.parseDouble(member.substring(0, sep));
                        long ts = Long.parseLong(member.substring(sep + 1));
                        List<Object> pair = new ArrayList<>(2);
                        pair.add(ts);
                        pair.add(value);
                        points.add(pair);
                    } catch (NumberFormatException e) {
                        log.debug("[SeriesEngine] Redis 指标值解析失败: {}", member);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[SeriesEngine] Redis 查询失败 target={} monitor={}: {}", target, monitorId, e.getMessage());
            return List.of();
        }
        points.sort((a, b) -> Long.compare(((Number) a.getFirst()).longValue(), ((Number) b.getFirst()).longValue()));
        // 等距抽样降采样
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

    /**
    * 构造 ZSET 键。
    * @param target 目标，不允许为 null
    * @param monitorId monitorID，不允许为 null
    * @param metric 方法入参 metric
    * @return 结果字符串
    */
    private String key(String target, Long monitorId, String metric) {
        return KEY_PREFIX + target + ":" + monitorId + ":" + metric;
    }
}
