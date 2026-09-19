package com.chua.common.support.sync.executor;


/**
 * 数据中心执行器
 * <p>由同步流（SyncFlow）注入到 Sink 中，Sink 可通过它反向驱动消费流程。</p>
 *
 * <p>典型场景：Sink 收到新数据后调用 {@link #wakeup()} 唤醒消费线程，
 * 避免消费端空轮询。</p>
 *
 * @author CH
 * @since 2026/07/28
 */
public interface SinkExecutor {

    /**
     * 唤醒消费线程
     * <p>当 Sink 中有新数据到达时调用，通知消费端立即拉取。</p>
     */
    void wakeup();

    /**
     * 是否正在运行
     *
     * @return true 表示同步流处于运行状态
     */
    boolean isRunning();
}
