package com.chua.common.support.ai.branch;

import com.chua.common.support.ai.rule.RuleContext;
import com.chua.common.support.ai.rule.RuleException;

import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 链式分支 when 段独立构造器。
 *
 * <p>每次 {@code when} 都返回一个新的本类实例（单独 builder），
 * 底下共享同一个 {@link BranchFlowState}，保证链不断。
 * 本段负责当前分支的条件与元信息，{@link #then(Consumer)} 进入 then 段。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public final class BranchWhenBuilder {

    /**
     * 共享状态
     */
    private final BranchFlowState state;

    BranchWhenBuilder(BranchFlowState state) {
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
     * 为当前分支指定固定标识。
     *
     * <p>必须紧跟 {@code when} 调用；不调用则自动生成 {@code branch-N}。</p>
     *
     * @param id 分支标识，不可为空白
     * @return 新的 when 段 builder
     */
    public BranchWhenBuilder as(String id) {
        state.requireWritable();
        if (!state.open) {
            throw new RuleException("as 必须紧跟 when 调用，当前没有待命名的分支");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id 不能为 null 或空白");
        }
        if (state.id != null) {
            throw new RuleException("当前分支标识已指定为 " + state.id + "，不允许改为 " + id);
        }
        state.id = id;
        if (state.name == null) {
            state.name = id;
        }
        return new BranchWhenBuilder(state);
    }

    /**
     * 覆盖当前分支显示名。
     *
     * @param name 显示名，不可为空白
     * @return 新的 when 段 builder
     */
    public BranchWhenBuilder name(String name) {
        state.requireWritable();
        state.requireOpen();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为 null 或空白");
        }
        state.name = name;
        return new BranchWhenBuilder(state);
    }

    /**
     * 声明当前分支描述。
     *
     * @param description 分支描述，可为 null
     * @return 新的 when 段 builder
     */
    public BranchWhenBuilder description(String description) {
        state.requireWritable();
        state.requireOpen();
        state.description = description;
        return new BranchWhenBuilder(state);
    }

    /**
     * 声明当前分支命中后对外说明的原因。
     *
     * @param message 拦截原因，可为 null
     * @return 新的 when 段 builder
     */
    public BranchWhenBuilder message(String message) {
        state.requireWritable();
        state.requireOpen();
        state.message = message;
        return new BranchWhenBuilder(state);
    }

    /**
     * 为当前分支追加动作，进入 then 段，返回新的 then 段 builder。
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
     * 无条件兜底分支，等价于 {@code else}，最后调用一次。
     *
     * @param action 动作
     * @return 新的 then 段 builder（已终结，只可执行）
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
     * @return 新的 then 段 builder（已终结，只可执行）
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
