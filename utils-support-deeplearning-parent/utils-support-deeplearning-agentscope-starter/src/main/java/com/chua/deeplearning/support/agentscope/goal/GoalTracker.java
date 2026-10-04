package com.chua.deeplearning.support.agentscope.goal;

import java.util.Objects;

/**
 * 会话目标的状态机：负责状态迁移、暂停/恢复、令牌与轮次计账。
 *
 * <p>本类是<b>线程安全</b>的：目标循环在工作线程推进，暂停/恢复可能来自 Web 请求线程，
 * 两者都通过同一把监视器锁串行化；{@link #awaitResumable(long)} 处于暂停时会释放锁等待，
 * 因此暂停期间不会占住 CPU、也不会再消耗模型调用。
 *
 * <p>越界迁移一律抛 {@link IllegalStateException}——目标状态是业务事实，
 * 静默纠正（例如把「已完成」再改回「进行中」）会让前端看到一个自相矛盾的状态。
 *
 * @author CH
 */
public final class GoalTracker {

    /**
     * 暂停等待的轮询上限：即使丢了一次 {@code notifyAll}，最多也只多等这么久。
     */
    private static final long WAIT_SLICE_MILLIS = 500L;

    private final Object lock = new Object();

    private final String reasonSeparator;

    private SessionGoal goal;

    private String lastReason = "";

    /**
     * @param goal 初始目标，不能为 null
     */
    public GoalTracker(SessionGoal goal) {
        this.goal = Objects.requireNonNull(goal, "goal 不能为 null");
        this.reasonSeparator = "; ";
    }

    /**
     * 当前目标快照。
     *
     * @return 目标快照
     */
    public synchronized SessionGoal goal() {
        return goal;
    }

    /**
     * 当前展示投影。
     *
     * @return 展示投影
     */
    public synchronized GoalStatus status() {
        return GoalStatus.from(goal);
    }

    /**
     * 最近一次状态变更的原因（无则为空串）。
     *
     * @return 原因文本
     */
    public synchronized String lastReason() {
        return lastReason;
    }

    /**
     * 启动目标（PENDING -&gt; RUNNING）。已在运行或暂停时不改变状态。
     *
     * @param now 当前时间（毫秒时间戳）
     * @return 迁移后的展示投影
     */
    public GoalStatus start(long now) {
        synchronized (lock) {
            if (goal.state() == GoalState.PENDING) {
                goal = goal.withState(GoalState.RUNNING, now);
                lastReason = "目标已启动";
            }
            return GoalStatus.from(goal);
        }
    }

    /**
     * 暂停目标（PENDING/RUNNING -&gt; PAUSED）。终态或已暂停时抛异常。
     *
     * @param now 当前时间（毫秒时间戳）
     * @return 迁移后的展示投影
     */
    public GoalStatus pause(long now) {
        synchronized (lock) {
            GoalState current = goal.state();
            if (current == GoalState.PAUSED) {
                return GoalStatus.from(goal);
            }
            requireActive(current, "暂停");
            goal = goal.withState(GoalState.PAUSED, now);
            lastReason = "用户暂停";
            lock.notifyAll();
            return GoalStatus.from(goal);
        }
    }

    /**
     * 恢复目标（PAUSED -&gt; RUNNING）。未处于暂停时抛异常。
     *
     * @param now 当前时间（毫秒时间戳）
     * @return 迁移后的展示投影
     */
    public GoalStatus resume(long now) {
        synchronized (lock) {
            if (goal.state() != GoalState.PAUSED) {
                throw new IllegalStateException("目标当前状态为 " + goal.state() + "，不可恢复");
            }
            goal = goal.withState(GoalState.RUNNING, now);
            lastReason = "用户恢复";
            lock.notifyAll();
            return GoalStatus.from(goal);
        }
    }

    /**
     * 目标达成（-&gt; COMPLETED）。终态时抛异常。
     *
     * @param reason 达成说明
     * @param now    当前时间（毫秒时间戳）
     * @return 迁移后的展示投影
     */
    public GoalStatus complete(String reason, long now) {
        return finish(GoalState.COMPLETED, reason, now, "达成目标");
    }

