package com.chua.common.support.concurrent.threadflow;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
* 线程流程门面，提供链式 API 配置并发执行策略。
*
* <p>支持四种执行器类型：
* <ul>
*     <li>{@link ThreadExecutorType#PLATFORM}：平台线程池</li>
*     <li>{@link ThreadExecutorType#VIRTUAL}：虚拟线程</li>
*     <li>{@link ThreadExecutorType#REACTIVE}：响应式异步</li>
*     <li>{@link ThreadExecutorType#SYNC}：同步顺序执行</li>
* </ul>
* </p>
*
* <pre>{@code
* ThreadFlow.of("example")
*     .executorType(ThreadExecutorType.VIRTUAL)
*     .strategy(ThreadStrategy.ANY_SUCCESS)
*     .addTask(() -> System.out.println("task1"))
*     .addCallable(() -> "result")
*     .execute();
* }</pre>
*
* @author CH
* @since 2026/08/15
 */
public class ThreadFlow {

    /**
    * 流程名称
    */
    private final String name;

    /**
    * 执行器类型，默认虚拟线程
    */
    private ThreadExecutorType executorType = ThreadExecutorType.VIRTUAL;

    /**
    * 合并策略，默认全部成功
    */
    private ThreadStrategy strategy = ThreadStrategy.ALL_SUCCESS;

    /**
    * 阈值（用于 N_FAIL / N_SUCCESS）
    */
    private int threshold = 1;

    /**
    * 超时时间，0 表示无限等待
    */
    private long timeout = 0;

    /**
    * 超时单位
    */
    private TimeUnit timeUnit = TimeUnit.MILLISECONDS;

    /**
    * 并发上限，小于 1 表示不限
    */
    private int maxConcurrent = -1;

    /**
    * 生命周期事件回调
    */
    private ThreadFlowListener listener;

    /**
    * Runnable 任务列表
    */
    private final List<Runnable> runnableTasks = new ArrayList<>();

    /**
    * Callable 任务列表
    */
    private final List<Callable<Object>> callableTasks = new ArrayList<>();

    /**
    * 创建 ThreadFlow 实例
    * @param name name
    */
    private ThreadFlow(String name) {
        this.name = name;
    }

    /**
    * 创建 ThreadFlow 实例。
    *
    * @param name 流程名称
    * @return ThreadFlow 实例
    */
    public static ThreadFlow of(String name) {
        return new ThreadFlow(name);
    }

    /**
    * 设置执行器类型。
    *
    * @param type 执行器类型
    * @return this
    */
    public ThreadFlow executorType(ThreadExecutorType type) {
        this.executorType = type;
        return this;
    }

    /**
    * 设置合并策略。
    *
    * @param strategy 策略
    * @return this
    */
    public ThreadFlow strategy(ThreadStrategy strategy) {
        this.strategy = strategy;
        return this;
    }

    /**
    * 设置阈值（用于 N_FAIL / N_SUCCESS）。
    *
    * @param threshold 阈值
    * @return this
    */
    public ThreadFlow threshold(int threshold) {
        this.threshold = threshold;
        return this;
    }

    /**
    * 设置超时时间。
    *
    * @param timeout 超时值，0 表示无限等待
    * @param unit    时间单位
    * @return this
    */
    public ThreadFlow timeout(long timeout, TimeUnit unit) {
        this.timeout = timeout;
        this.timeUnit = unit;
        return this;
    }

    /**
    * 设置最大并发数（>0 生效），超过该数量的任务排队等待。
    *
    * @param maxConcurrent 并发上限
    * @return this
    */
    public ThreadFlow maxConcurrent(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
        return this;
    }

    /**
    * 注册生命周期事件回调。
    *
    * @param listener 事件回调，见 {@link ThreadFlowListener}
    * @return this
    */
    public ThreadFlow listener(ThreadFlowListener listener) {
        this.listener = listener;
        return this;
    }

    /**
    * 添加无返回值的任务。
    *
    * @param task 任务
    * @return this
    */
    public ThreadFlow addTask(Runnable task) {
        runnableTasks.add(task);
        return this;
    }

    /**
    * 添加有返回值的任务。
    *
    * @param task 任务
    * @return this
    */
    public ThreadFlow addCallable(Callable<?> task) {
        callableTasks.add((Callable<Object>) task);
        return this;
    }

    /**
    * 执行所有任务并返回聚合结果。
    *
    * @return 执行结果 {@link ThreadFlowResult}
    * @throws Exception 执行异常
    */
    public ThreadFlowResult<Object> execute() throws Exception {
        AbstractThreadExecutor executor = createExecutor();
        try {
            if (listener != null) {
                executor.listener(listener);
            }
            for (Runnable r : runnableTasks) {
                executor.addTask(r);
            }
            for (Callable<Object> c : callableTasks) {
                executor.addCallable(c);
            }
            return executor.execute();
        } finally {
            executor.close();
        }
    }

    /**
    * 根据 executorType 创建对应的执行器。
    *
    * @return 执行器实例
    */
    private AbstractThreadExecutor createExecutor() {
        return switch (executorType) {
            case PLATFORM -> new PlatformThreadExecutor(strategy, threshold, timeout, timeUnit, maxConcurrent);
            case VIRTUAL -> new VirtualThreadExecutor(strategy, threshold, timeout, timeUnit, maxConcurrent);
            case REACTIVE -> new ReactiveThreadExecutor(strategy, threshold, timeout, timeUnit, maxConcurrent);
            case SYNC -> new SyncThreadExecutor(strategy, threshold, timeout, timeUnit);
        };
    }
}
