package com.chua.common.support.task.scheduler;

import com.chua.common.support.function.NamedThreadFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JDK 默认调度器提供者实现
 *
 * <p>基于 JDK 内置的 {@link ScheduledThreadPoolExecutor} 和虚拟线程（Virtual Thread）实现的
 * 调度服务提供者。是 {@link SchedulerProvider} 接口的默认实现。
 *
 * <p>架构设计：
 * <ul>
 *   <li><strong>定时调度层</strong>：使用 {@link ScheduledExecutorService} 管理定时任务的触发时间点，
 *   根据 {@link Trigger} 计算的下一次执行时间进行延迟调度</li>
 *   <li><strong>任务执行层</strong>：使用线程池异步执行每个触发点的任务逻辑，实现高并发执行</li>
 *   <li><strong>链式调度</strong>：每次任务执行完成后，自动计算下一次触发时间并重新调度，
 *   形成持续的任务执行链</li>
 * </ul>
 *
 * <p>工作流程：
 * <ol>
 *   <li>调用 {@link #schedule(String, Runnable, Trigger)} 注册任务</li>
 *   <li>计算触发器的下一次执行时间，计算当前时间到触发时间的延迟</li>
 *   <li>通过 {@link ScheduledExecutorService#schedule(Runnable, long, TimeUnit)} 在指定延迟后触发</li>
 *   <li>触发后，通过线程池异步执行任务逻辑</li>
 *   <li>执行完成后（或在 {@link CompletableFuture#whenComplete} 回调中），计算下一次触发时间并重复步骤 2</li>
 *   <li>如果任务被取消或调度器关闭，终止链式调度</li>
 * </ol>
 *
 * <p>线程安全：使用 {@link ReentrantLock} 保护任务注册表的并发修改，
 * 使用 {@link ConcurrentHashMap} 存储任务和 期货 映射。
 *
 * @author CH
 * @since 1.0.0
 */
public class JdkSchedulerProvider extends AbstractSchedulerProvider {

    /**
     * 定时任务调度器，用于按延迟时间触发任务
     */
    private final ScheduledExecutorService scheduler;

    /**
     * 线程池执行器，用于异步执行每个触发点的任务逻辑
     */
    private final ExecutorService virtualThreadExecutor;

    /**
     * 调度 期货 注册表（任务 标识 → 调度 期货）
     */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    /** 锁 */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 创建默认的 JDK 调度器提供者
     *
     * <p>核心线程数取 {@code max(2, CPU核心数)}。
     */
    public JdkSchedulerProvider() {
        this(Math.max(2, Runtime.getRuntime().availableProcessors()));
    }

    /**
     * 创建指定核心线程数的 JDK 调度器提供者
     *
     * @param corePoolSize 定时调度器核心线程数
     */
    public JdkSchedulerProvider(int corePoolSize) {
        this.scheduler = new ScheduledThreadPoolExecutor(corePoolSize, new NamedThreadFactory("scheduler"));
        this.virtualThreadExecutor = new ThreadPoolExecutor(
                corePoolSize, corePoolSize * 2,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new NamedThreadFactory("scheduler-exec"));
    }

    /**
     * 调度一个任务（指定任务 标识）
     *
     * <p>如果指定 ID 已存在调度任务，会先取消旧任务再注册新任务。
     * 注册完成后立即计算第一次触发时间并开始调度。
     *
     * @param id      任务唯一标识
     * @param task    待执行的任务逻辑
     * @param trigger 触发策略
     * @return 已调度的任务实例
     */
    @Override
    protected void doSchedule(String id, Runnable task, Trigger trigger) {
        lock.lock();
        try {
            scheduleNext(new ScheduledTask(id, task, trigger));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 调度任务的下一次执行
     *
     * <p>计算触发器的下一次执行时间，计算当前时间到触发时间的延迟毫秒数，
     * 通过 {@link ScheduledExecutorService#schedule(Runnable, long, TimeUnit)} 在指定延迟后触发。
     *
     * <p>触发后执行流程：
     * <ol>
     *   <li>检查任务是否已被取消或调度器是否已关闭</li>
     *   <li>通过虚拟线程执行器异步执行任务逻辑</li>
     *   <li>执行完成后（通过 {@link CompletableFuture#whenComplete} 回调），执行链式调度</li>
     * </ol>
     *
     * @param scheduledTask 需要调度下一次执行的调度任务
     */
    private void scheduleNext(ScheduledTask scheduledTask) {
        if (!running || scheduledTask.isCancelled()) {
            return;
        }

        LocalDateTime nextTime = scheduledTask.getTrigger().nextExecutionTime();
        if (nextTime == null) {
            return;
        }

        long delay = Duration.between(LocalDateTime.now(), nextTime).toMillis();
        if (delay < 0) {
            delay = 0;
        }

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            if (scheduledTask.isCancelled() || !running) {
                return;
            }

            CompletableFuture.runAsync(() -> {
                try {
                    scheduledTask.setCurrentThread(Thread.currentThread());
                    scheduledTask.getTask().run();
                } catch (Throwable ignored) {
                } finally {
                    scheduledTask.setCurrentThread(null);
                }
            }, virtualThreadExecutor).whenComplete((v, t) -> {
                if (!scheduledTask.isCancelled() && running) {
                    scheduleNext(scheduledTask);
                }
            });
        }, delay, TimeUnit.MILLISECONDS);

        futures.put(scheduledTask.getId(), future);
    }

    /**
     * 重新调度任务（实时变更触发策略）
     *
     * <p>取消当前未执行的 Future，更新任务的触发策略，并立即以新策略重新调度。
     *
     * @param id      任务唯一标识
     * @param trigger 新的触发策略
     * @return 重新调度后的任务实例，不存在返回 {@code null}
     */
    @Override
    protected void doReschedule(String id, Trigger trigger) {
        lock.lock();
        try {
            ScheduledFuture<?> existingFuture = futures.remove(id);
            if (existingFuture != null) {
                existingFuture.cancel(false);
            }
            scheduleNext(taskMap.get(id));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 取消指定 标识 的调度任务
     *
     * @param id 任务唯一标识
     * @return 如果存在该任务并成功取消返回 {@code true}，否则返回 {@code false}
     */
    @Override
    protected void doCancel(String id) {
        ScheduledFuture<?> future = futures.remove(id);
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * 关闭调度器
     *
     * <p>执行优雅关闭，操作顺序如下：
     * <ol>
     *   <li>标记运行状态为 {@code false}，阻止新的调度</li>
     *   <li>取消所有已调度的 Future</li>
     *   <li>清空任务注册表和 Future 注册表</li>
     *   <li>关闭虚拟线程执行器</li>
     *   <li>关闭定时调度器，等待 5 秒内完成正在执行的任务，超时则强制关闭</li>
     * </ol>
     */
    @Override
    protected void doShutdown() {
        lock.lock();
        try {
            for (ScheduledFuture<?> future : futures.values()) {
                future.cancel(false);
            }
            futures.clear();
        } finally {
            lock.unlock();
        }
        virtualThreadExecutor.shutdown();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            virtualThreadExecutor.shutdownNow();
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            virtualThreadExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
