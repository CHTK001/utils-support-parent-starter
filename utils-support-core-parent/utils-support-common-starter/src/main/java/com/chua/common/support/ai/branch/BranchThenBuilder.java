package com.chua.common.support.ai.branch;

import com.chua.common.support.ai.rule.RuleContext;

import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 链式分支 then 段独立构造器。
 *
 * <p>每次 {@code then} 都返回一个新的本类实例（单独 builder），
 * 底下共享同一个 {@link BranchFlowState}，保证链不断。
 * 本段负责当前分支的动作追加，下一个 {@code when} 另起新分支。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public final class BranchThenBuilder {

    /**
     * 共享状态
     */
    private final BranchFlowState state;

    BranchThenBuilder(BranchFlowState state) {
        this.state = Objects.requireNonNull(state, "state 不能为 null");
    }

    /**
     * 为当前分支追加动作，返回新的 then 段 builder。
     *
     * @param action 动作
     * @return 新的 then 段 builder
     */
    public BranchThenBuilder then(Consumer<RuleContext> action) {
        state.requireWritable();
        state.requireOpen();
        state.addThen(action);
        return new BranchThenBuilder(state);
    }

    /**
     * 声明当前分支命中后对外说明的原因，返回新的 then 段 builder。
     *
     * @param message 拦截原因，可为 null
     * @return 新的 then 段 builder
     */
    public BranchThenBuilder message(String message) {
        state.requireWritable();
        state.requireOpen();
        state.message = message;
        return new BranchThenBuilder(state);
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
     * 无条件兜底分支，等价于 {@code else}，最后调用一次。
     *
     * @param action 动作
     * @return 自身（已终结，只可执行）
     */
    public BranchThenBuilder otherwise(Consumer<RuleContext> action) {
        state.requireWritable();
        state.closeCurrent();
        state.open = true;
        state.addThen(action);
        state.closeCurrent();
        state.open = false;
        state.built = true;
        return new BranchThenBuilder(state);
    }

    /**
     * 无动作兜底分支，等价于空 {@code else}。
     *
     * @return 自身（已终结，只可执行）
     */
    public BranchThenBuilder otherwise() {
        state.requireWritable();
        state.closeCurrent();
        state.open = true;
        state.closeCurrent();
        state.open = false;
        state.built = true;
        return new BranchThenBuilder(state);
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

    /**
     * 终结链式并异步执行，返回 {@link Mono}。
     *
     * <p>交给 Reactor 调度，支持三个回调：{@code subscribe(onNext, onError, onComplete)}——
     * 结果（含未命中的空命中结果）走 onNext，求值/动作异常走 onError，
     * onComplete 在流结束时通知；冷流可缓存复用。</p>
     *
     * @param input 流程输入，可为 null
     * @return 执行结果
     */
    public Mono<BranchResult> executeAsync(Object input) {
        return chain().executeAsync(input);
    }

    /**
     * 终结链式并异步执行，返回 {@link Mono}。
     *
     * @param input       流程输入，可为 null
     * @param environment 环境量，可为 null
     * @return 执行结果
     */
    public Mono<BranchResult> executeAsync(Object input, Map<String, Object> environment) {
        return chain().executeAsync(input, environment);
    }
}
