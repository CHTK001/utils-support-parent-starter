package com.chua.deeplearning.support.agentscope.goal;

import java.util.Objects;

/**
 * 会话目标（Issue）的不可变快照。
 *
 * <p>本类型是值载体：字段一经构造不再变化，所有「推进」操作（改状态、加令牌、加轮次）
 * 都返回新的实例，因此可以安全地在多线程之间传递，也便于把某一时刻的状态落库或推送给前端。
 *
 * <p>时间字段用毫秒时间戳（{@code long}）而非 {@link java.time.Instant}，
 * 与仓库内既有实体的时间口径保持一致，避免序列化格式分叉。
 *
 * @param id        目标标识（必填）
 * @param sessionId 所属会话标识（必填）
 * @param title     目标标题，用于输入框上方的目标条展示（必填）
 * @param detail    目标补充说明，可为空字符串但不可为 null
 * @param state     当前状态（必填）
 * @param tokensUsed 该目标累计消耗的令牌数
 * @param rounds    已执行的轮次
 * @param createdAt 创建时间（毫秒时间戳）
 * @param updatedAt 最近一次变更时间（毫秒时间戳）
 * @author CH
 */
public record SessionGoal(String id, String sessionId, String title, String detail,
                          GoalState state, long tokensUsed, int rounds,
                          long createdAt, long updatedAt) {

    /**
     * 紧凑构造器：只对语义必填项做校验，{@code detail} 归一化为空字符串。
     *
     * <p>{@code tokensUsed} / {@code rounds} 不做下界校验——调用方若传入负数属于其自身错误，
     * 这里把业务校验前置成异常会把「数据异常」变成「500」，与仓库规约不符，
     * 故仅在 {@link GoalTracker#recordTokens(long, long)} 入口做非负约束。
     */
    public SessionGoal {
        Objects.requireNonNull(id, "id 不能为 null");
        Objects.requireNonNull(sessionId, "sessionId 不能为 null");
        Objects.requireNonNull(title, "title 不能为 null");
        Objects.requireNonNull(state, "state 不能为 null");
        detail = detail == null ? "" : detail;
    }

    /**
     * 创建一个新的目标（初始状态为 {@link GoalState#PENDING}）。
     *
     * @param id        目标标识
     * @param sessionId 所属会话标识
     * @param title     目标标题
     * @param detail    目标补充说明，可为 null
     * @param now       当前时间（毫秒时间戳）
     * @return 新建的目标
     */
    public static SessionGoal create(String id, String sessionId, String title, String detail, long now) {
        return new SessionGoal(id, sessionId, title, detail, GoalState.PENDING, 0L, 0, now, now);
    }

    /**
     * 迁移到新状态，并刷新更新时间。
     *
     * @param next 目标状态
     * @param now  当前时间（毫秒时间戳）
     * @return 迁移后的新目标
     */
    public SessionGoal withState(GoalState next, long now) {
        return new SessionGoal(id, sessionId, title, detail, next, tokensUsed, rounds, createdAt, now);
    }

    /**
     * 累加令牌消耗，并刷新更新时间。
     *
     * @param delta 本次增量（非负）
     * @param now   当前时间（毫秒时间戳）
     * @return 累加后的新目标
     */
    public SessionGoal plusTokens(long delta, long now) {
        return new SessionGoal(id, sessionId, title, detail, state, tokensUsed + delta, rounds, createdAt, now);
    }

    /**
     * 轮次加一并刷新更新时间。
     *
     * @param now 当前时间（毫秒时间戳）
     * @return 轮次加一后的新目标
     */
    public SessionGoal plusRound(long now) {
        return new SessionGoal(id, sessionId, title, detail, state, tokensUsed, rounds + 1, createdAt, now);
    }

    /**
     * 替换补充说明并刷新更新时间。
     *
     * @param nextDetail 新的补充说明，可为 null
     * @param now        当前时间（毫秒时间戳）
     * @return 替换后的新目标
     */
    public SessionGoal withDetail(String nextDetail, long now) {
        return new SessionGoal(id, sessionId, title, nextDetail, state, tokensUsed, rounds, createdAt, now);
    }
}
