package com.chua.common.support.utils;

import com.chua.common.support.function.NamedThreadFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.chua.common.support.constant.NameConstant.DEFAULT;
import static java.util.concurrent.Executors.newThreadPerTaskExecutor;


/**
 * 线程工具类，提供线程池创建、线程管理、休眠等常用操作。
 *
 * <p>支持虚拟线程、固定线程池、缓存线程池、定时任务线程池等多种线程模型，
 * 以及线程工厂、安全关闭、JVM 钩子注册等辅助功能。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ThreadUtils {
    /**
     * 无效退出状态码标记。
     */
    public static final int INVALID_EXITVALUE = 0xdeadbeef;
    /** 全局_执行器 */
    public static final Executor GLOBAL_EXECUTOR = newVirtualThreadExecutor();

    /**
     * 单例线程数。
     */
    private static final int SINGLETON = 1;

    /**
     * 空闲线程存活时间（0 表示不回收）。
     */
    private static final long KEEP_ALIVE_TIME = 0L;

    /** 处理器 */
    private static final int PROCESSOR = processor();

    /** Thread_游泳池 */
    private static final ExecutorService THREAD_POOL = newVirtualThreadExecutor();
    /** 调度_执行器_服务 */
    private static final ScheduledExecutorService SCHEDULED_EXECUTOR_SERVICE = newScheduledThreadPoolExecutor(200, "com-ch-scheduled-thread-pool");

    static {
        Runtime.getRuntime().addShutdownHook(newThread(THREAD_POOL::shutdownNow));
    }

    /**
     * 安静关闭线程池（不抛异常）。
     * <p>
      * 若传入的 执行器 为 执行器服务 实例，则立即调用 关闭now。
     * </p>
     *
     * @param executor 待关闭的执行器
     */
    public static void closeQuietly(final Executor executor) {
        if (executor instanceof ExecutorService executorService) {
            executorService.shutdownNow();
        }
    }

    /**
     * 安静关闭可关闭资源（不抛异常）。
     * <p>
      * 忽略关闭过程中抛出的 io异常。
     * </p>
     *
     * @param closeable 待关闭的资源
     */
    public static void closeQuietly(final Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // ignored
            }
        }
    }

    /**
     * 安静中断线程。
     * <p>
     * 调用线程的 interrupt 方法。
     * </p>
     *
     * @param thread 待中断的线程
     */
    public static void closeQuietly(final Thread thread) {
        if (thread != null) {
            thread.interrupt();
        }
    }

    /**
     * 新建单线程线程池并立即执行任务。
     * <p>
     * 创建后自动执行一次给定的 Runnable。
     * </p>
     *
     * @param runnable 待执行的任务
     */
    public static void newAndRunThread(final Runnable runnable) {
        var executorService = newSingleThreadExecutor();
        executorService.execute(runnable);
    }

    /**
     * 创建默认缓存线程池。
     * <p>
      * 核心线程 0，最大 Integer.最大_值，空闲 60s 回收。
     * </p>
     *
     * @return 缓存线程池
     */
    public static ExecutorService newCachedThreadPool() {
        return newCachedThreadPool("global-" + DEFAULT + "-cached-pool");
    }

    /**
     * 创建带名称的缓存线程池。
     * <p>
      * 核心线程 0，最大 Integer.最大_值，空闲 60s 回收。
     * </p>
     *
     * @param name 线程名称前缀
     * @return 缓存线程池
     */
    public static ExecutorService newCachedThreadPool(final String name) {
        return new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(), newThreadFactory(name));
    }

    /**
     * 创建缓存线程池并立即执行一个任务。
     * <p>
      * 线程池创建后立即执行 供应商 提供的任务。
     * </p>
     *
     * @param name     线程名称前缀
     * @param supplier 任务提供者
     * @return 缓存线程池
     */
    public static ExecutorService newCachedThreadPool(String name, Supplier<Runnable> supplier) {
        var executorService = newCachedThreadPool(name);
        executorService.execute(supplier.get());
        return executorService;
    }

    /**
     * 使用指定线程工厂创建缓存线程池。
     * <p>
      * 核心线程 0，最大 Integer.最大_值，空闲 60s 回收。
     * </p>
     *
     * @param threadFactory 线程工厂
     * @return 缓存线程池
     */
    public static ExecutorService newCachedThreadPool(ThreadFactory threadFactory) {
        return new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(), threadFactory);
    }

    /**
      * 创建新的 completable期货 实例。
     * <p>
      * 返回尚未完成的 completable期货，可用于异步结果传递。
     * </p>
     *
     * @param <T> 结果类型
     * @return 新的 completable期货 实例
     */
    public static <T> CompletableFuture<T> newCompletableFuture() {
        return new CompletableFuture<>();
    }

    /**
      * 获取执行器，若传入的 执行器 为 空 则创建默认大小的固定线程池。
     * <p>
      * 默认线程数由 处理器() 决定（CPU 核心数 × 2 - 1）。
     * </p>
     *
     * @param threadFactory 线程工厂
     * @param executor      现有执行器（可为 空）
     * @return 执行器实例
     */
    public static Executor newExecutor(ThreadFactory threadFactory, Executor executor) {
        return newExecutor(threadFactory, executor, processor());
    }

    /**
      * 获取指定大小的执行器，若传入的 执行器 为 空 则创建固定线程池。
     * <p>
      * 优先使用传入的 执行器，否则按 大小 创建新线程池。
     * </p>
     *
     * @param threadFactory 线程工厂
     * @param executor      现有执行器（可为 空）
     * @param size          线程池大小
     * @return 执行器实例
     */
    public static Executor newExecutor(ThreadFactory threadFactory, Executor executor, int size) {
        return Optional.ofNullable(executor).orElse(newFixedThreadExecutor(size, threadFactory));
    }

    /**
     * 创建固定大小的线程池（默认名称）。
     * <p>
     * 使用全局默认的线程名称模板。
     * </p>
     *
     * @param max 最大线程数
     * @return 固定大小线程池
     */
    public static ExecutorService newFixedThreadExecutor(final int max) {
        return newFixedThreadExecutor(max, "com-ch-global-" + DEFAULT + "-fixed-{" + max + "}-pool");
    }

    /**
     * 创建虚拟线程池。
     * <p>
      * 使用 Java 虚拟线程（虚拟 Thread），每个任务启动一条新虚拟线程。
     * </p>
     *
     * @return 虚拟线程池
     */
    public static ExecutorService newVirtualThreadExecutor() {
        return newThreadPerTaskExecutor(Thread.ofVirtual()
                .name("com-ch-virtual-" + DEFAULT + "-virtual-{1000}-pool")
                .factory());
    }

    /**
     * 创建指定名称的固定大小线程池。
     * <p>
      * 使用 执行器.新fixedthread游泳池 实现。
     * </p>
     *
     * @param thread 线程数
     * @param name   线程名称前缀
     * @return 固定大小线程池
     */
    public static ExecutorService newFixedThreadExecutor(int thread, String name) {
        return new ThreadPoolExecutor(thread, thread,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(),
                newThreadFactory(name));
    }

    /**
     * 创建固定大小线程池并预填充任务。
     * <p>
      * 创建后立即执行 thread 次 供应商 提供的任务。
     * </p>
     *
     * @param thread   线程数
     * @param name     线程名称前缀
     * @param supplier 任务提供者
     * @return 固定大小线程池
     */
    public static ExecutorService newFixedThreadExecutor(int thread, String name, Supplier<Runnable> supplier) {
        var executorService = newFixedThreadExecutor(thread, name);
        for (int i = 0; i < thread; i++) {
            executorService.execute(supplier.get());
        }
        return executorService;
    }

    /**
     * 使用指定线程工厂创建固定大小线程池。
     * <p>
      * 委托给 执行器.新fixedthread游泳池。
     * </p>
     *
     * @param thread        线程数
     * @param threadFactory 线程工厂
     * @return 固定大小线程池
     */
    public static ExecutorService newFixedThreadExecutor(int thread, ThreadFactory threadFactory) {
        return new ThreadPoolExecutor(thread, thread,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(),
                threadFactory);
    }

    /**
     * 创建固定大小线程池（JDK 标准风格）。
     * <p>
      * 核心线程数和最大线程数均为 nthreads，使用默认名称。
     * </p>
     *
     * @param nThreads 线程数
     * @return 固定大小线程池
     */
    public static ExecutorService newFixedThreadPool(int nThreads) {
        return newFixedThreadPool(nThreads, "global-" + DEFAULT + "-fixed-pool");
    }

    /**
     * 创建带名称的固定大小线程池。
     * <p>
      * 核心线程数和最大线程数均为 nthreads。
     * </p>
     *
     * @param nThreads 线程数
     * @param name     线程名称前缀
     * @return 固定大小线程池
     */
    public static ExecutorService newFixedThreadPool(int nThreads, String name) {
        return new ThreadPoolExecutor(nThreads, nThreads,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(),
                newThreadFactory(name));
    }

    /**
     * 使用指定线程工厂创建固定大小线程池。
     * <p>
      * 核心线程数和最大线程数均为 nthreads。
     * </p>
     *
     * @param nThreads      线程数
     * @param threadFactory 线程工厂
     * @return 固定大小线程池
     */
    public static ExecutorService newFixedThreadPool(int nThreads, ThreadFactory threadFactory) {
        return new ThreadPoolExecutor(nThreads, nThreads,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(),
                threadFactory);
    }

    /**
      * 获取 分叉连接 公共线程池。
     * <p>
      * 返回 fork连接游泳池.通用游泳池()，适用于并行计算。
     * </p>
     *
     * @return ForkJoin 公共线程池
     */
    public static ExecutorService newForkJoinPool() {
        return ForkJoinPool.commonPool();
    }

    /**
     * 创建取较大值的线程池。
     * <p>
      * 线程数为 值1 和 值2 中的较大值。
     * </p>
     *
     * @param value1 候选值 1
     * @param value2 候选值 2
     * @return 固定大小线程池
     */
    public static ExecutorService newMaxThreadExecutor(final int value1, final int value2) {
        return newFixedThreadExecutor(Math.max(value1, value2));
    }

    /**
     * 创建取较大值且带名称的线程池。
     * <p>
      * 线程数为 值1 和 值2 中的较大值，使用指定名称。
     * </p>
     *
     * @param value1 候选值 1
     * @param value2 候选值 2
     * @param name   线程名称前缀
     * @return 固定大小线程池
     */
    public static ExecutorService newMaxThreadExecutor(final int value1, final int value2, final String name) {
        return newFixedThreadExecutor(Math.max(value1, value2), name);
    }

    /**
     * 创建取较小值的线程池。
     * <p>
      * 线程数为 值1 和 值2 中的较小值。
     * </p>
     *
     * @param value1 候选值 1
     * @param value2 候选值 2
     * @return 固定大小线程池
     */
    public static ExecutorService newMinThreadExecutor(final int value1, final int value2) {
        return newFixedThreadExecutor(Math.min(value1, value2));
    }

    /**
     * 创建取较小值且带名称的线程池。
     * <p>
      * 线程数为 值1 和 值2 中的较小值，使用指定名称。
     * </p>
     *
     * @param value1 候选值 1
     * @param value2 候选值 2
     * @param name   线程名称前缀
     * @return 固定大小线程池
     */
    public static ExecutorService newMinThreadExecutor(final int value1, final int value2, final String name) {
        return newFixedThreadExecutor(Math.min(value1, value2), name);
    }

    /**
     * 创建处理器核心数的线程池。
     * <p>
      * 线程数由 处理器() 决定（CPU 核心数 × 2 - 1）。
     * </p>
     *
     * @return 固定大小线程池
     */
    public static ExecutorService newProcessorThreadExecutor() {
        return newFixedThreadExecutor(PROCESSOR);
    }

    /**
     * 创建不超过处理器核心数的线程池。
     * <p>
      * 取 核心 和 处理器 中的较小值作为线程数。
     * </p>
     *
     * @param core 期望线程数
     * @return 固定大小线程池
     */
    public static ExecutorService newProcessorThreadExecutor(final int core) {
        return newFixedThreadExecutor(Math.min(core, PROCESSOR));
    }

    /**
     * 创建带名称的处理器核心数线程池。
     * <p>
      * 线程数由 处理器() 决定，使用指定名称。
     * </p>
     *
     * @param name 线程名称前缀
     * @return 固定大小线程池
     */
    public static ExecutorService newProcessorThreadExecutor(final String name) {
        return newFixedThreadExecutor(PROCESSOR, name);
    }

    /**
     * 创建定时任务线程池。
     * <p>
      * 使用 调度thread游泳池执行器 实现，默认名称 全局-调度-游泳池。
     * </p>
     *
     * @param thread 核心线程数
     * @return 定时任务线程池
     */
    public static ScheduledExecutorService newScheduledThreadPoolExecutor(final int thread) {
        return new ScheduledThreadPoolExecutor(thread, newThreadFactory("GLOBAL-" + DEFAULT + "-SCHEDULE-POOL"));
    }

    /**
     * 创建固定延迟的定时任务（默认线程池）。
     * <p>
     * 任务完成后，等待指定延迟再执行下一次。
     * </p>
     *
     * @param runnable     待执行的任务
     * @param initialDelay 初始延迟
     * @param delay        每次执行后的延迟
     * @param unit         时间单位
     * @return 调度结果 期货
     */
    public static ScheduledFuture<?> newScheduleWithFixedDelay(final Runnable runnable, long initialDelay,
                                                                long delay, TimeUnit unit) {
        return newScheduleWithFixedDelay("GLOBAL-" + DEFAULT + "-SCHEDULED-2-POOL", runnable, initialDelay, delay, unit);
    }

    /**
     * 创建指定线程名称的固定延迟定时任务。
     * <p>
     * 每次运行结束后等待指定延迟再执行下一次。
     * </p>
     *
     * @param threadName   线程名称
     * @param runnable     待执行的任务
     * @param initialDelay 初始延迟
     * @param delay        每次执行后的延迟
     * @param unit         时间单位
     * @return 调度结果 期货
     */
    public static ScheduledFuture<?> newScheduleWithFixedDelay(final String threadName, final Runnable runnable,
                                                                long initialDelay, long delay, TimeUnit unit) {
        return newScheduledThreadPoolExecutor(1, newThreadFactory(threadName))
                .scheduleWithFixedDelay(runnable, initialDelay, delay, unit);
    }

    /**
     * 创建指定名称的定时任务线程池。
     * <p>
      * 使用 调度thread游泳池执行器 实现。
     * </p>
     *
     * @param thread 核心线程数
     * @param name   线程名称前缀
     * @return 定时任务线程池
     */
    public static ScheduledExecutorService newScheduledThreadPoolExecutor(final int thread, final String name) {
        return new ScheduledThreadPoolExecutor(thread, newThreadFactory(name));
    }

    /**
     * 使用指定线程工厂创建定时任务线程池。
     * <p>
      * 核心线程数为 thread，使用指定的 thread工厂。
     * </p>
     *
     * @param thread        核心线程数
     * @param threadFactory 线程工厂
     * @return 定时任务线程池
     */
    public static ScheduledExecutorService newScheduledThreadPoolExecutor(final int thread, final ThreadFactory threadFactory) {
        return new ScheduledThreadPoolExecutor(thread, threadFactory);
    }

    /**
     * 创建单线程的定时任务线程池。
     * <p>
     * 核心线程数为 1，使用指定名称。
     * </p>
     *
     * @param name 线程名称前缀
     * @return 定时任务线程池
     */
    public static ScheduledExecutorService newScheduledThreadPoolExecutor(final String name) {
        return new ScheduledThreadPoolExecutor(1, newThreadFactory(name));
    }

    /**
     * 创建单线程执行器。
     * <p>
     * 使用默认名称的单个工作线程。
     * </p>
     *
     * @return 单线程执行器
     */
    public static ExecutorService newSingleThreadExecutor() {
        return newSingleThreadExecutor("global-" + DEFAULT + "-single-pool");
    }

    /**
     * 创建单工作线程执行器（别名）。
     * <p>
      * 此方法实际调用 新单个thread执行器。
     * </p>
     *
     * @return 单线程执行器
     */
    public static ExecutorService newSingleWorkThreadExecutor() {
        return newSingleThreadExecutor("global-" + DEFAULT + "-single-pool");
    }

    /**
     * 创建指定名称的单工作线程执行器。
     * <p>
      * 委托给 新单个thread执行器。
     * </p>
     *
     * @param name 线程名称前缀
     * @return 单线程执行器
     */
    public static ExecutorService newSingleWorkThreadExecutor(String name) {
        return newSingleThreadExecutor(name);
    }

    /**
     * 创建指定名称的单线程执行器。
     * <p>
     * 核心线程 1，最大线程 1，空闲不回收，无界队列。
     * </p>
     *
     * @param name 线程名称前缀
     * @return 单线程执行器
     */
    public static ExecutorService newSingleThreadExecutor(String name) {
        return new ThreadPoolExecutor(
                SINGLETON,
                SINGLETON,
                KEEP_ALIVE_TIME,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                newThreadFactory(name)
        );
    }


    /**
     * 创建指定名称的虚拟线程执行器。
     * <p>
     * 每个任务启动一条新的虚拟线程。
     * </p>
     *
     * @param name 虚拟线程名称
     * @return 虚拟线程执行器
     */
    public static ExecutorService newVirtualThreadPerTaskExecutor(String name) {
        var factory = Thread.ofVirtual()
                .name(name)
                .factory();
        return newThreadPerTaskExecutor(factory);
    }

    /**
     * 使用指定线程工厂创建单线程执行器。
     * <p>
     * 核心线程 1，最大线程 1，无界队列。
     * </p>
     *
     * @param threadFactory 线程工厂
     * @return 单线程执行器
     */
    public static ExecutorService newSingleThreadExecutor(ThreadFactory threadFactory) {
        return new ThreadPoolExecutor(
                SINGLETON,
                SINGLETON,
                KEEP_ALIVE_TIME,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                threadFactory
        );
    }

    /**
     * 创建新线程。
     * <p>
     * 使用默认名称的直接线程封装。
     * </p>
     *
     * @param runnable 待执行的任务
     * @return 新线程
     */
    public static Thread newThread(final Runnable runnable) {
        return new Thread(runnable);
    }

    /**
     * 创建指定名称的新线程。
     * <p>
     * 设置线程名称以便于调试。
     * </p>
     *
     * @param runnable 待执行的任务
     * @param name     线程名称
     * @return 新线程
     */
    public static Thread newThread(final Runnable runnable, final String name) {
        var thread = newThread(runnable);
        thread.setName(name);
        return thread;
    }

    /**
     * 获取虚拟线程构建器。
     * <p>
      * 返回 Thread.的虚拟() 以便自定义创建虚拟线程。
     * </p>
     *
     * @return 虚拟线程构建器
     */
    public static Thread.Builder.OfVirtual ofVirtual() {
        return Thread.ofVirtual();
    }

    /**
     * 获取平台线程构建器。
     * <p>
      * 返回 Thread.的platform() 以便自定义创建平台线程。
     * </p>
     *
     * @return 平台线程构建器
     */
    public static Thread.Builder.OfPlatform ofPlatform() {
        return Thread.ofPlatform();
    }
    /**
     * 启动指定名称的虚拟线程。
     * <p>
      * 创建后直接 启动()，并记录调用者信息到 调试 日志。
     * </p>
     *
     * @param name     虚拟线程名称
     * @param runnable 待执行的任务
     * @return 已启动的虚拟线程
     */
    public static Thread startVirtualThread(String name, Runnable runnable) {
        var stackTrace = Thread.currentThread().getStackTrace();
        var caller = stackTrace.length > 2 ? stackTrace[2].toString() : "unknown";
        if (log.isDebugEnabled()) {
            log.debug("启动虚拟线程 [{}], 调用来源: {}", name, caller);
        }
        return Thread.ofVirtual()
                .name(name)
                .start(runnable);
    }

    /**
     * 创建指定名称的虚拟线程（未启动）。
     * <p>
      * 使用 unstarted() 创建，需手动调用 启动() 启动。
     * </p>
     *
     * @param name     虚拟线程名称
     * @param runnable 待执行的任务
     * @return 未启动的虚拟线程
     */
    public static Thread newVirtualThread(String name, Runnable runnable) {
        var stackTrace = Thread.currentThread().getStackTrace();
        var caller = stackTrace.length > 2 ? stackTrace[2].toString() : "unknown";
        if (log.isDebugEnabled()) {
            log.debug("创建虚拟线程 [{}], 调用来源: {}", name, caller);
        }
        return Thread.ofVirtual()
                .name(name)
                .unstarted(runnable);
    }

    /**
     * 创建线程工厂。
     * <p>
      * 使用 默认thread工厂 实现，索引从 0 开始。
     * </p>
     *
     * @param name 线程名称前缀
     * @return 线程工厂
     */
    public static ThreadFactory newThreadFactory(final String name) {
        return newThreadFactory(name, 0);
    }

    /**
     * 创建线程工厂（指定池索引）。
     * <p>
      * 使用 默认thread工厂，从指定索引开始计数。
     * </p>
     *
     * @param name  线程名称前缀
     * @param index 池索引起始值
     * @return 线程工厂
     */
    public static ThreadFactory newThreadFactory(final String name, final int index) {
        return new DefaultThreadFactory(name, index);
    }

    /**
     * 创建守护线程工厂。
     * <p>
      * 使用 名称thread工厂 实现，创建的线程均为守护线程。
     * </p>
     *
     * @param name 线程名称前缀
     * @return 守护线程工厂
     */
    public static ThreadFactory newDaemonThreadFactory(final String name) {
        return new NamedThreadFactory(name, true);
    }

    /**
     * 创建带名称的缓存线程池（守护线程）。
     * <p>
      * 核心线程 0，最大 Integer.最大_值，空闲 60s 回收，线程为守护线程。
     * </p>
     *
     * @param name 线程名称前缀
     * @return 守护型缓存线程池
     */
    public static ExecutorService newDaemonCachedThreadPool(final String name) {
        return newCachedThreadPool(newDaemonThreadFactory(name));
    }

    /**
     * 创建固定大小线程池（守护线程）。
     * <p>
      * 核心线程数和最大线程数均为 nthreads，线程为守护线程。
     * </p>
     *
     * @param nThreads 线程数
     * @param name     线程名称前缀
     * @return 守护型固定大小线程池
     */
    public static ExecutorService newDaemonFixedThreadPool(int nThreads, String name) {
        return newFixedThreadPool(nThreads, newDaemonThreadFactory(name));
    }

    /**
     * 创建守护单线程执行器。
     * <p>
     * 单工作线程，线程为守护线程。
     * </p>
     *
     * @param name 线程名称前缀
     * @return 守护型单线程执行器
     */
    public static ExecutorService newDaemonSingleThreadExecutor(String name) {
        return newSingleThreadExecutor(newDaemonThreadFactory(name));
    }

    /**
     * 创建守护单线程定时任务执行器。
     * <p>
     * 单工作线程，线程为守护线程。
     * </p>
     *
     * @param name 线程名称前缀
     * @return 守护型单线程定时任务执行器
     */
    public static ScheduledExecutorService newDaemonSingleThreadScheduledExecutor(String name) {
        return newSingleThreadScheduledExecutor(newDaemonThreadFactory(name));
    }

    /**
     * 获取推荐处理器线程数。
     * <p>
      * 计算公式：可用处理器 × 2 - 1，用于 I/O 密集型场景。
     * </p>
     *
     * @return 推荐线程数
     */
    public static int processor() {
        return Runtime.getRuntime().availableProcessors() * 2 - 1;
    }

    /**
     * 立即关闭线程池。
     * <p>
      * 调用 执行器服务.关闭now() 尝试停止所有正在执行的任务。
     * </p>
     *
     * @param executor 待关闭的执行器
     */
    public static void shutdownNow(final Executor executor) {
        if (executor instanceof ExecutorService executorService) {
            executorService.shutdownNow();
        }
    }

    /**
     * 线程休眠（不捕获异常）。
     * <p>
      * 可能会抛出 interrupted异常，由调用方处理。
     * </p>
     *
     * @param millis 休眠毫秒数
     * @throws InterruptedException 线程被中断
     */
    public static void sleepOfUnSafe(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    /**
     * 线程休眠（安静模式）。
     * <p>
      * 捕获 interrupted异常 并忽略，不恢复中断状态。
     * </p>
     *
     * @param millis 休眠毫秒数
     */
    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            // 忽略中断异常
        }
    }

    /**
     * 线程休眠（中断时抛运行时异常）。
     * <p>
      * 捕获 interrupted异常 后恢复中断状态并抛出 runtime异常。
     * </p>
     *
     * @param millis 休眠毫秒数
     * @throws RuntimeException 线程被中断
     */
    public static void sleepOfInterrupt(int millis) {
        try {
            sleepOfUnSafe(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /**
     * 线程休眠（指定时间单位，不捕获异常）。
     * <p>
      * 可能抛出 interrupted异常，由调用方处理。
     * </p>
     *
     * @param time     休眠时长
     * @param timeUnit 时间单位
     * @throws InterruptedException 线程被中断
     */
    public static void sleep(long time, TimeUnit timeUnit) throws InterruptedException {
        var unit = timeUnit.toMillis(time);
        ThreadUtils.sleep(unit);
    }

    /**
     * 安静休眠指定毫秒数。
     * <p>
      * 捕获 interrupted异常 并忽略。
     * </p>
     *
     * @param millis 休眠毫秒数
     */
    public static void sleepMillisecondsQuietly(long millis) {
        sleepQuietly(millis, TimeUnit.MILLISECONDS);
    }

    /**
     * 安静休眠指定时间。
     * <p>
     * 时间为负数时直接返回，不抛出异常。
     * </p>
     *
     * @param time     休眠时长
     * @param timeUnit 时间单位
     */
    public static void sleepQuietly(long time, TimeUnit timeUnit) {
        if (time < 0L) {
            return;
        }
        try {
            var unit = timeUnit.toMillis(time);
            Thread.sleep(unit);
        } catch (InterruptedException ignored) {
            // 忽略中断异常
        }
    }

    /**
     * 安静休眠指定秒数。
     * <p>
      * 委托给 sleepquietly。
     * </p>
     *
     * @param time 休眠秒数
     */
    public static void sleepSecondsQuietly(long time) {
        sleepQuietly(time, TimeUnit.SECONDS);
    }

    /**
     * 安静休眠指定分钟数。
     * <p>
      * 委托给 sleepquietly。
     * </p>
     *
     * @param time 休眠分钟数
     */
    public static void sleepMinutesQuietly(long time) {
        sleepQuietly(time, TimeUnit.MINUTES);
    }

    /**
     * 获取全局静态默认线程池。
     * <p>
      * 返回类级别的虚拟线程池 THREAD_游泳池。
     * </p>
     *
     * @return 全局线程池
     */
    public static ExecutorService newStaticThreadPool() {
        return THREAD_POOL;
    }

    /**
     * 获取全局静态定时任务线程池。
     * <p>
      * 返回类级别的 调度_执行器_服务。
     * </p>
     *
     * @return 全局定时任务线程池
     */
    public static ScheduledExecutorService newStaticScheduledThreadPoolExecutor() {
        return SCHEDULED_EXECUTOR_SERVICE;
    }

    /**
     * 注册 JVM 关闭钩子。
     * <p>
     * 将 Runnable 包装为守护线程后添加到 JVM 关闭钩子。
     * </p>
     *
     * @param runnable JVM 关闭时要执行的任务
     */
    public static void addShutdownHook(Runnable runnable) {
        var thread = ThreadUtils.newThread(runnable);
        thread.setDaemon(true);
        Runtime.getRuntime().addShutdownHook(thread);
    }

    /**
     * 创建单线程定时任务执行器（委托模式）。
     * <p>
      * 返回 delegated调度执行器服务 包装的 调度thread游泳池执行器。
     * </p>
     *
     * @param threadFactory 线程工厂
     * @return 单线程定时任务执行器
     */
    public static ScheduledExecutorService newSingleThreadScheduledExecutor(ThreadFactory threadFactory) {
        return new DelegatedScheduledExecutorServiceImpl(new ScheduledThreadPoolExecutor(1, threadFactory));
    }

    /**
     * 创建默认单线程定时任务执行器。
     * <p>
      * 使用 名称thread工厂("调度") 作为线程工厂。
     * </p>
     *
     * @return 单线程定时任务执行器
     */
    public static ScheduledExecutorService newSingleThreadScheduledExecutor() {
        return newSingleThreadScheduledExecutor(new NamedThreadFactory("schedule"));
    }

    /**
     * 获取默认全局线程池。
     * <p>
      * 返回 新静态thread游泳池() 的结果。
     * </p>
     *
     * @return 默认线程池
     */
    public static Executor getDefaultThreadPool() {
        return newStaticThreadPool();
    }

    /**
     * 在默认线程池中执行任务。
     * <p>
      * 提交任务到 新静态thread游泳池()。
     * </p>
     *
     * @param runnable 待执行的任务
     */
    public static void execute(Runnable runnable) {
        newStaticThreadPool().execute(runnable);
    }

    /**
     * 同步等待对象。
     * <p>
      * 在 同步 块中调用 obj.wait() 等待，忽略中断。
     * </p>
     *
     * @param obj 要等待的对象
     */
    public static void sync(Object obj) {
        synchronized (obj) {
            try {
                obj.wait();
            } catch (InterruptedException ignored) {
                // 忽略中断异常
            }
        }
    }

    /**
      * 创建定时任务线程池（使用 名称thread工厂）。
     * <p>
      * 使用 名称thread工厂 作为线程工厂。
     * </p>
     *
     * @param numThreads 核心线程数
     * @param name       线程名称前缀
     * @return 定时任务线程池
     */
    public static ScheduledExecutorService newScheduledThreadPool(int numThreads, String name) {
        return new ScheduledThreadPoolExecutor(numThreads, new NamedThreadFactory(name));
    }

    /**
     * 创建默认虚拟线程执行器。
     * <p>
      * 使用 JDK 21 的 执行器.新虚拟threadper任务执行器()。
     * </p>
     *
     * @return 虚拟线程执行器
     */
    public static ExecutorService newVirtualThreadPerTaskExecutor() {
        return newThreadPerTaskExecutor(Thread.ofVirtual().factory());
    }




    /**
      * 委托模式的定时任务执行器，将 调度执行器服务 的方法委托给内部实例。
     * @author CH
     * @since 4.0.0
     */
    @SuppressWarnings("ALL")
    static class DelegatedScheduledExecutorServiceImpl
            extends DelegatedExecutorService
            implements ScheduledExecutorService {
        /** E */
        private final ScheduledExecutorService e;
        DelegatedScheduledExecutorServiceImpl(ScheduledExecutorService executor) {
            super(executor);
            e = executor;
        }
        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return e.schedule(command, delay, unit);
        }
        @Override
        /** 调度 */
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            return e.schedule(callable, delay, unit);
        }
        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            return e.scheduleAtFixedRate(command, initialDelay, period, unit);
        }
        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            return e.scheduleWithFixedDelay(command, initialDelay, delay, unit);
        }
    }


    /**
      * 委托模式的执行器，将 执行器服务 的方法委托给内部实例。
     * @author CH
     * @since 4.0.0
     */
    static class DelegatedExecutorService extends AbstractExecutorService {
        /** E */
        private final ExecutorService e;
        DelegatedExecutorService(ExecutorService executor) { e = executor; }
        @Override
        /** 执行 */
        public void execute(Runnable command) { e.execute(command); }
        @Override
        /** 关闭 */
        public void shutdown() { e.shutdown(); }
        @Override
        /** 关闭Now */
        public List<Runnable> shutdownNow() { return e.shutdownNow(); }
        @Override
        /** 是否关闭 */
        public boolean isShutdown() { return e.isShutdown(); }
        @Override
        /** 是否Terminated */
        public boolean isTerminated() { return e.isTerminated(); }
        @Override
        /** await终止 */
        public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            return e.awaitTermination(timeout, unit);
        }
        @Override
        public Future<?> submit(Runnable task) {
            return e.submit(task);
        }
        @Override
        /** 提交 */
        public <T> Future<T> submit(Callable<T> task) {
            return e.submit(task);
        }
        @Override
        /** 提交 */
        public <T> Future<T> submit(Runnable task, T result) {
            return e.submit(task, result);
        }
        @Override
        /** 调用全部 */
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
                throws InterruptedException {
            return e.invokeAll(tasks);
        }
        @Override
        /**
          * 调用全部
         * @param tasks 任务
         * @param timeout 超时
         * @param unit unit
         */
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                             long timeout, TimeUnit unit)
                throws InterruptedException {
            return e.invokeAll(tasks, timeout, unit);
        }
        @Override
        /** 调用任意 */
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
                throws InterruptedException, ExecutionException {
            return e.invokeAny(tasks);
        }
        @Override
        /**
          * 调用任意
         * @param tasks 任务
         * @param timeout 超时
         * @param unit unit
         */
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks,
                               long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return e.invokeAny(tasks, timeout, unit);
        }
    }
    /**
     * 默认线程工厂实现，支持命名前缀和线程编号。
     */
    public static final class DefaultThreadFactory implements ThreadFactory {
        /** 游泳池_数字 */
        private static final AtomicInteger POOL_NUMBER = new AtomicInteger(1);
        /**
         * 用户组
         */
        private final ThreadGroup group;
        /** 线程数字 */
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        /** 名称前缀 */
        private final String namePrefix;

        /**
         * 创建 默认thread工厂 实例
         *
         * @return 默认thread工厂的结果
         */
        public DefaultThreadFactory() {
            group = Thread.currentThread().getThreadGroup();
            namePrefix = "pool-" + POOL_NUMBER.getAndIncrement() + "-thread-";
        }

        /**
          * 创建 默认thread工厂 实例
         * @param name 名称
         * @return 默认thread工厂的结果
         */
        public DefaultThreadFactory(String name) {
            group = Thread.currentThread().getThreadGroup();
            namePrefix = name + "-" + POOL_NUMBER.getAndIncrement() + "-";
        }

        /**
          * 创建 默认thread工厂 实例
         * @param name 名称
         * @param index int
         * @param index 索引
         * @return 默认thread工厂的结果
         */
        public DefaultThreadFactory(String name, int index) {
            POOL_NUMBER.set(index);
            group = Thread.currentThread().getThreadGroup();
            namePrefix = name + "-" + POOL_NUMBER.getAndIncrement() + "-";
        }

        @Override
        /** 新thread */
        public Thread newThread(Runnable r) {
            Thread t = new Thread(group, r, namePrefix + threadNumber.getAndIncrement(), 0);
            if (t.isDaemon()) {
                t.setDaemon(false);
            }
            if (t.getPriority() != Thread.NORM_PRIORITY) {
                t.setPriority(Thread.NORM_PRIORITY);
            }
            return t;
        }
    }


    /**
     * 立即关闭线程池（空安全）。
     * <p>
      * 调用前检查 空，避免空指针。
     * </p>
     *
     * @param executorService 待关闭的线程池
     */
    public static void shutdownNow(ExecutorService executorService) {
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    /**
     * 优雅关闭线程池（空安全）。
     * <p>
      * 调用前检查 空，不再接受新任务，等待已有任务完成。
     * </p>
     *
     * @param executorService 待关闭的线程池
     */
    public static void shutdown(ExecutorService executorService) {
        if (executorService != null) {
            executorService.shutdown();
        }
    }



}
