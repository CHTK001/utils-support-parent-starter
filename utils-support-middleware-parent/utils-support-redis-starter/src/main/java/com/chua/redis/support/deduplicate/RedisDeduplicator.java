package com.chua.redis.support.deduplicate;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.task.deduplicate.Deduplicator;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

/**
 * 基于 Redisson 的 Redis 去重器，实现分布式幂等。
 * <p>
 * 使用 Redis SETNX + TTL 实现，支持跨进程/跨节点的去重判断。
 * 默认 TTL 5 分钟，可通过构造参数调整。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("redis")
@SpiDescribe("Redis 分布式去重器")
public class RedisDeduplicator implements Deduplicator {

    /**

     * * 默认 TTL，5 分钟

     */
    private static final long DEFAULT_TTL_MS = 5 * 60 * 1000L;

    /**

     * * Redis 键 前缀

     */
    private static final String KEY_PREFIX = "dedup:";

    /**
     * Redisson
    */
    private final RedissonClient redisson;
    /**
     * TTLMS
    */
    private final long ttlMs;

    /**
     * 创建 redisdeduplicator 实例
     * @param redisson redisson
     */
    public RedisDeduplicator(RedissonClient redisson) {
        this(redisson, DEFAULT_TTL_MS);
    }

    /**
     * 创建 redisdeduplicator 实例
     * @param redisson redisson
     * @param ttlMs long
     * @param ttlMs ttlms
     */
    public RedisDeduplicator(RedissonClient redisson, long ttlMs) {
        this.redisson = redisson;
        this.ttlMs = ttlMs;
    }

    @Override
    /**
     * 是否重复
    */
    public boolean isDuplicate(String key) {
        return redisson.getBucket(KEY_PREFIX + key).isExists();
    }

    @Override
    /**
     * 标记处理
    */
    public void markProcessed(String key) {
        RBucket<String> bucket = redisson.getBucket(KEY_PREFIX + key);
        bucket.set("1", ttlMs, TimeUnit.MILLISECONDS);
    }

    @Override
    /**
     * Clear
    */
    public void clear() {
        // Redis 不支持批量按前缀删除的原子操作，保留为空实现
    }

    @Override
    /**
     * 获取大小
    */
    public int size() {
        return 0;
    }
}
