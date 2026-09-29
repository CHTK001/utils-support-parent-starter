package com.chua.redis.support.engine;

import com.chua.common.support.lang.datasource.dialect.SqlName;
import com.chua.common.support.lang.datasource.series.SeriesEngine;
import com.chua.common.support.spi.annotations.Spi;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanIterator;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
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
     * 跨目标清理时 SCAN 每批返回的键数
     */
    private static final int SCAN_BATCH = 1000;

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
    public List<String> metrics(String target, Long monitorId) {
        if (target == null) {
            throw new IllegalArgumentException("时序指标发现缺少必填参数: target");
        }
        if (!SqlName.isWord(target)) {
            throw new IllegalArgumentException("非法 target: " + target);
        }
        if (!isAvailable()) {
            return List.of();
        }
        try {
            Set<String> found = commands().smembers(metricsIndexKey(target, monitorId));
            if (found == null || found.isEmpty()) {
                return List.of();
            }
            List<String> sorted = new ArrayList<>(found);
            Collections.sort(sorted);
            return sorted;
        } catch (Exception e) {
            log.warn("[SeriesEngine] Redis 指标发现失败 target={} monitor={}: {}", target, monitorId, e.getMessage());
            throw new IllegalStateException("Redis 时序指标发现失败 target=" + target + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<List<Object>> series(String target, Long monitorId, String metricName, int hours, String window) {
        if (target == null || monitorId == null) {
            throw new IllegalArgumentException("时序查询缺少必填参数: target/monitorId");
        }
        if (!SqlName.isWord(target)) {
            throw new IllegalArgumentException("非法 target: " + target);
        }
        if (metricName != null && !SqlName.isWord(metricName)) {
            throw new IllegalArgumentException("非法 metricName: " + metricName);
        }
        SeriesEngine.requirePositiveHours(hours);
        if (!isAvailable()) {
            return List.of();
        }
        long nowMs = System.currentTimeMillis();
        long startMs = nowMs - (long) hours * 3600_000L;

        List<Object[]> points = new ArrayList<>();
        int dropped = 0;
        try {
            RedisCommands<String, String> sync = commands();
            // 关键：一条曲线只能对应一个指标。这里绝不遍历指标索引把所有指标混进同一条曲线，
            // 那样折线图的纵轴会变成「不同指标数值的平均」，看起来正常但语义无意义。
            String metric = resolveSingleMetric(sync, target, monitorId, metricName);
            if (metric == null) {
                return List.of();
            }
            List<String> members = sync.zrangebyscore(key(target, monitorId, metric), startMs, nowMs);
            if (members == null) {
                members = List.of();
            }
            for (String member : members) {
                int sep = member.lastIndexOf(':');
                if (sep <= 0) {
                    dropped++;
                    continue;
                }
                try {
                    double value = Double.parseDouble(member.substring(0, sep));
                    long ts = Long.parseLong(member.substring(sep + 1));
                    points.add(new Object[]{ts, value});
                } catch (NumberFormatException e) {
                    dropped++;
                    log.debug("[SeriesEngine] Redis 指标值解析失败: {}", member);
                }
            }
            // 坏成员被静默丢弃会让曲线残缺却看起来像「没数据」，必须留下可观测痕迹
            if (dropped > 0) {
                log.warn("[SeriesEngine] 丢弃无法解析的 Redis 成员 target={} monitor={} metric={} 丢弃={} 保留={}",
                        target, monitorId, metric, dropped, points.size());
            }
        } catch (Exception e) {
            log.warn("[SeriesEngine] Redis 查询失败 target={} monitor={}: {}", target, monitorId, e.getMessage());
            throw new IllegalStateException("Redis 时序曲线查询失败 target=" + target + ": " + e.getMessage(), e);
        }
        return SeriesEngine.downsample(points, SeriesEngine.windowMillis(window), MAX_SERIES_POINTS);
    }

    /**
     * 解析本次查询唯一对应的指标名。
     *
     * <p>显式给定 {@code metricName} 时直接采用；未给定时读指标索引，
     * 只有「恰好一个指标」才允许继续，索引为空表示无数据返回 {@code null}，
     * 索引有多个则抛 {@link IllegalArgumentException} 并列出候选——
     * 合并多条曲线会产出语义错误但不报错的图表数据，必须拒绝。</p>
     *
     * @param sync       Redis 同步命令
     * @param target     目标
     * @param monitorId  monitorID
     * @param metricName 显式指定的指标名，可为 null
     * @return 唯一指标名；无数据时返回 {@code null}
     */
    private String resolveSingleMetric(RedisCommands<String, String> sync, String target, Long monitorId, String metricName) {
        if (metricName != null) {
            return metricName;
        }
        Set<String> found = sync.smembers(metricsIndexKey(target, monitorId));
        if (found == null || found.isEmpty()) {
            return null;
        }
        if (found.size() == 1) {
            return found.iterator().next();
        }
        List<String> candidates = new ArrayList<>(found);
        Collections.sort(candidates);
        throw new IllegalArgumentException("target=" + target + " monitor=" + monitorId + " 下存在多个指标 "
                + candidates + "，无法确定要查询哪条曲线；请显式传入 metricName");
    }

    @Override
    public int purge(String target, Long monitorId, String metricName, long beforeMillis) {
        if (target == null) {
            throw new IllegalArgumentException("时序清理缺少必填参数: target");
        }
        if (!SqlName.isWord(target)) {
            throw new IllegalArgumentException("非法 target: " + target);
        }
        if (metricName != null && !SqlName.isWord(metricName)) {
            throw new IllegalArgumentException("非法 metricName: " + metricName);
        }
        if (!isAvailable()) {
            return 0;
        }
        int deleted = 0;
        try {
            RedisCommands<String, String> sync = commands();
            // monitorId 为 null 时无法用索引 SET 定位（SET 键本身含 monitorId），
            // 此时只能按 scan 逐键清理
            if (monitorId == null) {
                deleted = purgeAllTargets(sync, target, metricName, beforeMillis);
            } else {
                List<String> metrics = metricName != null
                        ? List.of(metricName)
                        : metrics(target, monitorId);
                for (String metric : metrics) {
                    Long removed = sync.zremrangebyscore(key(target, monitorId, metric), "-inf",
                            "(" + beforeMillis);
                    deleted += removed == null ? 0 : removed.intValue();
                }
            }
            log.info("[SeriesEngine] Redis 保留期清理 target={} monitor={} metric={} before={} 删除={} 点",
                    target, monitorId, metricName, beforeMillis, deleted);
            return deleted;
        } catch (Exception e) {
            log.error("[SeriesEngine] Redis 保留期清理失败 target={} monitor={}", target, monitorId, e);
            throw new IllegalStateException("Redis 时序保留期清理失败 target=" + target + ": " + e.getMessage(), e);
        }
    }

    /**
     * 跨全部监控目标清理某 target 的历史点。
     *
     * <p>指标索引 SET 的键里带 monitorId，指定 monitorId 时可直接命中索引；
     * {@code monitorId} 为 null 时只能扫描 {@code monitor:metric:&lt;target&gt;:*} 逐键清理。</p>
     *
     * @param sync       Redis 同步命令
     * @param target     目标
     * @param metricName 指标名，{@code null} 表示全部指标
     * @param beforeMillis 删除该毫秒时间戳之前的点
     * @return 删除点总数
     */
    private int purgeAllTargets(RedisCommands<String, String> sync, String target, String metricName, long beforeMillis) {
        int deleted = 0;
        // 必须用 SCAN 而非 KEYS：KEYS 会遍历全库键空间并阻塞 Redis 事件循环。
        // Lettuce 7 的同步命令接口不再提供 scan(...)，SCAN 统一走 ScanIterator。
        ScanArgs scanArgs = new ScanArgs().match(KEY_PREFIX + target + ":*").limit(SCAN_BATCH);
        ScanIterator<String> keys = ScanIterator.scan(sync, scanArgs);
        while (keys.hasNext()) {
            String zsetKey = keys.next();
            // 键形如 monitor:metric:<target>:<monitorId>[:<metric>]，
            // 末段是指标名；未指定 metricName 时按后缀再确认一层
            if (metricName != null && !zsetKey.endsWith(":" + metricName)) {
                continue;
            }
            Long removed = sync.zremrangebyscore(zsetKey, "-inf", "(" + beforeMillis);
            deleted += removed == null ? 0 : removed.intValue();
        }
        return deleted;
    }

    /**
     * 释放共享连接；若客户端由本引擎创建则一并关闭。
     *
     * <p>本方法与 {@link #commands()} 的建连路径共用 {@code this} 监视器：
     * 若不加锁，{@code close()} 可能在 {@code commands()} 执行
     * {@code redisClient.connect()} 的间隙读到尚未赋值的 {@code sharedConnection}，
     * 随后刚建立的连接被覆盖丢失、永远不会被关闭（连接泄漏）。
     * 加锁后 {@code close()} 要么先于建连执行（建连路径随即被 {@code closed} 拒绝），
     * 要么后于建连执行（能正常关闭）。</p>
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
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
     * @throws IllegalStateException 引擎已 {@link #close()}，不再建立新连接
     */
    private RedisCommands<String, String> commands() {
        StatefulRedisConnection<String, String> conn = sharedConnection;
        if (conn != null && conn.isOpen()) {
            return conn.sync();
        }
        synchronized (this) {
            // 必须在锁内复检：close() 已持同一把锁，放锁后建连会泄漏且客户端可能已 shutdown
            if (closed) {
                throw new IllegalStateException("RedisSeriesEngine 已关闭，拒绝建立新连接");
            }
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
