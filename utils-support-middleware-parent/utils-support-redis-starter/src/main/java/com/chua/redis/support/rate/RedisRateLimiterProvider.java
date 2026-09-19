package com.chua.redis.support.rate;

import com.chua.common.support.concurrent.rate.RateLimiterProvider;
import com.chua.common.support.spi.annotations.Spi;
import org.redisson.Redisson;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.concurrent.TimeUnit;

/**
 * 基于 Redisson 分布式限流器的实现。
 *
 * <p>使用 Redisson {@link RRateLimiter} 实现分布式限流，支持 OVERALL 和 PER_CLIENT 两种模式。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("redis")
public class RedisRateLimiterProvider implements RateLimiterProvider {

    /**
     * 名称
    */
    private final String name;
    /**
     * Redisson客户端
    */
    private final RedissonClient redissonClient;
    /**
     * 比率limiter
    */
    private final RRateLimiter rateLimiter;

    /**
     * 创建 redisrate限制提供者 实例
     * @param name 名称
     * @param name 字符串
     * @param permitsPerSecond double
     * @param redisUri redisuri
     * @param permitsPerSecond 许可证persecond
     */
    public RedisRateLimiterProvider(String name, String redisUri, double permitsPerSecond) {
        this.name = name;
        Config config = new Config();
        config.useSingleServer().setAddress(redisUri);
        this.redissonClient = Redisson.create(config);
        this.rateLimiter = redissonClient.getRateLimiter(name);
        this.rateLimiter.trySetRate(RateType.OVERALL, (long) permitsPerSecond, 1, RateIntervalUnit.SECONDS);
    }

    /**
     * 创建 redisrate限制提供者 实例
     * @param name 名称
     * @param redissonClient redisson客户端
     * @param permitsPerSecond double
     * @param redissonClient redisson客户端
     * @param permitsPerSecond 许可证persecond
     */
    public RedisRateLimiterProvider(String name, RedissonClient redissonClient, double permitsPerSecond) {
        this.name = name;
        this.redissonClient = redissonClient;
        this.rateLimiter = redissonClient.getRateLimiter(name);
        this.rateLimiter.trySetRate(RateType.OVERALL, (long) permitsPerSecond, 1, RateIntervalUnit.SECONDS);
    }

    @Override
    /**
     * 尝试获取
    */
    public boolean tryAcquire() {
        return rateLimiter.tryAcquire();
    }

    @Override
    /**
     * 尝试获取
    */
    public boolean tryAcquire(long timeout, TimeUnit timeUnit) {
        return rateLimiter.tryAcquire(timeout, timeUnit);
    }

    @Override
    /**
     * 获取名称
    */
    public String getName() {
        return name;
    }
}
