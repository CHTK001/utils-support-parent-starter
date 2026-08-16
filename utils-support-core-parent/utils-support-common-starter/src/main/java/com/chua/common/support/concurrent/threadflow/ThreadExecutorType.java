package com.chua.common.support.concurrent.threadflow;

/**
 * 线程执行器类型。
 *
 * <p>用于 {@link ThreadFlow} 选择底层执行模型：
 * <ul>
 *     <li>{@link #PLATFORM}：基于平台线程池（{@link java.util.concurrent.ThreadPoolExecutor}）</li>
 *     <li>{@link #VIRTUAL}：基于 JDK 21 虚拟线程（{@link Thread#ofVirtual()}）</li>
 *     <li>{@link #REACTIVE}：基于响应式流（{@link java.util.concurrent.Flow}）</li>
 *     <li>{@link #SYNC}：同步执行（调用线程直接运行）</li>
 * </ul>
 *
 * @author CH
 * @since 2026/08/15
 */
public enum ThreadExecutorType {

    /**
     * 平台线程池执行器
     */
    PLATFORM,

    /**
     * 虚拟线程执行器
     */
    VIRTUAL,

    /**
     * 响应式流执行器
     */
    REACTIVE,

    /**
     * 同步执行器（调用线程直接运行）
     */
    SYNC
}
