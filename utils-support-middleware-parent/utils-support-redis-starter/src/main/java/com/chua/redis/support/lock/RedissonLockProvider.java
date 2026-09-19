package com.chua.redis.support.lock;

import com.chua.common.support.concurrent.lock.AbstractLockProvider;
import com.chua.common.support.spi.annotations.Spi;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.concurrent.TimeUnit;


/**
 * 基于 Redisson 的分布式锁提供者实现
 *
 * <p>使用 Redisson {@link RLock} 提供 Redis 分布式锁能力。
 * Redisson 的分布式锁实现了可重入锁、公平锁、自动续期（看门狗）等高级特性。
 *
 * <p>核心特性：
 * <ul>
 *   <li><strong>自动续期</strong>：看门狗机制，业务未完成时自动延长锁有效期</li>
 *   <li><strong>可重入</strong>：同一线程可多次获取同一把锁</li>
 *   <li><strong>高可用</strong>：支持单节点、哨兵、集群等多种部署模式</li>
 *   <li><strong>公平锁</strong>：支持公平和非公平两种模式</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * RedissonLockProvider provider = new RedissonLockProvider("redis://127.0.0.1:6379");
 * if (provider.tryLock(5, TimeUnit.SECONDS)) {
 *     try {
 *         // 执行业务逻辑
 *     } finally {
 *         provider.unlock();
 *     }
 * }
 * }</pre>.unlock();
 *     }
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("redis")
public class RedissonLockProvider extends AbstractLockProvider {

    /**

     * * 锁名称

     */
    private final String name;

    /**

     * * Redisson 客户端

     */
    private final RedissonClient redissonClient;

    /**

     * * Redisson 分布式锁

     */
    private final RLock lock;

    /**
     * 创建 Redisson 分布式锁提供者
     *
     * @param redisUri Redis 连接 URI，如 Redis://127.0.0.1:6379
     */
    public RedissonLockProvider(String redisUri) {
        this("redis-lock", redisUri);
    }

    /**
     * 创建指定名称的 Redisson 分布式锁提供者
     *
     * @param name     锁名称
     * @param redisUri Redis 连接 URI
     */
    public RedissonLockProvider(String name, String redisUri) {
        this.name = name;
        Config config = new Config();
        config.useSingleServer().setAddress(redisUri);
        this.redissonClient = Redisson.create(config);
        this.lock = redissonClient.getLock(name);
    }

    /**
     * 创建使用已有 Redisson 客户端的锁提供者
     *
     * @param name           锁名称
     * @param redissonClient Redisson 客户端
     */
    public RedissonLockProvider(String name, RedissonClient redissonClient) {
        this.name = name;
        this.redissonClient = redissonClient;
        this.lock = redissonClient.getLock(name);
    }

    /**
     * 尝试获取锁，如果在指定时间内无法获取则返回 false
     *
     * @param timeout  等待锁的最大时间
     * @param timeUnit 时间单位
     * @return 成功获取锁返回 true，否则返回 false
     */
    @Override
    protected boolean doTryLock(int timeout, TimeUnit timeUnit) {
        try {
            return lock.tryLock(timeout, timeUnit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    /**
     * 执行解锁
    */
    protected void doUnlock() {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    @Override
    /**
     * 执行获取名称
    */
    protected String doGetName() {
        return name;
    }

    @Override
    /**
     * 执行获取类型
    */
    protected String doGetType() {
        return "redis";
    }

    @Override
    /**
     * 关闭
    */
    public void close() throws Exception {
        super.close();
        redissonClient.shutdown();
    }
}
