package com.chua.common.support.concurrent.threadflow;

/**
 * ThreadFlow 生命周期事件回调协议。
 *
 * <p>通过 {@link ThreadFlow#listener(ThreadFlowListener)} 或
 * {@link ThreadExecutor#listener(ThreadFlowListener)} 注册，
 * 观察任务从提交到整体完成的全部阶段。</p>
 *
 * <ul>
 *     <li>{@link #onStart(ThreadExecutor)}：整体流程启动</li>
 *     <li>{@link #onTaskStart(int)}：单个任务开始</li>
 *     <li>{@link #onNext(int, Object)}：单个任务成功产出</li>
 *     <li>{@link #onError(int, Throwable)}：单个任务失败</li>
 *     <li>{@link #onProcess(int, int)}：进度上报（已完成 / 总数）</li>
 *     <li>{@link #onComplete(ThreadFlowResult)}：整体结束</li>
 * </ul>
 *
 * @author CH
 * @since 2026/08/15
 */
public interface ThreadFlowListener {

    /**
     * 整体流程启动回调。
     *
     * @param executor 当前执行器
     */
    default void onStart(ThreadExecutor<?> executor) {
    }

    /**
     * 单个任务开始回调。
     *
     * @param index 任务下标（从 0 开始）
     */
    default void onTaskStart(int index) {
    }

    /**
     * 单个任务成功回调。
     *
     * @param index  任务下标
     * @param result 任务返回值（无返回值任务为 null）
     */
    default void onNext(int index, Object result) {
    }

    /**
     * 单个任务失败回调。
     *
     * @param index 任务下标
     * @param error 异常
     */
    default void onError(int index, Throwable error) {
    }

    /**
     * 进度回调（每次任务结束后触发一次）。
     *
     * @param completed 已完成任务数
     * @param total     任务总数
     */
    default void onProcess(int completed, int total) {
    }

    /**
     * 整体完成回调。
     *
     * @param result 聚合结果
     */
    default void onComplete(ThreadFlowResult<?> result) {
    }
}