package com.chua.common.support.concurrent.structured;

import com.chua.common.support.concurrent.backoff.BackoffProvider;
import com.chua.common.support.concurrent.backoff.provider.ExponentialBackoffProvider;
import com.chua.common.support.concurrent.structured.provider.ShutdownOnFailureStructuredConcurrencyProvider;
import com.chua.common.support.concurrent.structured.provider.ShutdownOnSuccessStructuredConcurrencyProvider;
import com.chua.common.support.concurrent.structured.provider.VirtualThreadStructuredConcurrencyProvider;
import com.chua.common.support.utils.ThreadUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 结构化并发流式构建器。
 *
 * <p>提供链式调用的结构化并发编程模型，支持失败策略、重试、超时等特性。</p>
 *
 * <pre>{@code
 * // 失败快速模式：任意任务失败立即取消其余任务
 * StructuredConcurrencyFlow.of("batch")
 *         .failureStrategy(FailureStrategy.FAIL_FAST)
 *         .submit(() -> callApi1())
 *         .submit(() -> callApi2())
 *         .collect();
 *
 * // 收集最佳结果模式：忽略失败的任务，返回成功的结果
 * StructuredConcurrencyFlow.of("batch")
 *         .failureStrategy(FailureStrategy.COLLECT_BEST)
 *         .submit(() -> callApi1())
 *         .submit(() -> callApi2())
 *         .submit(() -> callApi3())
 *         .collect();
 *
 * // 成功计数模式：达到指定数量的成功结果后停止
 * StructuredConcurrencyFlow.of("batch")
 *         .failureStrategy(FailureStrategy.SUCCESS_COUNT)
 *         .successCount(2)
 *         .submit(() -> callApi1())
 *         .submit(() -> callApi2())
 *         .submit(() -> callApi3())
 *         .collect();
 * }</pre>
 *
 * @since 2026/07/24
 */
@SuppressWarnings("unchecked")
public final class StructuredConcurrencyFlow {

    /**
     * 失败策略枚举。
 * @author CH
     */
    public enum FailureStrategy {
        /**
         * 快速失败：任一任务失败立即取消其余任务。
         */
        FAIL_FAST,

        /**
         * 收集最佳：收集所有成功结果，忽略失败。
         */
        COLLECT_BEST,

        /**
         * 成功计数：达到指定成功数后停止。
         */
        SUCCESS_COUNT,

        /**
         * 失败计数：达到指定失败数后停止。
         */
        FAILURE_COUNT,

        /**
         * 失败时重试：任务失败后进行重试，重试耗尽则按 FAIL_FAST 处理
         */
        RETRY_ON_FAILURE
    }

    /**
     * 名称
     */
    private final String name;

    /**
     * 失败策略，默认为 FAIL_FAST
     */
    private FailureStrategy failureStrategy = FailureStrategy.FAIL_FAST;

    /**
     * 成功计数，用于 SUCCESS_COUNT 策略，默认为 1
     */
    private int successCount = 1;

    /**
     * 失败计数，用于 FAILURE_COUNT 策略，默认为 1
     */
    private int failureCount = 1;

    /**
     * 最大重试次数，默认为 3
     */
    private int maxRetries = 3;

    /**
     * 退避策略
     */
    private BackoffProvider backoff;

    /**
     * 超时时间
     */
    private long timeout;

    /**
     * 超时时间单位
     */
    private TimeUnit timeUnit;

    /**
     * 降级回调
     */
    private Supplier<Object> fallback;

    /**
     * 任务列表
     */
    private final List<Callable<?>> tasks = new ArrayList<>();

    /**
     * 创建 StructuredConcurrencyFlow 实例
     * @param name name
     */
    private StructuredConcurrencyFlow(String name) {
        this.name = name;
    }

    /** Of */
    public static StructuredConcurrencyFlow of(String name) {
        return new StructuredConcurrencyFlow(name);
    }

    /** FailureStrategy */
    public StructuredConcurrencyFlow failureStrategy(FailureStrategy failureStrategy) {
        this.failureStrategy = failureStrategy;
        return this;
    }

    /** Success计算数量 */
    public StructuredConcurrencyFlow successCount(int successCount) {
        this.successCount = successCount;
        return this;
    }

    /** Failure计算数量 */
    public StructuredConcurrencyFlow failureCount(int failureCount) {
        this.failureCount = failureCount;
        return this;
    }

