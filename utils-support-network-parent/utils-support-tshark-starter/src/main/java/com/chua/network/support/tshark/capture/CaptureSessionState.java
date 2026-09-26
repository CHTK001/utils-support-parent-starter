package com.chua.network.support.tshark.capture;

/**
 * 抓包会话状态。
 *
 * <p>状态机：</p>
 * <pre>
 *   IDLE ──start──&gt; RUNNING ──pause──&gt; PAUSED ──resume──&gt; RUNNING
 *             │                     │
 *             └─────stop────────────┴──&gt; STOPPED
 * </pre>
 * <p>{@link #FAILED} 为终态之外的异常终态：抓包进程异常退出或 tshark 不可用时进入，
 * 携带失败原因供上层展示，不再自动重试——重试策略由调用方决定。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum CaptureSessionState {

    /**
     * 已创建但未启动
     */
    IDLE,

    /**
     * 采集中
     */
    RUNNING,

    /**
     * 已暂停，不再投递数据包但进程仍在
     */
    PAUSED,

    /**
     * 已正常停止
     */
    STOPPED,

    /**
     * 因异常终止
     */
    FAILED
}
