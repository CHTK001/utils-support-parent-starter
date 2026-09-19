package com.chua.common.support.concurrent.threadflow;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 线程执行器抽象基类，提供任务管理、执行模板、策略判定、事件回调与上下文透传。
 *
 * <p>子类需实现 {@link #submitTasks()} 方法以提交任务并返回 Future 列表。</p>
 *
 * @author CH
 * @since 2026/08/15
 */
public abstract class AbstractThreadExecutor implements ThreadExecutor<Object> {

    /**
     * 待执行任务列表
     */
    protected final List<Callable<Object>> tasks = new ArrayList<>();

    /**
     * 合并策略
     */
    protected final ThreadStrategy strategy;

    /**
     * 阈值（用于 N_FAIL / N_SUCCESS）
     */
    protected final int threshold;

    /**
     * 超时时间，0 表示无限等待
     */
    protected final long timeout;

    /**
     * 超时时间单位
     */
    protected final TimeUnit timeUnit;

    /**
     * 并发上限，小于 1 表示不限
     */
    protected final int maxConcurrent;

    /**
     * 并发信号量，限制同时执行的任务数
     */
    protected final Semaphore semaphore;

    /**
     * 生命周期事件回调
     */
    protected ThreadFlowListener listener;

    /**
     * 创建 AbstractThreadExecutor 实例
     * @param strategy strategy
     * @param int int
     * @param long long
     * @param TimeUnit TimeUnit
     * @param threshold 方法入参 threshold
     * @param timeout 超时时间，不允许为 null
     * @param timeUnit 时间Unit，不允许为 null
     */
    protected AbstractThreadExecutor(ThreadStrategy strategy, int threshold, long timeout, TimeUnit timeUnit) {
        this(strategy, threshold, timeout, timeUnit, -1);
    }

    /**
     * 创建 AbstractThreadExecutor 实例
     * @param strategy strategy
     * @param threshold threshold
     * @param timeout timeout
     * @param timeUnit timeUnit
     * @param maxConcurrent maxConcurrent
     */
    protected AbstractThreadExecutor(ThreadStrategy strategy, int threshold, long timeout, TimeUnit timeUnit,
                                     int maxConcurrent) {
        this.strategy = strategy;
        this.threshold = threshold;
        this.timeout = timeout;
        this.timeUnit = timeUnit;
        this.maxConcurrent = maxConcurrent;
        this.semaphore = maxConcurrent > 0 ? new Semaphore(maxConcurrent) : null;
    }

    @Override
    /** 添加Task */
    public ThreadExecutor<Object> addTask(Runnable runnable) {
        tasks.add(wrapWithConcurrency(wrapWithContext(() -> {
            runnable.run();
            return null;
        })));
        return this;
    }

    @Override
    /** 添加Callable */
    public ThreadExecutor<Object> addCallable(Callable<Object> callable) {
        tasks.add(wrapWithConcurrency(wrapWithContext(callable)));
        return this;
    }

    @Override
    /** Listener */
    public ThreadExecutor<Object> listener(ThreadFlowListener listener) {
        this.listener = listener;
        return this;
    }

    @Override
    /** 执行 */
    public ThreadFlowResult<Object> execute() throws Exception {
        long start = System.currentTimeMillis();
        if (listener != null) {
            listener.onStart(this);
        }

        int total = tasks.size();
        List<Future<Object>> futures = submitTasks();

        List<Object> results = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;
        int index = 0;
        boolean done = false;

        for (Future<Object> future : futures) {
            if (listener != null) {
                listener.onTaskStart(index);
            }
            try {
                Object result = getFutureResult(future);
                if (result != null) {
                    results.add(result);
                }
                successCount++;
                if (listener != null) {
                    listener.onNext(index, result);
                }
            } catch (Exception e) {
                errors.add(e);
                failCount++;
                if (listener != null) {
                    listener.onError(index, e);
                }
            }
            int doneCount = successCount + failCount;
            if (listener != null) {
                listener.onProcess(doneCount, total);
            }

            if (canShortCircuit(strategy, successCount, failCount, total)) {
                done = true;
                cancelRemaining(futures, index + 1);
                break;
            }
            index++;
        }

        if (!done) {
            index = futures.size();
        }
        boolean overallSuccess = evaluate(strategy, successCount, failCount, total);
        long cost = System.currentTimeMillis() - start;
        ThreadFlowResult<Object> result =
                new ThreadFlowResult<>(overallSuccess, results, errors, total, successCount, failCount, cost, strategy);
        if (listener != null) {
            listener.onComplete(result);
        }
        return result;
    }

    /**
     * 判断当前策略下是否满足提前短路条件。
     *
     * @param strategy     策略
     * @param successCount 已成功数
     * @param failCount    已失败数
     * @param total        总任务数
     * @return true 可提前结束并取消剩余任务
     */
    private boolean canShortCircuit(ThreadStrategy strategy, int successCount, int failCount, int total) {
        return switch (strategy) {
            case ANY_SUCCESS -> successCount >= 1;
            case ALL_SUCCESS -> failCount >= 1 || successCount == total;
            case ANY_FAIL -> failCount >= 1;
            case N_FAIL -> failCount >= threshold;
            case N_SUCCESS -> successCount >= threshold;
        };
    }

    /**
     * 取消尚未执行的任务。
     *
     * @param futures    任务 Future 列表
     * @param fromIndex  开始取消的下标
     */
    private void cancelRemaining(List<Future<Object>> futures, int fromIndex) {
        for (int i = fromIndex; i < futures.size(); i++) {
            futures.get(i).cancel(true);
        }
    }

    /**
     * 包装任务以施加并发限制。
     *
     * @param callable 原始任务
     * @return 受限任务
     */
    private Callable<Object> wrapWithConcurrency(Callable<Object> callable) {
        if (semaphore == null) {
            return callable;
        }
        return () -> {
            semaphore.acquire();
            try {
                return callable.call();
            } finally {
                semaphore.release();
            }
        };
    }

    /**
     * 包装任务以透传父线程上下文。
     *
     * <p>提交任务时捕获父线程的 {@link ThreadContext} 快照，
     * 任务执行线程绑定副本，结束或异常后自动清理，避免泄漏。</p>
     *
     * @param callable 原始任务
     * @return 上下文感知任务
     */
    private Callable<Object> wrapWithContext(Callable<Object> callable) {
        ThreadContext parent = ThreadContext.currentOrNull();
        if (parent == null) {
            return callable;
        }
        ThreadContext snapshot = parent.copy();
        return () -> {
            ThreadContext.set(snapshot);
            try {
                return callable.call();
            } finally {
                ThreadContext.set(snapshot);
            }
        };
    }

    /**
     * 获取 Future 结果，支持超时配置。
     *
     * @param future Future 实例
     * @return 任务结果
     * @throws InterruptedException 中断异常
     * @throws ExecutionException 执行异常
     * @throws TimeoutException 超时异常
     */
    protected Object getFutureResult(Future<Object> future) throws InterruptedException, ExecutionException, TimeoutException {
        if (timeout > 0) {
            return future.get(timeout, timeUnit);
        }
        return future.get();
    }

    /**
     * 提交所有任务，返回 Future 列表。
     *
     * @return Future 列表
     * @throws Exception 提交异常
     */
    protected abstract List<Future<Object>> submitTasks() throws Exception;

    /**
     * 根据策略判定整体结果。
     *
     * @param strategy     策略
     * @param successCount 成功数
     * @param failCount    失败数
     * @param total        总任务数
     * @return true 整体成功
     */
    private boolean evaluate(ThreadStrategy strategy, int successCount, int failCount, int total) {
        return switch (strategy) {
            case ANY_SUCCESS -> successCount > 0;
            case ALL_SUCCESS -> successCount == total;
            case ANY_FAIL -> failCount == 0;
            case N_FAIL -> failCount < threshold;
            case N_SUCCESS -> successCount >= threshold;
        };
    }
}
