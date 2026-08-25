package com.chua.common.support.task.scheduler;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调度器提供者抽象基类
 *
 * <p>提供共享的任务注册表管理和通用方法实现。
 * 子类只需实现核心调度逻辑：{@link #doSchedule(String, Runnable, Trigger)}、
 * {@link #doReschedule(String, Trigger)}、{@link #doCancel(String)}、{@link #doShutdown()}。
 *
 * @author CH
 * @since 1.0.0
 */
public abstract class AbstractSchedulerProvider implements SchedulerProvider {

    /** 任务注册表：taskId -> 调度任务 */
    protected final Map<String, ScheduledTask> taskMap = new ConcurrentHashMap<>();
    /** 调度器是否处于运行状态 */
    protected volatile boolean running = true;

    /**
     * 以随机 UUID 注册并调度任务。
     */
    @Override
    public ScheduledTask schedule(Runnable task, Trigger trigger) {
        return schedule(UUID.randomUUID().toString(), task, trigger);
    }

    /**
     * 以指定 ID 注册并调度任务：登记到任务表后委托 {@link #doSchedule}。
     */
    @Override
    public ScheduledTask schedule(String id, Runnable task, Trigger trigger) {
        var scheduledTask = new ScheduledTask(id, task, trigger);
        taskMap.put(id, scheduledTask);
        doSchedule(id, task, trigger);
        return scheduledTask;
    }

    /**
     * 更新指定任务的触发器；任务不存在时返回 null（调用方自行判定）。
     */
    @Override
    public ScheduledTask reschedule(String id, Trigger trigger) {
        var task = taskMap.get(id);
        if (task == null) {
            return null;
        }
        task.updateTrigger(trigger);
        doReschedule(id, trigger);
        return task;
    }

    /**
     * 取消并移除指定任务；任务不存在返回 false。
     */
    @Override
    public boolean cancel(String id) {
        var task = taskMap.remove(id);
        if (task == null) {
            return false;
        }
        task.cancel();
        doCancel(id);
        return true;
    }

    /**
     * 判断指定任务是否处于运行中（已注册且未取消）。
     */
    @Override
    public boolean isRunning(String id) {
        var task = taskMap.get(id);
        return task != null && !task.isCancelled();
    }

    /**
     * 判断调度器整体是否运行中。
     */
    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 获取全部已注册任务的快照列表。
     */
    @Override
    public List<ScheduledTask> getScheduledTasks() {
        return List.copyOf(taskMap.values());
    }

    /**
     * 关闭调度器：置位状态、取消并清空任务表后委托 {@link #doShutdown}。
     */
    @Override
    public void shutdown() {
        running = false;
        taskMap.values().forEach(ScheduledTask::cancel);
        taskMap.clear();
        doShutdown();
    }

    /**
     * 执行具体调度：将任务按触发器交给底层调度设施，由子类实现。
     *
     * @param id      任务 ID
     * @param task    业务逻辑
     * @param trigger 触发器
     */
    protected abstract void doSchedule(String id, Runnable task, Trigger trigger);

    /**
     * 重新调度指定任务（触发器已更新），由子类实现。
     *
     * @param id      任务 ID
     * @param trigger 新触发器
     */
    protected abstract void doReschedule(String id, Trigger trigger);

    /**
     * 取消底层设施中的指定任务，由子类实现。
     *
     * @param id 任务 ID
     */
    protected abstract void doCancel(String id);

    /**
     * 释放底层调度资源（线程池等），由子类实现。
     */
    protected abstract void doShutdown();
}
