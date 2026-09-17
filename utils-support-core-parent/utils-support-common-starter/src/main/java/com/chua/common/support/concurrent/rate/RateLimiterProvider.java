package com.chua.common.support.concurrent.rate;


/**
* 限流器提供者 SPI 接口。
*
* <p>定义限流器的核心行为：尝试获取许可。通过 SPI 机制支持不同的限流实现
* （如 Guava RateLimiter、Semaphore、Redis 令牌桶等）。</p>
*
* @author CH
* @since 2026/07/24
 */
public interface RateLimiterProvider {

    /**
    * 尝试获取一个许可。
    *
    * @return 获取成功返回 true，否则返回 false
    */
    boolean tryAcquire();

    /**
    * 尝试在指定时间内获取一个许可。
    *
    * @param timeout  超时时间
    * @param timeUnit 时间单位
    * @return 获取成功返回 true，否则返回 false
    */
    boolean tryAcquire(long timeout, java.util.concurrent.TimeUnit timeUnit);

    /**
    * 获取当前可用的许可数。
    *
    * @return 可用许可数，-1 表示不支持查询
    */
    default int availablePermits() {
        return -1;
    }

    /**
    * 获取限流器名称。
    *
    * @return 名称
    */
    String getName();
}
