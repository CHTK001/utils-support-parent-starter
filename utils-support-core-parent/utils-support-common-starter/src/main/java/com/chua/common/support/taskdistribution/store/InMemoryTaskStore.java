package com.chua.common.support.taskdistribution.store;

import com.chua.common.support.taskdistribution.task.Task;
import com.chua.common.support.taskdistribution.task.TaskResult;
import com.chua.common.support.taskdistribution.task.TaskStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存任务存储实现。
 *
 * <p>基于 ConcurrentHashMap，零外部依赖，进程重启后数据丢失。</p>
 *
 * @author CH
 * @since 4.0.0.42
*/
public class InMemoryTaskStore implements TaskStore {

    /**
    * 任务状态映射：任务id -> 任务entry
    */
    private final Map<String, TaskEntry> tasks = new ConcurrentHashMap<>();

    /**
    * 任务条目。
    * @author CH
    * @since 4.0.0
    */
    private static class TaskEntry {
        Task<?> task; // 任务
        TaskStatus status; // 状态
        TaskResult<?> result; // 结果

        TaskEntry(Task<?> task, TaskStatus status) {
            this.task = task;
            this.status = status;
        }
    }

    @Override
    /** 保存任务 */
    public void saveTask(Task<?> task, TaskStatus status) {
        if (task != null && task.getTaskId() != null) {
            tasks.compute(task.getTaskId(), (k, v) -> {
                if (v == null) {
                    return new TaskEntry(task, status);
                }
                v.task = task;
                v.status = status;
                return v;
            });
        }
    }

    @Override
    /** 更新状态 */
    public void updateStatus(String taskId, TaskStatus status) {
        TaskEntry entry = tasks.get(taskId);
        if (entry != null) {
            entry.status = status;
        }
    }

    @Override
    /** 保存结果 */
    public void saveResult(TaskResult<?> result) {
        if (result != null && result.getTaskId() != null) {
            TaskEntry entry = tasks.computeIfAbsent(result.getTaskId(),
                    k -> new TaskEntry(null, TaskStatus.PENDING));
            entry.result = result;
        }
    }

    @Override
    public Task<?> getTask(String taskId) {
        TaskEntry entry = tasks.get(taskId);
        return entry != null ? entry.task : null;
    }

    @Override
    /** 获取状态 */
    public TaskStatus getStatus(String taskId) {
        TaskEntry entry = tasks.get(taskId);
        return entry != null ? entry.status : null;
    }

    @Override
    public TaskResult<?> getResult(String taskId) {
        TaskEntry entry = tasks.get(taskId);
        return entry != null ? entry.result : null;
    }

    @Override
    public List<Task<?>> getRecoverableTasks() {
        List<Task<?>> result = new ArrayList<>();
        for (TaskEntry entry : tasks.values()) {
            if (entry.task != null && (entry.status == TaskStatus.PENDING || entry.status == TaskStatus.RUNNING)) {
                result.add(entry.task);
            }
        }
        return result;
    }

    @Override
    /** 移除任务 */
    public void removeTask(String taskId) {
        tasks.remove(taskId);
    }

    @Override
    /** Clear */
    public void clear() {
        tasks.clear();
    }

    @Override
    /** 关闭 */
    public void close() {
        tasks.clear();
    }
}
