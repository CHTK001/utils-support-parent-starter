package com.chua.common.support.concurrent.lock;

import com.chua.common.support.concurrent.lock.provider.ObjectLockProvider;
import com.chua.common.support.spi.ServiceProvider;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 锁流门面，支持链式调用、降级回调和受保护执行。
 *
 * <pre>{@code
 * LockFlow.of("orderLock").lockType("redis").tryLock();
 * LockFlow.of("orderLock").waitTime(5000).execute(() -> doSomething());
 * LockFlow.of("orderLock").fair(true).waitTime(3000).fallback(() -> fallbackResult).execute(() -> doSomething());
 * }</pre>
 *
 * @author CH
 */
@SuppressWarnings({"NullAway", "unchecked"})
@NullUnmarked
public final class LockFlow {

    /**
     * 锁提供者缓存，按缓存键索引
     */
    private static final Map<String, LockProvider> CACHE = new ConcurrentHashMap<>();

    /**
     * 锁名称
     */
    private final String name;

    /**
     * 锁类型标识
     */
    private String lockType;

    /**
     * 是否公平锁
     */
    private boolean fair;

    /**
     * 等待锁的时间（毫秒）
     */
    private long waitTime;

    /**
     * 租约时间（毫秒），-1 表示永不过期
     */
    private long leaseTime = -1;

    /**
     * 获取锁失败时的降级回调
     */
    private Supplier<Object> fallback;

    private LockFlow(String name) {
        this.name = name;
    }

    /**
     * 创建锁门面实例。
     *
     * @param name 锁名称
     * @return 门面实例
     */
    public static LockFlow of(String name) {
        return new LockFlow(name);
    }

    /**
     * 设置锁类型。
     *
     * @param lockType 锁类型标识
     * @return this
     */
    public LockFlow lockType(String lockType) {
        this.lockType = lockType;
        return this;
    }

    /**
     * 设置公平锁。
     *
     * @param fair 是否公平锁
     * @return this
     */
    public LockFlow fair(boolean fair) {
        this.fair = fair;
        return this;
    }

    /**
     * 设置等待时间。
     *
     * @param waitTime 等待锁的时间（毫秒）
     * @return this
     */
    public LockFlow waitTime(long waitTime) {
        this.waitTime = waitTime;
        return this;
    }

    /**
     * 设置租约时间。
     *
     * @param leaseTime 租约时间（毫秒），-1 表示永不过期
     * @return this
     */
    public LockFlow leaseTime(long leaseTime) {
        this.leaseTime = leaseTime;
        return this;
    }

    /**
     * 设置获取锁失败时的降级回调。
     *
     * @param fallback 降级回调，获取锁失败时执行
     * @return this
     */
    public LockFlow fallback(Supplier<Object> fallback) {
        this.fallback = fallback;
        return this;
    }

    /**
     * 尝试获取锁。
     *
     * @return 获取成功返回 true
     */
    public boolean tryLock() {
        LockProvider provider = getProvider();
        if (waitTime > 0) {
            return provider.tryLock((int) waitTime, TimeUnit.MILLISECONDS);
        }
        return provider.tryLock(0, TimeUnit.MILLISECONDS);
    }

    /**
     * 在锁保护下执行任务，获取失败时触发降级回调。
     *
     * @param task 待执行任务
     * @param <T>  返回值类型
     * @return 任务结果，获取失败时返回降级回调结果
     * @throws Exception 任务执行异常
     */
    public <T> T execute(Callable<T> task) throws Exception {
        LockProvider provider = getProvider();
        boolean locked = waitTime > 0
                ? provider.tryLock((int) waitTime, TimeUnit.MILLISECONDS)
                : provider.tryLock(0, TimeUnit.MILLISECONDS);
        if (!locked) {
            return onRejected();
        }
        try {
            return (T) LockReleaseSupport.releaseAfter(task.call(), provider::unlock);
        } catch (Throwable ex) {
            provider.unlock();
            throw ex;
        }
    }

    /**
     * 在锁保护下执行任务（无返回值），获取失败时静默跳过。
     *
     * @param runnable 待执行任务
     * @throws Exception 任务执行异常
     */
    public void execute(Runnable runnable) throws Exception {
        LockProvider provider = getProvider();
        boolean locked = waitTime > 0
                ? provider.tryLock((int) waitTime, TimeUnit.MILLISECONDS)
                : provider.tryLock(0, TimeUnit.MILLISECONDS);
        if (!locked) {
            onRejected();
            return;
        }
        try {
            LockReleaseSupport.releaseAfter(runnable, provider::unlock);
        } catch (Throwable ex) {
            provider.unlock();
            throw new RuntimeException(ex);
        }
    }

    /**
     * 执行降级回调。
     *
     * @param <T> 返回值类型
     * @return 降级回调结果
     * @throws Exception 降级回调执行异常
     */
    private <T> T onRejected() throws Exception {
        if (fallback != null) {
            return (T) fallback.get();
        }
        throw new IllegalStateException("获取锁失败：" + name);
    }

    /**
     * 从缓存获取或创建锁提供者。
     *
     * @return 锁提供者实例
     */
    private LockProvider getProvider() {
        return CACHE.computeIfAbsent(buildCacheKey(), k -> doCreate());
    }

    /**
     * 创建锁提供者，优先使用 SPI 发现，否则回退到 ObjectLockProvider。
     *
     * @return 锁提供者实例
     */
    private LockProvider doCreate() {
        String type = lockType != null && !lockType.isEmpty() ? lockType : "object";
        try {
            LockProvider provider = ServiceProvider.of(LockProvider.class).getExtension(type);
            if (provider != null) {
                provider.configure(buildSetting());
                return provider;
            }
        } catch (Exception ignored) {
            // SPI 未找到，使用默认实现
        }
        return new ObjectLockProvider(name, fair);
    }

    /**
     * 构建锁配置。
     *
     * @return 锁配置
     */
    private LockSetting buildSetting() {
        return LockSetting.builder()
                .name(name)
                .fair(fair)
                .waitTime(waitTime)
                .leaseTime(leaseTime)
                .lockType(lockType)
                .build();
    }

    /**
     * 构建缓存键。
     *
     * @return 缓存键字符串
     */
    private String buildCacheKey() {
        return name + ":" + (lockType != null ? lockType : "object") + ":" + fair;
    }

    /**
     * 获取已缓存的锁提供者。
     *
     * @param name 锁名称
     * @return 锁提供者实例，未找到返回 null
     */
    public static LockProvider get(String name) {
        return CACHE.get(name);
    }

    /**
     * 移除指定名称开头的锁缓存。
     *
     * @param name 锁名称前缀
     */
    public static void remove(String name) {
        CACHE.keySet().removeIf(key -> key.startsWith(name + ":"));
    }

    /**
     * 清空所有锁缓存。
     */
    public static void clear() {
        CACHE.clear();
    }

    /**
     * 获取锁提供者实例。
     *
     * @return LockProvider 实例
     */
    public LockProvider provider() {
        return getProvider();
    }
}