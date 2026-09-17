package com.chua.common.support.taskdistribution.task;

/**
 * 任务结果回调接口。
 *
 * <p>纯响应式设计，发布者通过此接口异步接收任务执行结果。</p>
 *
 * @author CH
 * @since 4.0.0.42
*/
public interface TaskCallback {

    /**
    * 任务执行成功回调。
    *
    * @param result 执行结果
    */
    default void onResult(TaskResult<?> result) {
    }

    /**
    * 任务执行失败回调。
    *
    * @param taskId   任务 标识
    * @param error    错误信息
    */
    default void onError(String taskId, String error) {
    }

    /**
    * 任务执行超时回调。
    *
    * @param taskId 任务 标识
    */
    default void onTimeout(String taskId) {
    }

    /**
    * 任务进度更新回调。
    *
    * @param taskId    任务 标识
    * @param progress  进度百分比（0-100）
    * @param message   进度描述
    */
    default void onProgress(String taskId, int progress, String message) {
    }
}
