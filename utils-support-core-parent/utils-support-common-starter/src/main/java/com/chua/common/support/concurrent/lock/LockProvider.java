package com.chua.common.support.concurrent.lock;

import java.util.concurrent.TimeUnit;


/**
 * 锁提供者接口，用于提供分布式或并发环境下的锁功能。
 * <p>
 * 此接口采用 SPI (Service Provider Interface) 机制实现，允许不同的锁实现类进行扩展和替换。
 * </p>
 *
 * @author CH
 * @since 2025-11-26
 */
public interface LockProvider extends AutoCloseable {

    /**
     * 尝试获取锁，如果在指定时间内无法获取则返回 false。
     *
     * @param timeout  等待锁的最大时间
     * @param timeUnit 时间的单位
     * @return 如果成功获取锁返回 true，否则返回 false
     */
    boolean tryLock(int timeout, TimeUnit timeUnit);

    /**
     * 尝试获取锁，默认使用毫秒作为时间单位。
     *
     * @param timeout 等待锁的最大时间（毫秒）
     * @return 如果成功获取锁返回 true，否则返回 false
     */
    default boolean tryLock(int timeout) {
        return tryLock(timeout, TimeUnit.MILLISECONDS);
    }

    /**
     * 释放当前持有的锁。
     */
    void unlock();

    /**
     * 获取锁的名称标识。
     *
     * @return 锁的名称
     */
    String getName();

    /**
     * 获取锁的类型标识。
     *
     * @return 锁的类型
     */
    String getType();

    /**
     * 关闭资源，自动释放锁。
     *
     * @throws Exception 如果释放锁时发生异常
     */
    @Override
    default void close() throws Exception {
        unlock();
    }

    /**
     * 使用配置初始化或更新锁提供者。
     *
     * @param setting 锁配置
     */
    default void configure(LockSetting setting) {
    }
}

