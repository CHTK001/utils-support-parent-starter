package com.chua.redis.support.engine;

import com.chua.common.support.lang.datasource.dialect.SqlName;
import com.chua.common.support.lang.datasource.series.SeriesEngine;
import com.chua.common.support.spi.annotations.Spi;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * 基于 Redis（Lettuce）的时序存储实现。
 *
 * <p>每个指标一条 ZSET（member=值:时间戳, score=时间戳），
 * 以 {@code monitor:metric:<target>:<monitorId>:<metricName>} 为键；
 * 同时以 {@code monitor:metrics:<target>:<monitorId>} 的 SET 维护指标名索引，
 * 查询曲线时按索引遍历全部已写入指标，而非硬编码清单。</p>
 *
 * <p>Lettuce 连接线程安全，本引擎按 {@link RedisClient} 惰性共享一条长连接，
 * 避免每次读写都新建/销毁连接。调用方不再使用时应调用 {@link #close()}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("redis")
public class RedisSeriesEngine implements SeriesEngine, AutoCloseable {

    /**
     * ZSET 键前缀
    */
    private static final String KEY_PREFIX = "monitor:metric:";

    /**
     * 指标名索引 SET 键前缀
    */
    private static final String METRICS_INDEX_PREFIX = "monitor:metrics:";

    /**
     * 单条 ZSET 上限（防止无限增长）
    */
    private static final int MAX_POINTS_PER_METRIC = 100_000;

    /**
     * 单次曲线返回点数上限
    */
    private static final int MAX_SERIES_POINTS = 1440;

    /**
     * Lettuce Redis 客户端
    */
    private final RedisClient redisClient;

    /**
     * 是否持有（并负责关闭）客户端
    */
    private final boolean ownsClient;

    /**
     * 是否可用
    */
    private final boolean available;

    /**
     * 是否已关闭：关闭后 isAvailable 必须为 false，避免向已释放的客户端继续提交读写
    */
    private volatile boolean closed;

    /**
     * 惰性共享长连接（Lettuce 连接线程安全）
    */
    private volatile StatefulRedisConnection<String, String> sharedConnection;

    /**
     * SPI 构造：按 Properties 创建自有客户端。
     *
     * @param properties 配置：url（缺省 redis://127.0.0.1:6379）
     */
    public RedisSeriesEngine(Properties properties) {
        this(RedisClient.create(properties == null ? "redis://127.0.0.1:6379"
                : properties.getProperty("url", "redis://127.0.0.1:6379")), true);
    }

    /**
     * 构造 Redis 时序引擎（客户端生命周期由调用方管理）。
     *
     * @param redisClient Lettuce Redis 客户端
     */
    public RedisSeriesEngine(RedisClient redisClient) {
        this(redisClient, false);
    }

    /**
     * 构造 Redis 时序引擎。
     *
     * @param redisClient Lettuce Redis 客户端
     * @param ownsClient  是否由本引擎负责关闭客户端
     */
    private RedisSeriesEngine(RedisClient redisClient, boolean ownsClient) {
        this.redisClient = redisClient;
        this.ownsClient = ownsClient;
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
        return available && redisClient != null && !closed;
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
            throw new IllegalStateException("RedisSeriesEngine 不可用：客户端为空或已关闭");
        }
        try {
            RedisCommands<String, String> sync = commands();
            String key = key(target, monitorId, metricName);
            // member = 值:时间戳（避免同 ts 覆盖），score = 时间戳
            String member = value + ":" + timestamp;
            sync.zadd(key, timestamp, member);
            sync.sadd(metricsIndexKey(target, monitorId), metricName);
            // 裁剪：删除最旧的超限记录
            Long size = sync.zcard(key);
            if (size != null && size > MAX_POINTS_PER_METRIC) {
                long remove = size - MAX_POINTS_PER_METRIC;
                sync.zremrangebyrank(key, 0, remove - 1);
            }
        } catch (Exception e) {
            log.warn("[SeriesEngine] Redis 写入失败 metric={}: {}", metricName, e.getMessage());
            throw new IllegalStateException("Redis 时序写入失败 metric=" + metricName + ": " + e.getMessage(), e);
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

        List<Object[]> points = new ArrayList<>();
        try {
            RedisCommands<String, String> sync = commands();
            Set<String> metrics = sync.smembers(metricsIndexKey(target, monitorId));
            if (metrics == null || metrics.isEmpty()) {
                return List.of();
            }
            for (String metric : metrics) {
                String key = key(target, monitorId, metric);
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
                        points.add(new Object[]{ts, value});
                    } catch (NumberFormatException e) {
                        log.debug("[SeriesEngine] Redis 指标值解析失败: {}", member);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[SeriesEngine] Redis 查询失败 target={} monitor={}: {}", target, monitorId, e.getMessage());
            throw new IllegalStateException("Redis 时序曲线查询失败 target=" + target + ": " + e.getMessage(), e);
        }
        return SeriesEngine.downsample(points, SeriesEngine.windowMillis(window), MAX_SERIES_POINTS);
    }

    /**
     * 释放共享连接；若客户端由本引擎创建则一并关闭。
     */
    @Override
    public void close() {
        closed = true;
        StatefulRedisConnection<String, String> conn = sharedConnection;
        sharedConnection = null;
        if (conn != null) {
            try {
                conn.close();
            } catch (Exception e) {
                log.warn("[SeriesEngine] Redis 共享连接关闭失败: {}", e.getMessage());
            }
        }
        if (ownsClient && redisClient != null) {
            redisClient.shutdown();
        }
    }

    /**
     * 获取（惰性创建）共享连接的同步命令接口。
     *
     * @return 同步命令
     */
    private RedisCommands<String, String> commands() {
        StatefulRedisConnection<String, String> conn = sharedConnection;
        if (conn != null && conn.isOpen()) {
            return conn.sync();
        }
        synchronized (this) {
            if (sharedConnection == null || !sharedConnection.isOpen()) {
                sharedConnection = redisClient.connect();
            }
            return sharedConnection.sync();
        }
    }

    /**
     * 构造 ZSET 键。
     * @param target 目标
     * @param monitorId monitorID
     * @param metric 指标名
     * @return 结果字符串
     */
    private String key(String target, Long monitorId, String metric) {
        return KEY_PREFIX + target + ":" + monitorId + ":" + metric;
    }

    /**
     * 构造指标名索引 SET 键。
     * @param target 目标
     * @param monitorId monitorID
     * @return 结果字符串
     */
    private String metricsIndexKey(String target, Long monitorId) {
        return METRICS_INDEX_PREFIX + target + ":" + monitorId;
    }
}
