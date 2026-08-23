package com.chua.common.support.concurrent.bulkhead;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 信号量注册表，按名称缓存并发信号量实例。
 *
 * <p>相同名称的并发隔离共享同一个 {@link Semaphore}，确保并发计数全局一致。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class SemaphoreRegistry {

    /**
     * 信号量缓存，按名称索引
     */
    private static final Map<String, Semaphore> CACHE = new ConcurrentHashMap<>();

    /** 创建 SemaphoreRegistry 实例 */
    private SemaphoreRegistry() {
    }

    /**
     * 获取指定名称的信号量，不存在则创建并缓存。
     *
     * @param name          信号量名称
     * @param permits       许可数
     * @param fair          是否公平
     * @return 信号量实例
     */
    public static Semaphore acquire(String name, int permits, boolean fair) {
        return CACHE.computeIfAbsent(name, k -> new Semaphore(permits, fair));
    }

    /**
     * 清空信号量缓存。
     */
    public static void clear() {
        CACHE.clear();
    }
}