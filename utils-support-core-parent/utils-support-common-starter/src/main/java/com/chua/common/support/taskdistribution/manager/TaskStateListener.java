package com.chua.common.support.taskdistribution.manager;

import com.chua.common.support.taskdistribution.task.TaskResult;
import com.chua.common.support.taskdistribution.task.TaskStatus;

/**
* 任务状态变更监听器。
*
* <p>当任务状态发生变化时触发回调，支持 UI 响应式更新和日志跟踪。</p>
*
* @author CH
* @since 4.0.0.42
 */
public interface TaskStateListener {

    /**
    * 任务状态变更回调。
    *
    * @param taskId   任务 标识
    * @param oldState 旧状态
    * @param newState 新状态
     */
    default void onStateChanged(String taskId, TaskStatus oldState, TaskStatus newState) {
    }

    /**
    * 任务完成回调（成功 / 失败）。
    *
    * @param result 执行结果
     */
    default void onCompleted(TaskResult<?> result) {
    }
}