package com.chua.deeplearning.support.agentscope.goal;

import java.util.Objects;
import java.util.function.Function;

/**
 * 目标循环：围绕一个会话目标反复推进，直到达成、失败、被取消或到达轮次上限。
 *
 * <p>这是「制定目标直到完成目标为止」的执行体。循环本身不关心模型怎么调、消息怎么落库——
 * 它只依赖一个 {@code turn} 回调（输入提示词，返回本轮回复与令牌消耗）和一个
 * {@link GoalEvaluator}（判断是否达成），因此可以脱离 AgentScope 被单元测试覆盖。
 *
 * <p><b>有界性</b>：{@code maxRounds} 是硬上限。达到上限仍未达成时判定为 FAILED 并写明原因，
 * 而不是无限重试——无限循环会把「目标没做完」变成「线程永远占着」，与仓库既有纪律相悖。
 *
 * <p><b>暂停语义</b>：轮次之间检查暂停状态；处于暂停时阻塞等待（可带超时），
 * 等待期间不发起任何模型调用。
 *
 * @author CH
 */
public final class GoalLoop {

    /**
     * 完成标记：模型回复中出现该标记即认为目标达成。
     */
    public static final String DONE_MARKER = "[[GOAL_DONE]]";

    /**
     * 失败标记：模型回复中出现该标记即认为目标无法完成。
     */
    public static final String FAIL_MARKER = "[[GOAL_FAILED]]";

    /**
     * 工具类构造器。
     */
    private GoalLoop() {
    }

    /**
     * 一轮执行的结果。
     *
     * @param reply  模型本轮回复文本（不能为 null）
     * @param tokens 本轮消耗的令牌数（非负）
     */
    public record TurnResult(String reply, long tokens) {

        /**
         * 紧凑构造器：回复不可为 null，令牌不可为负。
         */
        public TurnResult {
            Objects.requireNonNull(reply, "reply 不能为 null");
            if (tokens < 0L) {
                throw new IllegalArgumentException("tokens 不能为负: " + tokens);
            }
        }
    }

    /**
     * 目标判定结果。
     */
    public enum Outcome {

        /**
         * 尚未完成，继续下一轮。
         */
        CONTINUE,

        /**
         * 目标已达成。
         */
        COMPLETED,

        /**
         * 目标无法完成。
         */
        FAILED
    }

    /**
     * 判定结论。
     *
     * @param outcome 结论
     * @param reason  说明文本，可为空
     */
    public record GoalDecision(Outcome outcome, String reason) {

        /**
         * 紧凑构造器：结论不可为 null。
         */
        public GoalDecision {
            Objects.requireNonNull(outcome, "outcome 不能为 null");
            reason = reason == null ? "" : reason;
        }

        /**
         * 继续下一轮。
         *
         * @param reason 说明
         * @return 结论
         */
        public static GoalDecision next(String reason) {
            return new GoalDecision(Outcome.CONTINUE, reason);
        }

        /**
         * 目标达成。
         *
         * @param reason 说明
         * @return 结论
         */
        public static GoalDecision done(String reason) {
            return new GoalDecision(Outcome.COMPLETED, reason);
        }

        /**
         * 目标失败。
         *
         * @param reason 说明
         * @return 结论
         */
        public static GoalDecision failed(String reason) {
            return new GoalDecision(Outcome.FAILED, reason);
        }
    }

    /**
     * 目标判定器。
     */
    @FunctionalInterface
    public interface GoalEvaluator {

        /**
         * 根据目标当前快照与本轮回复判定下一步。
         *
         * @param goal  目标快照（含轮次与令牌消耗）
         * @param reply 本轮模型回复
         * @return 判定结论
         */
        GoalDecision evaluate(SessionGoal goal, String reply);
    }

    /**
     * 基于标记的默认判定器：识别 {@link #DONE_MARKER} 与 {@link #FAIL_MARKER}。
     *
     * <p>之所以用「模型显式输出标记」而不是关键词匹配，是因为后者会被自然语言误命中
     * （例如模型复述「已完成」并不等于目标真的完成）。标记由提示词明确要求模型输出。
     */
    public static final class MarkerGoalEvaluator implements GoalEvaluator {