    /** 最大值Retries */
    public StructuredConcurrencyFlow maxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
        return this;
    }

    /** Backoff */
    public StructuredConcurrencyFlow backoff(BackoffProvider backoff) {
        this.backoff = backoff;
        return this;
    }

    /** Timeout */
    public StructuredConcurrencyFlow timeout(long timeout, TimeUnit unit) {
        this.timeout = timeout;
        this.timeUnit = unit;
        return this;
    }

    /** Fallback */
    public StructuredConcurrencyFlow fallback(Supplier<Object> fallback) {
        this.fallback = fallback;
        return this;
    }

    /** 提交 */
    public <T> StructuredConcurrencyFlow submit(Callable<T> task) {
        tasks.add(task);
        return this;
    }

    /** 提交 */
    public StructuredConcurrencyFlow submit(Runnable task) {
        tasks.add(() -> {
            task.run();
            return null;
        });
        return this;
    }

    /** 合并 */
    public void join() throws Exception {
        executeAll();
    }

    /** Collect */
    public <T> List<T> collect() throws Exception {
        return (List<T>) executeAll();
    }

    /** 合并 */
    public <T, R> R merge(Function<List<T>, R> merger) throws Exception {
        return merger.apply((List<T>) executeAll());
    }

    /**
     * 获取结构化并发提供者。
     *
     * <p>根据当前配置的失败策略创建对应的提供者实现，并注册到全局缓存中：</p>
     * <ul>
     *   <li>{@link FailureStrategy#FAIL_FAST} -> {@link ShutdownOnFailureStructuredConcurrencyProvider}</li>
     *   <li>{@link FailureStrategy#COLLECT_BEST} -> {@link ShutdownOnSuccessStructuredConcurrencyProvider}</li>
     *   <li>其余策略 -> {@link VirtualThreadStructuredConcurrencyProvider}</li>
     * </ul>
     *
     * <p>提供者按 Flow 名称缓存（{@code computeIfAbsent}），首次调用后注册到缓存，
     * 后续调用直接返回缓存实例，不会因策略变更而改变。</p>
     *
     * @return 结构化并发提供者
     */
    public StructuredConcurrencyProvider getProvider() {
        return CACHE.computeIfAbsent(name, k -> createProvider(failureStrategy));
    }

    /** 执行All */
    private List<Object> executeAll() throws Exception {
        if (tasks.isEmpty()) {
            return List.of();
        }
        ExecutorService executor = resolveExecutor();
        try {
            return switch (failureStrategy) {
                case FAIL_FAST -> executeFailFast(executor);
                case COLLECT_BEST -> executeCollectBest(executor);
                case SUCCESS_COUNT -> executeSuccessCount(executor);
                case FAILURE_COUNT -> executeFailureCount(executor);
                case RETRY_ON_FAILURE -> executeWithRetry(executor);
            };
        } finally {
            executor.shutdown();
        }
    }

    /**
     * 快速失败策略：使用 invokeAll 并行执行，任一失败即取消其余。
     */
    private List<Object> executeFailFast(ExecutorService executor) throws Exception {
        List<Future<Object>> futures = invokeAll(executor);
        List<Object> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                results.add(futures.get(i).get());
            } catch (ExecutionException e) {
                cancelRemaining(futures, i + 1);
                return handleFailure((Exception) e.getCause());
            }
        }
        return results;
    }

    /**
     * 收集最佳策略：使用 invokeAll 并行执行，忽略失败的任务，返回 null 作为失败标记。
     */
    private List<Object> executeCollectBest(ExecutorService executor) {
        try {
            List<Future<Object>> futures = invokeAll(executor);
            return futures.stream()
                    .map(f -> {
                        try { return getWithTimeout(f); } catch (Exception e) { return null; }
                    })
                    .collect(Collectors.toList());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return IntStream.range(0, tasks.size()).mapToObj(i -> (Object) null).collect(Collectors.toList());
        }
    }

    /**
     * 成功计数策略：达到指定成功数后立即取消其余任务。
     */
    private List<Object> executeSuccessCount(ExecutorService executor) throws Exception {
        List<Future<Object>> futures = invokeAll(executor);
        List<Object> results = new ArrayList<>();
        int success = 0;
        int fail = 0;
        Exception firstException = null;
        for (int i = 0; i < futures.size(); i++) {
            try {
                results.add(futures.get(i).get());
                success++;
                if (success >= successCount) {
                    cancelRemaining(futures, i + 1);
                    return results;
                }
            } catch (ExecutionException e) {
                results.add(null);
                fail++;
                firstException = (Exception) e.getCause();
                if (tasks.size() - fail < successCount) {
                    cancelRemaining(futures, i + 1);
                    return handleFailure(firstException);
                }
            }
        }
        if (success >= successCount) {
            return results;
        }
        return handleFailure(new IllegalStateException("成功数量不足: " + success + "<" + successCount));
    }

    /**
     * 失败计数策略：达到指定失败数后停止。
     */
    private List<Object> executeFailureCount(ExecutorService executor) throws Exception {
        List<Future<Object>> futures = invokeAll(executor);
        List<Object> results = new ArrayList<>();
        int fail = 0;
        Exception firstException = null;
        for (int i = 0; i < futures.size(); i++) {
            try {
                results.add(futures.get(i).get());
            } catch (ExecutionException e) {
                results.add(null);
                fail++;
                firstException = (Exception) e.getCause();
                if (fail > failureCount) {
                    cancelRemaining(futures, i + 1);
                    return handleFailure(firstException);
                }
            }
        }
        return results;
    }

    /**
     * 带重试的失败策略：任务失败后进行重试。
     */
    private List<Object> executeWithRetry(ExecutorService executor) throws Exception {
        List<Object> results = new ArrayList<>();
        List<Integer> failedIndices = new ArrayList<>();

        invokeAll(executor).forEach(f -> {
            try {
                results.add(f.get());
            } catch (Exception e) {
                results.add(null);
                failedIndices.add(results.size() - 1);
            }
        });

        if (failedIndices.isEmpty()) {
            return results;
        }

        BackoffProvider bp = resolveBackoff();
        for (int attempt = 0; attempt < maxRetries && !failedIndices.isEmpty(); attempt++) {
            bp.sleep(attempt);
            List<Integer> stillFailed = new ArrayList<>();
            for (int idx : failedIndices) {
                try {
                    Object result = executor.submit(tasks.get(idx)).get();
                    results.set(idx, result);
                } catch (Exception e) {
                    stillFailed.add(idx);
                }
            }
            failedIndices.clear();
            failedIndices.addAll(stillFailed);
        }

        if (!failedIndices.isEmpty()) {
            return handleFailure(new IllegalStateException("重试耗尽后仍有任务失败: " + name));
        }

        return results;
    }

    /**
     * 使用执行器并行执行所有任务。
     */
    private List<Future<Object>> invokeAll(ExecutorService executor) throws InterruptedException {
        return executor.invokeAll(tasks.stream()
                .map(t -> (Callable<Object>) () -> t.call())
                .collect(Collectors.toList()));
    }

    /** 获取WithTimeout */
    private Object getWithTimeout(Future<Object> future) throws Exception {
        if (timeout > 0 && timeUnit != null) {
            return future.get(timeout, timeUnit);
        }
        return future.get();
    }

    /** 处理Failure */
    private List<Object> handleFailure(Exception e) throws Exception {
        if (fallback != null) {
            return List.of(fallback.get());
        }
        throw e;
    }

    /** CancelRemaining */
    private void cancelRemaining(List<Future<Object>> futures, int startIndex) {
        for (int i = startIndex; i < futures.size(); i++) {
            futures.get(i).cancel(true);
        }
    }

    /** 解析Backoff */
    private BackoffProvider resolveBackoff() {
        if (backoff != null) {
            return backoff;
        }
        return new ExponentialBackoffProvider();
    }

    /** 解析Executor */
    private ExecutorService resolveExecutor() {
        return ThreadUtils.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 全局缓存 Map。
     */
    private static final Map<String, StructuredConcurrencyProvider> CACHE = new ConcurrentHashMap<>();

    /**
     * 根据名称获取缓存的 Provider，不存在则返回 null。
     *
     * @param name 名称
     * @return 缓存的 Provider 或 null
     */
    public static StructuredConcurrencyProvider get(String name) {
        return CACHE.get(name);
    }

    /**
     * 注册一个结构化并发提供者到全局缓存。
     *
     * <p>注册后可通过 {@link #get(String)} 按名称重复使用，避免重复创建。</p>
     *
     * @param name     提供者名称
     * @param provider 结构化并发提供者
     * @return 之前已注册的同名提供者，若之前未注册则返回 null
     */
    public static StructuredConcurrencyProvider register(String name, StructuredConcurrencyProvider provider) {
        return CACHE.put(name, provider);
    }

    /**
     * 从全局缓存中注销指定名称的提供者。
     *
     * @param name 要注销的提供者名称
     * @return 被移除的提供者，若之前未注册则返回 null
     */
    public static StructuredConcurrencyProvider unregister(String name) {
        return CACHE.remove(name);
    }

    /**
     * 获取或创建一个指定名称的默认提供者（基于虚拟线程）并缓存。
     *
     * <p>如果缓存中已有同名提供者则直接返回，否则创建一个新的
     * {@link VirtualThreadStructuredConcurrencyProvider} 并注册到缓存中。</p>
     *
     * @param name 提供者名称
     * @return 缓存的结构化并发提供者（不会为 null）
     */
    public static StructuredConcurrencyProvider getOrCreate(String name) {
        return CACHE.computeIfAbsent(name, k -> new VirtualThreadStructuredConcurrencyProvider());
    }

    /**
     * 获取或创建一个指定名称和策略的提供者并缓存。
     *
     * <p>如果缓存中已有同名提供者则直接返回，否则根据策略创建对应的提供者并注册。</p>
     *
     * @param name     提供者名称
     * @param strategy 失败策略，决定创建哪种类型的提供者
     * @return 缓存的结构化并发提供者（不会为 null）
     */
    public static StructuredConcurrencyProvider getOrCreate(String name, FailureStrategy strategy) {
        return CACHE.computeIfAbsent(name, k -> createProvider(strategy));
    }

    /**
     * 根据失败策略创建对应的 Provider。
     */
    private static StructuredConcurrencyProvider createProvider(FailureStrategy strategy) {
        return switch (strategy) {
            case FAIL_FAST -> new ShutdownOnFailureStructuredConcurrencyProvider();
            case COLLECT_BEST -> new ShutdownOnSuccessStructuredConcurrencyProvider();
            case SUCCESS_COUNT, FAILURE_COUNT, RETRY_ON_FAILURE -> new VirtualThreadStructuredConcurrencyProvider();
        };
    }
}