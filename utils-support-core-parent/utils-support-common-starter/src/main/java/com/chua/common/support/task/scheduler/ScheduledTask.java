package com.chua.common.support.task.scheduler;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 已调度的任务
 *
 * <p>表示一个已被 {@link SchedulerProvider} 调度执行的任务实例。
 * 每个调度任务包含唯一的标识符、待执行的任务逻辑和触发策略。
 *
 * <p>通过该对象可以：
 * <ul>
 *   <li>获取任务的唯一标识和调度信息</li>
 *   <li>取消任务的后续执行</li>
 *   <li>检查任务是否已被取消</li>
 * </ul>
 *
 * <p>任务的生命周期：
 * <ol>
 *   <li>创建：由 {@link SchedulerProvider#schedule(String, Runnable, Trigger)} 创建</li>
 *   <li>运行：按照 Trigger 定义的时间点异步执行</li>
 *   <li>取消：调用 {@link #cancel()} 方法终止后续执行</li>
 *   <li>完成：被取消或调度器关闭时结束</li>
 * </ol>
 *
 * @author CH
 * @since 1.0.0
 */
public class ScheduledTask {

    /**
     * 任务唯一标识
     */
    private final String id;

    /**
     * 待执行的任务逻辑
     */
    private final Runnable task;

    /**
     * 触发策略
     */
    private volatile Trigger trigger;

    /**
     * 取消标记
     */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /**
     * 当前执行线程（用于中断正在执行的任务）
     */
    private volatile Thread currentThread;

    /**
     * 构造一个调度任务实例
     *
     * @param id      任务唯一标识
     * @param task    待执行的任务逻辑
     * @param trigger 触发策略
     */
    ScheduledTask(String id, Runnable task, Trigger trigger) {
        this.id = id;
        this.task = task;
        this.trigger = trigger;
    }

    /**
     * 获取任务唯一标识
     *
     * @return 任务 标识
     */
    public String getId() {
        return id;
    }

    /**
     * 获取待执行的任务逻辑
     *
     * @return 任务逻辑
     */
    public Runnable getTask() {
        return task;
    }

    /**
     * 获取触发策略
     *
     * @return 触发器
     */
    public Trigger getTrigger() {
        return trigger;
    }

    /**
     * 检查任务是否已被取消
     *
     * @return 如果任务已被取消返回 {@code true}，否则返回 {@code false}
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 取消任务
     *
     * <p>取消后任务将不再触发后续执行。如果任务当前正在执行，
     * 会中断执行线程。
     *
     * @return {@code true}
     */
    public boolean cancel() {
        cancelled.set(true);
        if (currentThread != null) {
            currentThread.interrupt();
        }
        return true;
    }

    /**
     * 更新触发策略（实时变更调度时间）
     *
     * <p>允许在任务运行过程中动态修改触发策略。调用后调度器会取消当前未执行的 Future，
     * 并使用新的触发策略重新计算下一次执行时间。
     *
     * @param newTrigger 新的触发策略
     */
    public void updateTrigger(Trigger newTrigger) {
        this.trigger = newTrigger;
    }

    /**
     * 设置当前执行线程
     *
     * <p>由调度框架在任务执行前调用，用于记录当前执行线程，
     * 以便在取消时中断任务执行。
     *
     * @param thread 当前执行线程
     */
    void setCurrentThread(Thread thread) {
        this.currentThread = thread;
    }
}