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

    /** taskMap */
    protected final Map<String, ScheduledTask> taskMap = new ConcurrentHashMap<>();
    /** running */
    protected volatile boolean running = true;

    @Override
    /** Schedule */
    public ScheduledTask schedule(Runnable task, Trigger trigger) {
        return schedule(UUID.randomUUID().toString(), task, trigger);
    }

    @Override
    /** Schedule */
    public ScheduledTask schedule(String id, Runnable task, Trigger trigger) {
        var scheduledTask = new ScheduledTask(id, task, trigger);
        taskMap.put(id, scheduledTask);
        doSchedule(id, task, trigger);
        return scheduledTask;
    }

    @Override
    /** Reschedule */
    public ScheduledTask reschedule(String id, Trigger trigger) {
        var task = taskMap.get(id);
        if (task == null) {
            return null;
        }
        task.updateTrigger(trigger);
        doReschedule(id, trigger);
        return task;
    }

    @Override
    /** Cancel */
    public boolean cancel(String id) {
        var task = taskMap.remove(id);
        if (task == null) {
            return false;
        }
        task.cancel();
        doCancel(id);
        return true;
    }

    @Override
    /** 是否Running */
    public boolean isRunning(String id) {
        var task = taskMap.get(id);
        return task != null && !task.isCancelled();
    }

    @Override
    /** 是否Running */
    public boolean isRunning() {
        return running;
    }

    @Override
    /** 获取ScheduledTasks */
    public List<ScheduledTask> getScheduledTasks() {
        return List.copyOf(taskMap.values());
    }

    @Override
    /** 关闭 */
    public void shutdown() {
        running = false;
        taskMap.values().forEach(ScheduledTask::cancel);
        taskMap.clear();
        doShutdown();
    }

    /** DoSchedule */
    protected abstract void doSchedule(String id, Runnable task, Trigger trigger);
    /** DoReschedule */
    protected abstract void doReschedule(String id, Trigger trigger);
    /** DoCancel */
    protected abstract void doCancel(String id);
    /** Do关闭 */
    protected abstract void doShutdown();
}
