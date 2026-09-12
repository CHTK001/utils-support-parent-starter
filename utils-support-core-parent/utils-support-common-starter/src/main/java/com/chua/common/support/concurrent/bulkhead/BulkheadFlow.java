package com.chua.common.support.concurrent.bulkhead;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
* 并发隔离门面，通过信号量限制并发调用数。
*
* <p>当并发数达到上限时，新请求直接拒绝并返回降级结果。
* 防止单个服务的高并发拖垮整个系统资源。</p>
*
* <pre>{@code
* BulkheadFlow.of("api").maxConcurrent(10).execute(() -> callApi());
* BulkheadFlow.of("api").maxConcurrent(10).fallback(() -> fallbackResult).execute(() -> callApi());
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
public final class BulkheadFlow {

    /**
    * 默认最大并发数
     */
    private static final int DEFAULT_MAX_CONCURRENT = 10;

    /**
    * 并发隔离名称
     */
    private final String name;

    /**
    * 最大并发数，默认 10
     */
    private int maxConcurrent = DEFAULT_MAX_CONCURRENT;

    /**
    * 是否公平模式，默认公平
     */
    private boolean fair = true;

    /**
    * 到达并发上限时的降级回调
     */
    private Supplier<Object> fallback;

    /**
    * 创建 BulkheadFlow 实例
    * @param name name
     */
    private BulkheadFlow(String name) {
        this.name = name;
    }

    /**
    * 创建并发隔离门面实例。
    *
    * @param name 名称
    * @return 门面实例
     */
    public static BulkheadFlow of(String name) {
        return new BulkheadFlow(name);
    }

    /**
    * 设置最大并发数。
    *
    * @param maxConcurrent 最大并发数
    * @return 当前门面
     */
    public BulkheadFlow maxConcurrent(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
        return this;
    }

    /**
    * 设置信号量是否公平模式。
    *
    * @param fair true 公平，false 非公平
    * @return 当前门面
     */
    public BulkheadFlow fair(boolean fair) {
        this.fair = fair;
        return this;
    }

    /**
    * 设置到达并发上限时的降级回调。
    *
    * @param fallback 降级回调
    * @return 当前门面
     */
    public BulkheadFlow fallback(Supplier<Object> fallback) {
        this.fallback = fallback;
        return this;
    }

    /**
    * 在并发隔离保护下执行任务。
    *
    * @param callable 要执行的任务
    * @param <T>      返回值类型
    * @return 任务执行结果，并发满时返回降级结果
     */
    @SuppressWarnings("unchecked")
    public <T> T execute(Callable<T> callable) {
        Semaphore semaphore = SemaphoreRegistry.acquire(name, maxConcurrent, fair);
        if (!semaphore.tryAcquire()) {
            if (fallback != null) {
                return (T) fallback.get();
            }
            throw new IllegalStateException("并发数已达上限(" + maxConcurrent + "): " + name);
        }
        try {
            return callable.call();
        } catch (Exception e) {
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("并发隔离执行失败: " + name, e);
        } finally {
            semaphore.release();
        }
    }
}