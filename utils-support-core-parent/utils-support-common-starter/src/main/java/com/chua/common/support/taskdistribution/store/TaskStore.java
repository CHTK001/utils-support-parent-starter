package com.chua.common.support.taskdistribution.store;

import com.chua.common.support.taskdistribution.task.Task;
import com.chua.common.support.taskdistribution.task.TaskResult;
import com.chua.common.support.taskdistribution.task.TaskStatus;

import java.util.List;

/**
 * 任务持久化存储接口。
 *
 * <p>提供任务、结果的状态持久化，支持应用重启后自动恢复任务。
 * 通过 SPI 实现扩展，默认内存实现，可扩展文件、数据库等。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface TaskStore extends AutoCloseable {

    /**
     * 存储任务。
     *
     * @param task   任务
     * @param status 状态
     */
    void saveTask(Task<?> task, TaskStatus status);

    /**
     * 更新任务状态。
     *
     * @param taskId 任务 标识
     * @param status 新状态
     */
    void updateStatus(String taskId, TaskStatus status);

    /**
     * 存储任务结果。
     *
     * @param result 执行结果
     */
    void saveResult(TaskResult<?> result);

    /**
     * 获取任务。
     *
     * @param taskId 任务 标识
     * @return 任务，不存在返回 空
     */
    Task<?> getTask(String taskId);

    /**
     * 获取任务状态。
     *
     * @param taskId 任务 标识
     * @return 状态，不存在返回 空
     */
    TaskStatus getStatus(String taskId);

    /**
     * 获取任务结果。
     *
     * @param taskId 任务 标识
     * @return 结果，不存在返回 空
     */
    TaskResult<?> getResult(String taskId);

    /**
     * 获取所有待恢复任务（PENDING / RUNNING 状态）。
     *
     * @return 待恢复任务列表
     */
    List<Task<?>> getRecoverableTasks();

    /**
     * 移除任务。
     *
     * @param taskId 任务 标识
     */
    void removeTask(String taskId);

    /**
     * 清空所有数据。
     */
    void clear();
}
