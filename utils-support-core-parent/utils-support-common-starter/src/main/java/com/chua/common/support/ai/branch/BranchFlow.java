package com.chua.common.support.ai.branch;

import com.chua.common.support.ai.rule.RuleContext;

import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 连续链式分支旧门面（已由分段 builder 接管）。
 *
 * <p>保留本类只为兼容：内部不再持有可变状态，
 * 所有写入均委托给 {@link BranchWhenBuilder} / {@link BranchThenBuilder}。
 * 新代码建议直接用 {@link Branches#when(Predicate)} 起链，
 * 每段 {@code when / then} 都会拿到独立的 builder 实例。</p>
 *
 * @author CH
 * @since 4.0.0.43
 * @deprecated 改用 {@link BranchWhenBuilder} / {@link BranchThenBuilder} 分段链式
 */
@Deprecated
public final class BranchFlow {

    /**
     * 共享状态
     */
    private final BranchFlowState state;

    BranchFlow() {
        this.state = new BranchFlowState();
    }

    BranchFlow(Predicate<RuleContext> condition) {
        this.state = new BranchFlowState();
        state.openBranch(condition);
    }

    BranchFlow(String expression) {
        this.state = new BranchFlowState();
        state.openBranch(expression);
    }

    BranchFlow(BranchFlowState state) {
        this.state = Objects.requireNonNull(state, "state 不能为 null");
    }

    /**
     * 再起一条条件分支，前一条在此终结，返回新的 when 段 builder。
     *
     * @param condition 条件
     * @return 新的 when 段 builder
     */
    public BranchWhenBuilder when(Predicate<RuleContext> condition) {
        state.openBranch(condition);
        return new BranchWhenBuilder(state);
    }

    /**
     * 再起一条条件分支（表达式形态），前一条在此终结，返回新的 when 段 builder。
     *
     * @param expression 表达式文本
     * @return 新的 when 段 builder
     */
    public BranchWhenBuilder when(String expression) {
        state.openBranch(expression);
        return new BranchWhenBuilder(state);
    }

    /**
     * 终结链式为门面。
     *
     * @return 分支门面
     */
    public BranchChain chain() {
        state.closeCurrent();
        state.built = true;
        return new BranchChain(state.done);
    }

    /**
     * 终结链式并执行。
     *
     * @param input 流程输入，可为 null
     * @return 执行结果
     */
    public BranchResult execute(Object input) {
        return chain().execute(input);
    }

    /**
     * 终结链式并执行。
     *
     * @param input       流程输入，可为 null
     * @param environment 环境量，可为 null
     * @return 执行结果
     */
    public BranchResult execute(Object input, Map<String, Object> environment) {
        return chain().execute(input, environment);
    }
}
