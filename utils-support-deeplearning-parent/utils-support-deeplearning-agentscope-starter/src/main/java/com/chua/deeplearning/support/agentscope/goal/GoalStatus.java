package com.chua.deeplearning.support.agentscope.goal;

import java.util.Locale;

/**
 * 目标状态的展示投影，供输入框上方的目标条使用。
 *
 * <p>把「给机器看的枚举 + 数字」翻译成「给人看的标签」集中在这里，前端只做渲染，
 * 不在前端再各写一套状态文案；状态文案与令牌格式因此只有一个出处。
 *
 * @param id         目标标识
 * @param title      目标标题
 * @param detail     目标补充说明
 * @param state      当前状态
 * @param tokensUsed 累计令牌数
 * @param rounds     已执行轮次
 * @param updatedAt  最近更新时间（毫秒时间戳）
 * @author CH
 */
public record GoalStatus(String id, String title, String detail, GoalState state,
                         long tokensUsed, int rounds, long updatedAt) {

    /**
     * 由目标快照生成展示投影。
     *
     * @param goal 目标快照，不能为 null
     * @return 展示投影
     */
    public static GoalStatus from(SessionGoal goal) {
        if (goal == null) {
            throw new IllegalArgumentException("goal 不能为 null");
        }
        return new GoalStatus(goal.id(), goal.title(), goal.detail(), goal.state(),
                goal.tokensUsed(), goal.rounds(), goal.updatedAt());
    }

    /**
     * 状态中文标签。
     *
     * @return 如「进行中」「已暂停」「已完成」
     */
    public String stateLabel() {
        return switch (state) {
            case PENDING -> "待开始";
            case RUNNING -> "进行中";
            case PAUSED -> "已暂停";
            case COMPLETED -> "已完成";
            case FAILED -> "失败";
            case CANCELED -> "已取消";
        };
    }

    /**
     * 令牌数的可读格式，如 {@code 717K 令牌}。
     *
     * @return 令牌标签
     */
    public String tokensLabel() {
        return formatTokens(tokensUsed);
    }

    /**
     * 目标条一行摘要：标题 + 状态 + 令牌。
     *
     * @return 一行文本
     */
    public String summary() {
        return title + " · " + stateLabel() + " · " + tokensLabel();
    }

    /**
     * 当前是否处于可暂停状态（运行中才允许暂停）。
     *
     * @return 可暂停返回 true
     */
    public boolean pausable() {
        return state == GoalState.RUNNING || state == GoalState.PENDING;
    }

    /**
     * 当前是否处于可恢复状态（暂停才允许恢复）。
     *
     * @return 可恢复返回 true
     */
    public boolean resumable() {
        return state == GoalState.PAUSED;
    }

    /**
     * 把令牌数格式化成带 K / M 单位的可读文本。
     *
     * @param tokens 令牌数
     * @return 可读文本，如 {@code 717K 令牌} / {@code 1.2M 令牌} / {@code 512 令牌}
     */
    static String formatTokens(long tokens) {
        if (tokens < 1000L) {
            return tokens + " 令牌";
        }
        if (tokens < 1_000_000L) {
            return trimTrailingZero(tokens / 1000.0) + "K 令牌";
        }
        return trimTrailingZero(tokens / 1_000_000.0) + "M 令牌";
    }

    /**
     * 保留一位小数并去掉无意义的 {@code .0}。
     *
     * @param value 数值
     * @return 形如 {@code 717} 或 {@code 1.2}
     */
    private static String trimTrailingZero(double value) {
        String text = String.format(Locale.ROOT, "%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }
}
