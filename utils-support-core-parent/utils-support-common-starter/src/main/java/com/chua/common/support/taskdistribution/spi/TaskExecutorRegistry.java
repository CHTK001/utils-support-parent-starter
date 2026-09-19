package com.chua.common.support.taskdistribution.spi;

import com.chua.common.support.taskdistribution.task.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务执行器注册中心。
 *
 * <p>按任务类型注册和管理执行器，支持查找和遍历。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TaskExecutorRegistry {

    /**
     * 执行器映射：任务类型 -> 任务执行器
     */
    private final Map<String, TaskExecutor<?>> executors = new ConcurrentHashMap<>();

    /**
     * 注册执行器。
     *
     * @param executor 执行器
     */
    public void register(TaskExecutor<?> executor) {
        if (executor != null && executor.taskType() != null) {
            executors.put(executor.taskType(), executor);
        }
    }

    /**
     * 注销执行器。
     *
     * @param taskType 任务类型
     */
    public void unregister(String taskType) {
        if (taskType != null) {
            executors.remove(taskType);
        }
    }

    /**
     * 查找执行器。
     *
     * @param taskType 任务类型
     * @return 执行器，不存在返回 空
     */
    public TaskExecutor<?> find(String taskType) {
        return executors.get(taskType);
    }

    /**
     * 根据任务查找执行器。
     *
     * @param task 任务
     * @return 匹配的执行器，无匹配返回 空
     */
    @SuppressWarnings("unchecked")
    public TaskExecutor<?> findExecutor(Task<?> task) {
        if (task == null) {
            return null;
        }
        TaskExecutor<Object> executor = (TaskExecutor<Object>) executors.get(task.getTaskType());
        if (executor != null && executor.canExecute((Task<Object>) task)) {
            return executor;
        }
        return null;
    }

    /**
     * 获取所有执行器。
     *
     * @return 执行器列表
     */
    public List<TaskExecutor<?>> all() {
        return new ArrayList<>(executors.values());
    }

    /**
     * 执行器数量。
     *
     * @return 数量
     */
    public int size() {
        return executors.size();
    }

    /**
     * 清空所有执行器。
     */
    public void clear() {
        executors.clear();
    }
}