        @Override
        public GoalDecision evaluate(SessionGoal goal, String reply) {
            String text = reply == null ? "" : reply;
            if (text.contains(FAIL_MARKER)) {
                return GoalDecision.failed("模型报告无法完成目标");
            }
            if (text.contains(DONE_MARKER)) {
                return GoalDecision.done("模型报告目标已完成");
            }
            return GoalDecision.next("");
        }
    }

    /**
     * 驱动目标直至结束。
     *
     * @param tracker           目标状态机
     * @param turn              单轮执行回调：入参为本轮提示词，返回本轮结果
     * @param evaluator         目标判定器
     * @param maxRounds         轮次硬上限（至少 1）
     * @param pauseTimeoutMillis 单次暂停等待上限（毫秒，小于等于 0 表示不设上限）
     * @return 结束时的展示投影
     * @throws InterruptedException 暂停等待被中断时抛出；此时会把目标置为 CANCELED 并回填中断标记
     */
    public static GoalStatus run(GoalTracker tracker, Function<String, TurnResult> turn,
                                 GoalEvaluator evaluator, int maxRounds, long pauseTimeoutMillis)
            throws InterruptedException {
        Objects.requireNonNull(tracker, "tracker 不能为 null");
        Objects.requireNonNull(turn, "turn 不能为 null");
        Objects.requireNonNull(evaluator, "evaluator 不能为 null");
        if (maxRounds < 1) {
            throw new IllegalArgumentException("maxRounds 至少为 1: " + maxRounds);
        }

        long now = System.currentTimeMillis();
        tracker.start(now);
        for (int round = 1; round <= maxRounds; round++) {
            GoalStatus waiting = tracker.awaitResumable(pauseTimeoutMillis);
            if (waiting.state().terminal()) {
                return waiting;
            }
            String prompt = roundPrompt(tracker.goal(), round);
            TurnResult result;
            try {
                result = turn.apply(prompt);
            } catch (RuntimeException e) {
                return tracker.fail("执行异常: " + e.getClass().getSimpleName(), System.currentTimeMillis());
            }
            long stamp = System.currentTimeMillis();
            tracker.recordTokens(result.tokens(), stamp);
            tracker.recordRound(stamp);

            GoalDecision decision = evaluator.evaluate(tracker.goal(), result.reply());
            long decidedAt = System.currentTimeMillis();
            switch (decision.outcome()) {
                case COMPLETED:
                    return tracker.complete(decision.reason(), decidedAt);
                case FAILED:
                    return tracker.fail(decision.reason(), decidedAt);
                case CONTINUE:
                    break;
                default:
                    break;
            }
            if (tracker.status().state().terminal()) {
                return tracker.status();
            }
        }
        return tracker.fail("达到最大轮次 " + maxRounds + " 仍未完成目标", System.currentTimeMillis());
    }

    /**
     * 组装本轮提示词：目标 + 进度 + 标记要求。
     *
     * @param goal  目标快照
     * @param round 当前轮次
     * @return 提示词
     */
    public static String roundPrompt(SessionGoal goal, int round) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("你正在推进一个长期目标，请围绕它继续工作。\n\n");
        sb.append("【目标】").append(goal.title()).append('\n');
        if (!goal.detail().isBlank()) {
            sb.append("【说明】").append(goal.detail()).append('\n');
        }
        sb.append("【进度】第 ").append(round).append(" 轮，累计已用 ")
                .append(GoalStatus.formatTokens(goal.tokensUsed())).append('\n');
        sb.append("\n要求：本轮只推进目标中最关键的一步，完成后给出简短结论。\n");
        sb.append("若目标已彻底达成，请在回复最后单独一行输出 ").append(DONE_MARKER).append("；\n");
        sb.append("若你判断该目标无法完成，请改为单行输出 ").append(FAIL_MARKER).append("。\n");
        return sb.toString();
    }
}