    /**
     * 目标失败（-&gt; FAILED）。终态时抛异常。
     *
     * @param reason 失败原因
     * @param now    当前时间（毫秒时间戳）
     * @return 迁移后的展示投影
     */
    public GoalStatus fail(String reason, long now) {
        return finish(GoalState.FAILED, reason, now, "执行失败");
    }

    /**
     * 取消目标（-&gt; CANCELED）。终态时抛异常。
     *
     * @param reason 取消原因
     * @param now    当前时间（毫秒时间戳）
     * @return 迁移后的展示投影
     */
    public GoalStatus cancel(String reason, long now) {
        return finish(GoalState.CANCELED, reason, now, "用户取消");
    }

    /**
     * 累加令牌消耗。
     *
     * @param delta 本次增量，必须非负
     * @param now   当前时间（毫秒时间戳）
     * @return 累加后的展示投影
     */
    public GoalStatus recordTokens(long delta, long now) {
        if (delta < 0L) {
            throw new IllegalArgumentException("令牌增量不能为负: " + delta);
        }
        synchronized (lock) {
            if (delta > 0L) {
                goal = goal.plusTokens(delta, now);
            }
            return GoalStatus.from(goal);
        }
    }

    /**
     * 轮次加一。
     *
     * @param now 当前时间（毫秒时间戳）
     * @return 加一后的展示投影
     */
    public GoalStatus recordRound(long now) {
        synchronized (lock) {
            goal = goal.plusRound(now);
            return GoalStatus.from(goal);
        }
    }

    /**
     * 若当前处于暂停，则阻塞等待恢复或超时；否则立即返回。
     *
     * <p>返回后调用方必须检查 {@link GoalStatus#state()}：仍为 PAUSED 说明是超时返回，
     * 已变 RUNNING 说明被恢复，已进入终态说明等待期间被取消/完成。
     *
     * @param timeoutMillis 最长等待毫秒数；小于等于 0 表示一直等
     * @return 等待结束时的展示投影
     * @throws InterruptedException 等待被中断时抛出，由调用方决定是否终止
     */
    public GoalStatus awaitResumable(long timeoutMillis) throws InterruptedException {
        synchronized (lock) {
            long deadline = timeoutMillis <= 0L ? Long.MAX_VALUE : System.currentTimeMillis() + timeoutMillis;
            while (goal.state() == GoalState.PAUSED) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    break;
                }
                lock.wait(Math.min(remaining, WAIT_SLICE_MILLIS));
            }
            return GoalStatus.from(goal);
        }
    }

    /**
     * 目标是否仍应继续推进。
     *
     * @return 未进入终态返回 true
     */
    public synchronized boolean shouldContinue() {
        return goal.state().active();
    }

    /**
     * 终态迁移的统一实现。
     *
     * @param target 目标状态
     * @param reason 原因文本
     * @param now    当前时间（毫秒时间戳）
     * @param fallback 原因为空时的兜底文案
     * @return 迁移后的展示投影
     */
    private GoalStatus finish(GoalState target, String reason, long now, String fallback) {
        synchronized (lock) {
            GoalState current = goal.state();
            if (current.terminal()) {
                throw new IllegalStateException("目标已处于终态 " + current + "，不可再次变更");
            }
            goal = goal.withState(target, now);
            String text = reason == null || reason.isBlank() ? fallback : reason;
            lastReason = lastReason.isEmpty() ? text : lastReason + reasonSeparator + text;
            lock.notifyAll();
            return GoalStatus.from(goal);
        }
    }

    /**
     * 断言当前状态可继续推进。
     *
     * @param current 当前状态
     * @param action  动作名（用于异常文案）
     */
    private static void requireActive(GoalState current, String action) {
        if (current.terminal()) {
            throw new IllegalStateException("目标已处于终态 " + current + "，不可" + action);
        }
    }
}
