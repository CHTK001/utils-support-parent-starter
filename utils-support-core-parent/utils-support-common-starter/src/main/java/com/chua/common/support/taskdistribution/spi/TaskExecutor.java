package com.chua.common.support.taskdistribution.spi;

import com.chua.common.support.taskdistribution.task.Task;
import com.chua.common.support.taskdistribution.task.TaskResult;

/**
 * 工作端任务执行器 SPI。
 *
 * <p>业务模块实现此接口处理特定类型的任务。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface TaskExecutor<T> {

    /**
     * 获取支持的任务类型。
     *
     * @return 任务类型标识
     */
    String taskType();

    /**
     * 判断是否可执行该任务。
     *
     * @param task 任务
     * @return true 表示可执行
     */
    default boolean canExecute(Task<T> task) {
        return task != null && taskType().equals(task.getTaskType());
    }

    /**
     * 执行任务。
     *
     * @param task 任务
     * @return 执行结果
     */
    TaskResult<T> execute(Task<T> task);
}