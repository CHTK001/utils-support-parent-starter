package com.chua.common.support.ai.agent.goal;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.agent.AgentResponse;

import java.util.Objects;
import java.util.function.Function;

/**
 * Agent 目标能力的共享支撑：持有目标状态机与循环参数，并驱动 {@link GoalLoop}。
 *
 * <p>为什么需要它：{@link GoalTracker} 是<b>有状态</b>的对象，而 {@code Agent} 是接口，
 * 不能声明字段。三个 Agent 实现（agentscope / opencode-server / langchain4j）
 * 各自持有一个本类实例，并把自己真实的 {@code run(String)} 作为单轮执行体传进来，
 * 这样「目标状态机 + 循环 + 计账」只有一份实现，不会在三个类里各写一遍。
 *
 * <p>线程安全：目标状态由 {@link GoalTracker} 自身串行化；本类只保存不可变的循环参数，
 * 因此暂停/恢复可以安全地来自其它线程。
 *
 * @author CH
 * @since 4.0.0.51
 */
public final class AgentGoalSupport {

    /**
     * 默认轮次上限：足够推进一个多步骤任务，又不至于把线程长期占住。
     */
    public static final int DEFAULT_MAX_ROUNDS = 20;

    private volatile GoalTracker tracker;

    private volatile int maxRounds = DEFAULT_MAX_ROUNDS;

    private volatile long pauseTimeoutMillis = 0L;

    /**
     * 绑定一个新目标（为当前 Agent 新建状态机）。
     *
     * @param goal 目标快照，不能为 null
     * @return 当前支撑实例，便于链式调用
     */
    public AgentGoalSupport goal(SessionGoal goal) {
        this.tracker = new GoalTracker(Objects.requireNonNull(goal, "goal 不能为 null"));
        return this;
    }

    /**
     * 挂接一个已存在的目标状态机（通常来自 {@link GoalRegistry} 的会话级目标）。
     *
     * @param tracker 目标状态机，不能为 null
     * @return 当前支撑实例，便于链式调用
     */
    public AgentGoalSupport attach(GoalTracker tracker) {
        this.tracker = Objects.requireNonNull(tracker, "tracker 不能为 null");
        return this;
    }

    /**
     * 设置轮次硬上限。
     *
     * @param maxRounds 轮次上限，至少 1
     * @return 当前支撑实例，便于链式调用
     */
    public AgentGoalSupport maxRounds(int maxRounds) {
        if (maxRounds < 1) {
            throw new IllegalArgumentException("maxRounds 至少为 1: " + maxRounds);
        }
        this.maxRounds = maxRounds;
        return this;
    }

    /**
     * 设置单次暂停等待上限。
     *
     * @param pauseTimeoutMillis 毫秒；小于等于 0 表示一直等到恢复或被取消
     * @return 当前支撑实例，便于链式调用
     */
    public AgentGoalSupport pauseTimeout(long pauseTimeoutMillis) {
        this.pauseTimeoutMillis = pauseTimeoutMillis;
        return this;
    }

    /**
     * 是否已绑定目标。
     *
     * @return 已绑定返回 true
     */
    public boolean hasGoal() {
        return tracker != null;
    }

    /**
     * 当前目标状态。
     *
     * @return 展示投影；未绑定返回 null
     */
    public GoalStatus status() {
        GoalTracker current = this.tracker;
        return current == null ? null : current.status();
    }

    /**
     * 暂停当前目标。
     *
     * @return 暂停后的展示投影
     */
    public GoalStatus pause() {
        return requireGoal().pause(System.currentTimeMillis());
    }

    /**
     * 恢复当前目标。
     *
     * @return 恢复后的展示投影
     */
    public GoalStatus resume() {
        return requireGoal().resume(System.currentTimeMillis());
    }

    /**
     * 取消当前目标。
     *
     * @param reason 取消原因
     * @return 取消后的展示投影
     */
    public GoalStatus cancel(String reason) {
        return requireGoal().cancel(reason, System.currentTimeMillis());
    }

    /**
     * 围绕已绑定的目标反复推进，直到完成/失败/取消或到达轮次上限。
     *
     * @param turn 单轮执行体：入参为本轮提示词，返回本轮回复与令牌消耗
     * @return 结束时的展示投影
     */
    public GoalStatus run(Function<String, GoalLoop.TurnResult> turn) {
        GoalTracker current = requireGoal();
        try {
            return GoalLoop.run(current, turn, new GoalLoop.MarkerGoalEvaluator(),
                    maxRounds, pauseTimeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            try {
                return current.cancel("目标执行被中断", System.currentTimeMillis());
            } catch (IllegalStateException terminal) {
                return current.status();
            }
        }
    }

    /**
     * 从 {@link AgentResponse} 折算本轮令牌消耗；缺失时返回 0 而不是猜一个数。
     *
     * @param response Agent 响应，可为 null
     * @return 令牌数（未知为 0）
     */
    public static long tokensOf(AgentResponse response) {
        if (response == null || response.getUsage() == null) {
            return 0L;
        }
        AiUsage usage = response.getUsage();
        if (usage.getTotalTokens() != null) {
            return usage.getTotalTokens();
        }
        long input = usage.getInputTokens() == null ? 0L : usage.getInputTokens();
        long output = usage.getOutputTokens() == null ? 0L : usage.getOutputTokens();
        return input + output;
    }

    /**
     * 取已绑定的目标状态机，未绑定则抛异常。
     *
     * @return 目标状态机
     */
    private GoalTracker requireGoal() {
        GoalTracker current = this.tracker;
        if (current == null) {
            throw new IllegalStateException("未绑定目标，请先调用 goal(...) 或 attachGoal(...)");
        }
        return current;
    }
}
