package com.chua.runtime.support.model;

/**
 * 运行时实例的生命周期状态枚举。
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
     * 正在启动中
     */
    STARTING,

    /**
     * 运行中
     */
    RUNNING,

    /**
     * 正在停止中
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