package com.chua.common.support.ai.branch;

import com.chua.common.support.ai.rule.RuleContext;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 一条分支，if-elseif-else 门面的最小单元。
 *
 * <p>与 {@code Rule} 的区别：分支是<b>轻量求值体</b>，
 * 条件与动作直接对 {@link RuleContext} 求值，不参与规则集的
 * 优先级排序、跳转与结论聚合。门面按声明顺序逐条求值，
 * 首个命中的分支执行动作后即停（独占语义），等价于
 * {@code if / else if / else}。</p>
 *
 * <p>条件与动作本身没有同步/异步之分，都是普通谓词与消费者；
 * 同步与异步只在执行层：{@link BranchChain#execute(Object)} 当前线程跑完，
 * {@link BranchChain#executeAsync(Object)} 返回 {@link reactor.core.publisher.Mono}
 * 交由 Reactor 调度并以三个回调（onNext/onError/onComplete）交付结果。</p>
 *
 * <p>无条件的分支等价于 {@code else}：构造时不传条件即恒真，
 * 但门面只允许最后一条为无条件分支，否则后续分支永远不可达。</p>
 *
 * @param id          分支标识，同一门面内唯一
 * @param name        分支显示名，默认与 {@code id} 相同
 * @param description 分支描述，可为 null
 * @param message     命中后对外说明的原因，可为 null
 * @param conditions  条件，OR 关系（任一成立即命中），可为空（无条件分支）
 * @param actions     命中后按序执行的动作
 * @author CH
 * @since 4.0.0.43
 */
public record Branch(String id,
                     String name,
                     String description,
                     String message,
                     List<Predicate<RuleContext>> conditions,
                     List<Consumer<RuleContext>> actions) {

    /**
     * 构造分支。
     */
    public Branch {
        Objects.requireNonNull(id, "id 不能为 null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id 不能为空白");
        }
        Objects.requireNonNull(name, "name 不能为 null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空白");
        }
        conditions = List.copyOf(Objects.requireNonNull(conditions, "conditions 不能为 null"));
        actions = List.copyOf(Objects.requireNonNull(actions, "actions 不能为 null"));
    }

    /**
     * 开始定义一条分支。
     *
     * @param id 分支标识，同时作为默认显示名
     * @return 分支构造器
     */
    public static BranchBuilder definition(String id) {
        return new DefaultBranchBuilder().id(id);
    }

    /**
     * 以一个条件开启连续链式分支，每段 {@code when...then...} 独立成一条分支。
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
     * 以一个表达式开启连续链式分支，每段 {@code when...then...} 独立成一条分支。
     *
     * @param expression 表达式文本
     * @return 新的 when 段 builder
     */
    public static BranchWhenBuilder when(String expression) {
        BranchFlowState state = new BranchFlowState();
        state.openBranch(expression);
        return new BranchWhenBuilder(state);
    }

    /**
     * 是否无条件分支，等价于 {@code else}。
     *
     * @return 无条件返回 true
     */
    public boolean unconditional() {
        return conditions.isEmpty();
    }

    /**
     * 判断条件是否成立，无条件视为恒真。
     *
     * @param context 规则上下文
     * @return 条件成立返回 true
     */
    public boolean matched(RuleContext context) {
        Objects.requireNonNull(context, "context 不能为 null");
        for (Predicate<RuleContext> condition : conditions) {
            if (condition.test(context)) {
                return true;
            }
        }
        return conditions.isEmpty();
    }

    /**
     * 执行动作，按声明顺序。
     *
     * @param context 规则上下文
     */
    public void apply(RuleContext context) {
        Objects.requireNonNull(context, "context 不能为 null");
        for (Consumer<RuleContext> action : actions) {
            action.accept(context);
        }
    }

    /**
     * 是否有动作需要执行。
     *
     * @return 存在动作返回 true
     */
    public boolean hasActions() {
        return !actions.isEmpty();
    }

    /**
     * 读分支声明的拦截原因。
     *
     * @return 拦截原因，可为 null
     */
    public Optional<String> reason() {
        return Optional.ofNullable(message);
    }
}
