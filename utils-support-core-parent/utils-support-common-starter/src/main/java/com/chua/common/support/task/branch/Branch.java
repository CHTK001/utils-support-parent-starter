package com.chua.common.support.task.branch;

import com.chua.common.support.concurrent.circuitbreaker.CircuitBreakerFlow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * 轻量惰性分支工具 —— 以流式链替代 if-else / try-catch。
 *
 * <p><strong>定位</strong>：单类、零依赖引擎、惰性求值；用于整理管线代码中散落的
 * 条件判断与异常兜底，使主流程线性可读。</p>
 *
 * <p><strong>管线改造对照</strong>（以人脸检测路由为例）：</p>
 *
 * <pre>{@code
 * // Before：嵌套 if-else + try-catch，错误策略散落
 * List<PredictRectangle> boxes;
 * if (animeDetector != null) {
 *     try {
 *         boxes = animeDetector.detect(imageData);
 *         if (boxes == null || boxes.isEmpty()) {
 *             boxes = detector.detect(imageData);
 *         }
 *     } catch (Exception e) {
 *         log.warn("anime fail", e);
 *         boxes = Collections.emptyList();
 *     }
 * } else {
 *     try {
 *         boxes = detector.detect(imageData);
 *     } catch (Exception e) {
 *         boxes = Collections.emptyList();
 *     }
 * }
 *
 * // After：线性组装，无一层缩进
 * List<PredictRectangle> boxes = Branch.of(imageData)
 *         .when(d -> animeDetector != null, d -> animeDetector.detect(d))
 *         .elseIf(d -> detector != null, d -> detector.detect(d))
 *         .recover(e -> Collections.emptyList())
 *         .get();
 * }</pre>
 *
 * <h2>语义契约</h2>
 * <ol>
 *   <li><strong>惰性</strong>：{@code of/when/otherwise/recover/onError/protect} 仅记录阶段，
 *       不产生任何执行；调用终端 {@link #get()} 或 {@link #afterBranch()} 时才折叠求值。</li>
 *   <li><strong>条件组</strong>：{@code when} 开启新组、{@code elseIf} 追加、{@code otherwise}
 *       收尾；求值时按序取第一个命中的分支执行，组内其余分支跳过。
 *       {@code elseIf}/{@code otherwise} 未跟随任何 {@code when} 时抛出
 *       {@link IllegalStateException} 快速失败。</li>
 *   <li><strong>null 免疫</strong>：除 {@link #whenNull(UnaryOperator)} 外，任何谓词与动作
 *       都不会收到 null 入参——当前值为 null 时整组跳过并透传。因此用户 lambda 从根上避免 NPE。</li>
 *   <li><strong>异常双通道</strong>：{@link #recover(Function)} 捕获此前未兜底步骤的异常，
 *       以返回值续接后续链；{@link #onError(Consumer)} 同样捕获但仅消费异常、链立即终止，
 *       结果取异常前最近一次成功值。二者作用域均为"自上一个同类注册之后"。</li>
 *   <li><strong>熔断保护</strong>：{@link #protect(String)} 使后续步骤经由
 *       {@link CircuitBreakerFlow} 执行；熔开时不再调用底层逻辑，直接落入 recover/onError，
 *       避免"依赖故障后每请求都抛异常"的开销。</li>
 *   <li><strong>规范强制</strong>：未设置任何条件分支即调用 {@link #get()} /
 *       {@link #afterBranch()} 抛出 {@link IllegalStateException}，防止拿分支工具当透传管道用。</li>
 * </ol>
 *
 * <p><strong>线程与复用</strong>：实例为流式构建器，链式组装须在单线程内完成；
 * 组装完成后 {@link #get()} 可重复调用（动作自身具备幂等性时结果幂等）。</p>
 *
 * @param <T> 链上流转值的类型
 * @author CH
 * @since 4.0.0.42
 */
public final class Branch<T> {

    /**
     * 条件分支：谓词 + 动作；nullAware 标记该分支允许接收 null 入参（仅 whenNull 使用）。
     */
    private static final class Case<T> {

        /** 触发条件（nullAware 时忽略）。 */
        final Predicate<T> condition;

        /** 命中后执行的动作。 */
        final UnaryOperator<T> action;

        /** 是否允许 null 入参。 */
        final boolean nullAware;

        Case(Predicate<T> condition, UnaryOperator<T> action, boolean nullAware) {
            this.condition = condition;
            this.action = action;
            this.nullAware = nullAware;
        }
    }

    /**
     * 条件组：按序首中胜；otherwise 以恒真条件追加并封组。
     */
    private static final class Group<T> {

        /** 组内候选分支。 */
        final List<Case<T>> cases = new ArrayList<>();

        /** 是否已被 otherwise 封组。 */
        boolean sealed;
    }

    /** 阶段抽象。 */
    private interface Stage<T> {
    }

    /** 条件组阶段。 */
    private static final class GroupStage<T> implements Stage<T> {

        final Group<T> group;

        GroupStage(Group<T> group) {
            this.group = group;
        }
    }

    /** 异常恢复注册阶段。 */
    private static final class RecoverStage<T> implements Stage<T> {

        final Function<Throwable, T> fallback;

        RecoverStage(Function<Throwable, T> fallback) {
            this.fallback = fallback;
        }
    }

    /** 异常终止注册阶段。 */
    private static final class OnErrorStage<T> implements Stage<T> {

        final Consumer<Throwable> handler;

        OnErrorStage(Consumer<Throwable> handler) {
            this.handler = handler;
        }
    }

    /** 熔断保护切换阶段。 */
    private static final class ProtectStage<T> implements Stage<T> {

        final CircuitBreakerFlow flow;

        ProtectStage(CircuitBreakerFlow flow) {
            this.flow = flow;
        }
    }

    /** 初始种子。 */
    private final T seed;

    /** 已登记的阶段序列。 */
    private final List<Stage<T>> stages = new ArrayList<>();

    /** 当前未封组的条件组；null 表示无开放组。 */
    private Group<T> openGroup;

    /** 是否已设置过任意条件分支（规范校验依据）。 */
    private boolean hasCondition;

    private Branch(T seed) {
        this.seed = seed;
    }

    /**
     * 创建分支链。
     *
     * @param seed 初始值，可为 null
     * @param <T>  值类型
     * @return 分支链
     */
    public static <T> Branch<T> of(T seed) {
        return new Branch<>(seed);
    }

    /* ---------------- 条件组 ---------------- */

    /**
     * 开启条件组并加入首个分支。
     *
     * <p>当前值为 null 时整组跳过（见类注释 null 免疫契约），谓词不会被调用。</p>
     *
     * @param condition 触发条件
     * @param action    命中后的动作
     * @return 本链
     */
    public Branch<T> when(Predicate<T> condition, UnaryOperator<T> action) {
        openGroup = new Group<>();
        stages.add(new GroupStage<>(openGroup));
        openGroup.cases.add(new Case<>(condition, action, false));
        hasCondition = true;
        return this;
    }

    /**
     * 向当前开放组追加分支；未跟随任何 {@code when} 时快速失败。
     *
     * @param condition 触发条件
     * @param action    命中后的动作
     * @return 本链
     */
    public Branch<T> elseIf(Predicate<T> condition, UnaryOperator<T> action) {
        requireOpenGroup("elseIf");
        openGroup.cases.add(new Case<>(condition, action, false));
        hasCondition = true;
        return this;
    }

    /**
     * 以恒真分支收尾当前组（等价 else）；未跟随任何 {@code when} 时快速失败。
     *
     * @param action 兜底动作
     * @return 本链
     */
    public Branch<T> otherwise(UnaryOperator<T> action) {
        requireOpenGroup("otherwise");
        if (openGroup.sealed) {
            throw new IllegalStateException("otherwise 之后不可继续追加 otherwise/elseIf");
        }
        openGroup.cases.add(new Case<>(v -> true, action, false));
        openGroup.sealed = true;
        openGroup = null;
        hasCondition = true;
        return this;
    }

    /* ---------------- 内置判断 ---------------- */

    /**
     * 当前值为 null 时执行（唯一允许动作入参为 null 的入口）。
     *
     * @param action 动作，入参为 null
     * @return 本链
     */
    public Branch<T> whenNull(UnaryOperator<T> action) {
        openGroup = new Group<>();
        stages.add(new GroupStage<>(openGroup));
        openGroup.cases.add(new Case<>(v -> true, action, true));
        hasCondition = true;
        return this;
    }

    /**
     * 当前值为空集合 / 空 Map / 空字符串 / 空数组时执行；其余类型视为不命中。
     *
     * @param action 命中后的动作
     * @return 本链
     */
    public Branch<T> whenNone(UnaryOperator<T> action) {
        return when(Branch::isNone, action);
    }

    /**
     * 元素个数为 0 或数值为 0 时执行；其余类型视为不命中。
     *
     * @param action 命中后的动作
     * @return 本链
     */
    public Branch<T> whenZero(UnaryOperator<T> action) {
        return when(Branch::isZeroOrNone, action);
    }

    /**
     * 元素个数为 1 或数值为 1 时执行；其余类型视为不命中。
     *
     * @param action 命中后的动作
     * @return 本链
     */
    public Branch<T> whenOne(UnaryOperator<T> action) {
        return when(v -> sizeOf(v) == 1 || isNumberEquals(v, 1d), action);
    }

    /**
     * 当前值为 Boolean.TRUE 时执行；其余类型视为不命中。
     *
     * @param action 命中后的动作
     * @return 本链
     */
    public Branch<T> whenTrue(UnaryOperator<T> action) {
        return when(v -> v instanceof Boolean b && b, action);
    }

    /* ---------------- 异常双通道 ---------------- */

    /**
     * 注册异常恢复：此后步骤抛出的异常交由 fallback 生成兜底值并续接后续链。
     *
     * @param fallback 兜底函数，入参为捕获到的异常
     * @return 本链
     */
    public Branch<T> recover(Function<Throwable, T> fallback) {
        stages.add(new RecoverStage<>(fallback));
        return this;
    }

    /**
     * 注册异常出口：此后步骤抛出的异常交由 handler 消费，链随即终止，
     * 结果取异常前最近一次成功值。
     *
     * @param handler 异常消费者
     * @return 本链
     */
    public Branch<T> onError(Consumer<Throwable> handler) {
        stages.add(new OnErrorStage<>(handler));
        return this;
    }

    /* ---------------- 熔断保护 ---------------- */

    /**
     * 后续步骤经默认配置的命名熔断器执行；熔开时不执行底层逻辑，
     * 直接进入 recover/onError 流程。
     *
     * @param breakerName 熔断器名称（同名共享状态）
     * @return 本链
     */
    public Branch<T> protect(String breakerName) {
        stages.add(new ProtectStage<>(CircuitBreakerFlow.of(breakerName)));
        return this;
    }

    /**
     * 后续步骤经自定义阈值的熔断器执行。
     *
     * @param breakerName      熔断器名称
     * @param failureThreshold 失败次数阈值（达到后熔开）
     * @param successThreshold 半开状态成功次数阈值（达到后闭合）
     * @param waitDurationMs   熔开等待时长（毫秒）
     * @return 本链
     */
    public Branch<T> protect(String breakerName, int failureThreshold,
                             int successThreshold, long waitDurationMs) {
        CircuitBreakerFlow flow = CircuitBreakerFlow.of(breakerName)
                .failureThreshold(failureThreshold)
                .successThreshold(successThreshold)
                .waitDuration(waitDurationMs);
        stages.add(new ProtectStage<>(flow));
        return this;
    }

    /* ---------------- 终端 ---------------- */

    /**
     * 折叠求值。未设置任何条件分支时抛出 {@link IllegalStateException}。
     *
     * @return 链上最终值（可能为 null）
     */
    public T get() {
        if (!hasCondition) {
            throw new IllegalStateException(
                    "Branch 缺少条件分支：至少设置一个 when/elseIf/otherwise 后才能 get()");
        }
        T current = seed;
        T lastGood = seed;
        Function<Throwable, T> recover = null;
        Consumer<Throwable> onError = null;
        CircuitBreakerFlow breaker = null;
        boolean terminated = false;

        for (Stage<T> stage : stages) {
            if (stage instanceof RecoverStage<T> r) {
                recover = r.fallback;
            } else if (stage instanceof OnErrorStage<T> o) {
                onError = o.handler;
            } else if (stage instanceof ProtectStage<T> p) {
                breaker = p.flow;
            } else if (stage instanceof GroupStage<T> g) {
                EvalResult<T> er = evalGroup(g.group, current, breaker, recover, onError);
                if (er.terminated) {
                    terminated = true;
                    break;
                }
                if (er.changed) {
                    current = er.value;
                    lastGood = er.value;
                    recover = null;
                    onError = null;
                }
            }
            if (terminated) {
                break;
            }
        }
        return terminated ? lastGood : current;
    }

    /**
     * 求值当前链并将结果作为新链种子；新链的条件组与异常作用域全部重置。
     * 内部等价于 {@code Branch.of(this.get())}。
     *
     * @return 以求值结果为种子的新分支链
     */
    public Branch<T> afterBranch() {
        return of(get());
    }

    /* ---------------- 内部求值 ---------------- */

    /**
     * 单次组求值结果。
     */
    private record EvalResult<T>(T value, boolean changed, boolean terminated) {

        static <T> EvalResult<T> unchanged() {
            return new EvalResult<>(null, false, false);
        }
    }

    /**
     * 求值一个条件组。
     */
    private EvalResult<T> evalGroup(Group<T> group, T current,
                                    CircuitBreakerFlow breaker,
                                    Function<Throwable, T> recover,
                                    Consumer<Throwable> onError) {
        // null 值：仅 nullAware 分支可介入，其余整组跳过
        if (current == null) {
            for (Case<T> c : group.cases) {
                if (!c.nullAware) {
                    continue;
                }
                T out = runProtected(() -> c.action.apply(null), breaker, recover, onError);
                if (out == SKIP_SENTINEL) {
                    return EvalResult.unchanged();
                }
                @SuppressWarnings("unchecked")
                T value = (T) out;
                return new EvalResult<>(value, true, TERMINATED.get());
            }
            return EvalResult.unchanged();
        }
        // 非 null：按序首中胜
        for (Case<T> c : group.cases) {
            if (c.nullAware || !c.condition.test(current)) {
                continue;
            }
            T out = runProtected(() -> c.action.apply(current), breaker, recover, onError);
            if (out == SKIP_SENTINEL) {
                return EvalResult.unchanged();
            }
            @SuppressWarnings("unchecked")
            T value = (T) out;
            return new EvalResult<>(value, true, TERMINATED.get());
        }
        return EvalResult.unchanged();
    }

    /**
     * 经熔断器执行动作并套接异常处理；返回 SKIP_SENTINEL 表示链已被 onError 终止。
     */
    private T runProtected(java.util.concurrent.Callable<T> action,
                           CircuitBreakerFlow breaker,
                           Function<Throwable, T> recover,
                           Consumer<Throwable> onError) {
        try {
            return breaker != null ? breaker.execute(action::call) : action.call();
        } catch (Throwable t) {
            if (recover != null) {
                try {
                    return recover.apply(t);
                } catch (Throwable nested) {
                    throw t;
                }
            }
            if (onError != null) {
                onError.accept(t);
                TERMINATED.set(true);
                return SKIP_SENTINEL;
            }
            throw t;
        }
    }

    /** onError 终止标记（线程级，避免跨链污染）。 */
    private static final ThreadLocal<Boolean> TERMINATED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 动作被跳过/链终止时的哨兵值。 */
    private static final Object SKIP_SENTINEL = new Object();

    /* ---------------- 工具方法 ---------------- */

    /**
     * 校验存在开放的条件组。
     *
     * @param op 操作名（错误信息用）
     */
    private void requireOpenGroup(String op) {
        if (openGroup == null) {
            throw new IllegalStateException(op + " 必须紧跟 when/elseIf 使用");
        }
        if (openGroup.sealed) {
            throw new IllegalStateException("条件组已被 otherwise 封闭，不可再追加 " + op);
        }
    }

    /**
     * 判定值是否为"空"：集合 / Map / 字符串 / 数组长度为 0。
     *
     * @param v 待判定值
     * @return 空返回 true
     */
    private static boolean isNone(Object v) {
        if (v instanceof Collection<?> c) {
            return c.isEmpty();
        }
        if (v instanceof Map<?, ?> m) {
            return m.isEmpty();
        }
        if (v instanceof CharSequence cs) {
            return cs.length() == 0;
        }
        if (v != null && v.getClass().isArray()) {
            return java.lang.reflect.Array.getLength(v) == 0;
        }
        return false;
    }

    /**
     * 取元素个数；非容器类型返回 -1。
     *
     * @param v 待判定值
     * @return 元素个数
     */
    private static int sizeOf(Object v) {
        if (v instanceof Collection<?> c) {
            return c.size();
        }
        if (v instanceof Map<?, ?> m) {
            return m.size();
        }
        if (v instanceof CharSequence cs) {
            return cs.length();
        }
        if (v != null && v.getClass().isArray()) {
            return java.lang.reflect.Array.getLength(v);
        }
        return -1;
    }

    /**
     * 判定数值是否等于指定值。
     *
     * @param v     待判定值
     * @param target 目标值
     * @return 相等返回 true
     */
    private static boolean isNumberEquals(Object v, double target) {
        if (v instanceof Number n) {
            return n.doubleValue() == target;
        }
        return false;
    }

    /**
     * 判定是否为零或空。
     *
     * @param v 待判定值
     * @return 零/空返回 true
     */
    private static boolean isZeroOrNone(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue() == 0d;
        }
        return isNone(v);
    }
}
