package com.chua.runtime.core.model;

/**
 * 运行时实例的生命周期状态。
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum RuntimeStatus {

    /**
     * 已注册但未启动
     */
    STOPPED,

    /**
     * 正在启动
     */
    STARTING,

    /**
     * 运行中
     */
    RUNNING,

    /**
     * 正在停止
     */
    STOPPING,

    /**
     * 进程异常退出
     */
    CRASHED,

    /**
     * 启动超时
     */
    TIMEOUT,

    /**
     * 未知状态
     */
    UNKNOWN
}