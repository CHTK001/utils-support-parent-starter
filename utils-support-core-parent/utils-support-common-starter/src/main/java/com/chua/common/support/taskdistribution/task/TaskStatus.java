package com.chua.common.support.taskdistribution.task;

/**
 * 任务状态枚举。
 *
 * <p>描述任务在整个生命周期中的流转状态：从创建到最终完成或被取消。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum TaskStatus {
    /**
     * 待执行：任务已创建，等待派发
     */
    PENDING,

    /**
     * 执行中：任务已派发到工作端，正在运行
     */
    RUNNING,

    /**
     * 执行成功：任务完成，结果已回传
     */
    SUCCESS,

    /**
     * 执行失败：任务异常终止
     */
    FAILED,

    /**
     * 执行超时：规定时间内未返回结果
     */
    TIMEOUT,

    /**
     * 已取消：任务被主动取消
     */
    CANCELLED,

    /**
     * 已暂停：服务端暂停派发，工作端可继续执行已有任务
     */
    PAUSED
}