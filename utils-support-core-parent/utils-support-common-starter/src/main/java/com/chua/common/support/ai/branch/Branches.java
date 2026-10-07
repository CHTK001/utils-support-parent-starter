package com.chua.common.support.ai.branch;

import com.chua.common.support.ai.rule.RuleContext;

import java.util.function.Predicate;

/**
 * 连续链式分支入口。
 *
 * <p>每段 {@code when...then...} 独立成一条分支，写完一段自动另起下一段，
 * 等价于 {@code if / else if / else} 连写：</p>
 * <pre>{@code
 * BranchResult result = Branches.when(ctx -> ctx.intOf("risk", 0) >= 90)
 *         .message("高风险拦截")
 *         .then(ctx -> ctx.set("level", "high"))
 *         .when(ctx -> ctx.intOf("risk", 0) >= 60)
 *         .message("中风险复核")
 *         .then(ctx -> ctx.set("level", "medium"))
 *         .otherwise(ctx -> ctx.set("level", "low"))
 *         .execute(Map.of("risk", 95));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
public final class Branches {

    private Branches() {
    }

    /**
     * 以一个条件开启链式分支。
     *
     * @param condition 条件
     * @return 新的 when 段 builder
     */
    public static BranchWhenBuilder when(Predicate<RuleContext> condition) {
        BranchFlowState state = new BranchFlowState();
        state.openBranch(condition);
        return new BranchWhenBuilder(state);
    }

    /**
     * 以一个表达式开启链式分支。
     *
     * @param expression 表达式文本
     * @return 新的 when 段 builder
     */
    public static BranchWhenBuilder when(String expression) {
        BranchFlowState state = new BranchFlowState();
        state.openBranch(expression);
        return new BranchWhenBuilder(state);
    }
}
