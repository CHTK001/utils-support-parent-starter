package com.chua.common.support.ai.branch;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.ai.rule.RuleContext;
import com.chua.common.support.ai.rule.RuleException;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * if-elseif-else 门面。
 *
 * <p>专门替代扁平业务分支的独立门面，{@code Rule} 保持不动：
 * 按声明顺序逐条求值，<b>首个命中的分支执行动作后即停</b>（独占语义）。
 * 无条件分支等价于 {@code else}，只允许出现在最后一条，
 * 否则后续分支永远不可达，构造期直接抛异常。</p>
 *
 * <p>与 {@code Rule} 的分工：分支只做「谁命中、执行什么」，
 * 不做优先级排序、跳转与结论聚合；需要那些能力时继续用规则引擎。</p>
 *
 * <p>同步与异步两套执行，链的定义完全不变（{@code when / then} 本身
 * 没有同步异步之分）：<b>{@link #execute(Object)}</b> 当前线程同步求值，
 * 返回 {@link BranchResult}；<b>{@link #executeAsync(Object)}</b> 交给
 * Reactor 调度异步求值，返回 {@link Mono}，支持三个回调
 * {@code subscribe(onNext, onError, onComplete)}：结果走 onNext、
 * 求值异常走 onError、onComplete 在流结束时通知——两条路径的
 * 命中与动作语义完全一致。</p>
 *
 * <pre>{@code
 * BranchResult result = BranchChain.of(
 *         Branch.definition("high").when(ctx -> ctx.intOf("risk", 0) >= 90)
 *             .message("高风险拦截").build(),
 *         Branch.definition("medium").when(ctx -> ctx.intOf("risk", 0) >= 60)
 *             .message("中风险复核").build(),
 *         Branch.definition("fallback").build())
 *     .execute(Map.of("risk", 95));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
public final class BranchChain {

    /**
     * 已排序的分支，保持声明顺序
     */
    private final List<Branch> branches;

    /**
     * 用分支集合构造。
     *
     * @param branches 分支集合，不可为 null、不可为空、分支标识不可重复、
     *                 无条件分支只允许出现在最后一条
     */
    public BranchChain(Collection<Branch> branches) {
        Objects.requireNonNull(branches, "branches 不能为 null");
        if (branches.isEmpty()) {
            throw new IllegalArgumentException("branches 不能为空");
        }
        Set<String> ids = Wraps.linkedSet();
        List<Branch> copy = List.copyOf(branches);
        for (int i = 0; i < copy.size(); i++) {
            Branch branch = Objects.requireNonNull(copy.get(i), "branches 不能包含 null 元素");
            if (!ids.add(branch.id())) {
                throw new IllegalArgumentException("分支标识重复: " + branch.id());
            }
            if (branch.unconditional() && i != copy.size() - 1) {
                throw new IllegalArgumentException("无条件分支只允许出现在最后一条: " + branch.id());
            }
        }
        this.branches = Collections.unmodifiableList(copy);
    }

    /**
     * 用分支数组构造。
     *
     * @param branches 分支
     * @return 分支门面
     */
    public static BranchChain of(Branch... branches) {
        return new BranchChain(List.of(branches));
    }

    /**
     * 按声明顺序的分支。
     *
     * @return 不可变分支列表
     */
    public List<Branch> branches() {
        return branches;
    }

    /**
     * 分支数量。
     *
     * @return 分支数量
     */
    public int size() {
        return branches.size();
    }

    /**
     * 用输入执行，不带环境量。
     *
     * @param input 流程输入，可为 null
     * @return 执行结果
     */
    public BranchResult execute(Object input) {
        return execute(input, null);
    }

    /**
     * 执行一次分支求值，同步返回。
     *
     * <p>按声明顺序逐条求值，首个命中的分支执行动作后即停；
     * 未命中任何分支时返回空命中结果，变量仍为拍平快照。</p>
     *
     * @param input       流程输入，可为 {@link Map} 或任意业务对象，可为 null
     * @param environment 环境量，可为 null
     * @return 执行结果
     */
    public BranchResult execute(Object input, Map<String, Object> environment) {
        RuleContext context = RuleContext.of(null, null, input, environment);
        for (Branch branch : branches) {
            if (!branch.matched(context)) {
                continue;
            }
            if (branch.hasActions()) {
                branch.apply(context);
            }
            return hit(branch, context);
        }
        return miss(context);
    }

    /**
     * 异步执行一次分支求值，交给 Reactor 调度。
     *
     * <p>求值与动作本身不变，整段放到 Reactor 异步执行，返回的
     * {@link Mono} 支持三个回调：{@code subscribe(onNext, onError, onComplete)}——
     * 结果（含未命中的空命中结果）走 onNext，求值/动作抛出的异常走 onError，
     * onComplete 在流结束时通知。</p>
     *
     * <p>冷流：每次订阅都用全新上下文重新求值，可缓存复用；
     * 首个命中的分支执行动作后即停，后续分支不再求值；
     * 任一条件/动作报错即走 onError，后续动作不再执行。</p>
     *
     * @param input 流程输入，可为 {@link Map} 或任意业务对象，可为 null
     * @return 执行结果的 Mono，永不为空序列
     */
    public Mono<BranchResult> executeAsync(Object input) {
        return executeAsync(input, null);
    }

    /**
     * 异步执行一次分支求值，交给 Reactor 调度。
     *
     * @param input       流程输入，可为 {@link Map} 或任意业务对象，可为 null
     * @param environment 环境量，可为 null
     * @return 执行结果的 Mono，永不为空序列
     */
    public Mono<BranchResult> executeAsync(Object input, Map<String, Object> environment) {
        return Mono.fromCallable(() -> execute(input, environment))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 命中结果。
     */
    private static BranchResult hit(Branch branch, RuleContext context) {
        return new BranchResult(branch.id(), branch.message(), context.action(), snapshot(context));
    }

    /**
     * 未命中结果。
     */
    private static BranchResult miss(RuleContext context) {
        return new BranchResult(null, null, context.action(), snapshot(context));
    }

    /**
     * 冻结上下文变量。
     */
    private static Map<String, Object> snapshot(RuleContext context) {
        Map<String, Object> all = Wraps.linkedMap();
        if (context.input() instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                all.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        all.putAll(context.environment());
        all.putAll(context.external());
        all.putAll(context.current());
        all.putAll(context.vars());
        return Collections.unmodifiableMap(all);
    }

    /**
     * 按标识取分支。
     *
     * @param id 分支标识
     * @return 对应分支，不存在时返回 null
     */
    public Branch byId(String id) {
        for (Branch branch : branches) {
            if (branch.id().equals(id)) {
                return branch;
            }
        }
        return null;
    }

    /**
     * 校验门面定义：分支标识互不重复由构造期保证，本方法预留给调用方显式自检。
     *
     * @throws RuleException 门面为空时
     */
    public void validate() {
        if (branches.isEmpty()) {
            throw new RuleException("分支门面为空");
        }
    }
}
