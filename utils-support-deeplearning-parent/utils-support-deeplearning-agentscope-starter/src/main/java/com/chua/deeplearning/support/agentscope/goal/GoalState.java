package com.chua.deeplearning.support.agentscope.goal;

/**
 * 会话目标（Issue）的生命周期状态。
 *
 * <p>状态迁移只允许下面这些方向，越界迁移由 {@link GoalTracker} 拒绝：
 * <pre>
 *   PENDING --start--&gt; RUNNING &lt;--resume-- PAUSED
 *                         |  \--pause--&gt; /
 *                         |
 *                         +--complete--&gt; COMPLETED
 *                         +--fail------&gt; FAILED
 *                         +--cancel----&gt; CANCELED
 * </pre>
 *
 * <p>其中 COMPLETED / FAILED / CANCELED 为终态，一旦进入不可再迁移。
 *
 * @author CH
 */
public enum GoalState {

    /**
     * 已创建但尚未开始执行。
     */
    PENDING,

    /**
     * 正在执行中。
     */
    RUNNING,

    /**
     * 已被暂停，等待恢复；暂停期间目标循环阻塞而不消耗模型调用。
     */
    PAUSED,

    /**
     * 目标已达成。
     */
    COMPLETED,

    /**
     * 执行失败（模型判定无法完成，或达到轮次上限仍未完成）。
     */
    FAILED,

    /**
     * 被用户取消。
     */
    CANCELED;

    /**
     * 是否为终态。
     *
     * @return 终态返回 true，此后不再推进
     */
    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED;
    }

    /**
     * 是否仍可继续推进（未进入终态）。
     *
     * @return 可继续返回 true
     */
    public boolean active() {
        return !terminal();
    }
}
