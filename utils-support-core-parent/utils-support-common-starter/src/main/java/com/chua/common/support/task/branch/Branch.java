package com.chua.common.support.task.branch;

import com.chua.common.support.concurrent.circuitbreaker.CircuitBreakerFlow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 轻量惰性分支工具 —— 以流式链替代 if-else / try-catch。
 *
 * <p><strong>定位</strong>：单类、零引擎依赖、惰性求值；用于整理管线代码中散落的
 * 条件判断与异常兜底，使主流程线性可读。</p>
 *
 * <p><strong>管线改造对照</strong>（以人脸检测路由为例）：</p>
 *
 * <pre>{@code
 * // Before：嵌套 if-else + try-catch
 * List<PredictRectangle> boxes;
 * if (animeDetector != null) {
 *     try {
 *         boxes = animeDetector.detect(imageData);
 *     } catch (Exception e) {
 *         boxes = detector.detect(imageData);
 *     }
 * } else {
 *     boxes = detector.detect(imageData);
 * }
 *
 * // After：线性组装，无一层缩进
 * List<PredictRectangle> boxes = Branch.ofBytes(imageData)
 *         .when(d -> animeDetector != null, d -> animeDetector.detect(d))
 *         .otherwise(d -> detector.detect(d))          // 组内输入始终是 byte[]
 *         .recover(e -> Collections.emptyList())
 *         .get();
 * }</pre>
 *
 * <h2>语义契约</h2>
 * <ol>
 *   <li><strong>惰性</strong>：所有登记方法仅记录阶段，不产生任何执行；
 *       调用终端 {@link #get()} 或 {@link #afterBranch()} 时才折叠求值。</li>
 *   <li><strong>条件组</strong>：{@code when}/内置判断开启一个组并返回
 *       {@link WhenGroup} 视图；组内 {@code elseIf} 的谓词与后续分支一样接收
 *       <em>组的原始输入类型 T</em>（而非上一分支的输出），按序首中胜；
 *       以 {@code otherwise} 收尾（等价 else）或 {@code end()} 省略 else 后回到主线。
 *       未跟随任何 {@code when} 的 {@code elseIf}/{@code otherwise} 不可能存在
 *       （它们只存在于 WhenGroup 上）。</li>
 *   <li><strong>null 免疫</strong>：除 {@link #whenNull(Function)} 外，任何谓词与动作
 *       都不会收到 null 入参——当前值为 null 时整组跳过并透传。</li>
 *   <li><strong>异常双通道</strong>：{@link #recover(Function)} 与 {@link #onError(Consumer)}
 *       作用于自注册点之后的步骤直至再次覆盖。前者以返回值续接后续链，
 *       后者仅消费异常并终止链，结果取异常前最近一次成功值。</li>
 *   <li><strong>熔断保护</strong>：{@link #protect(String)} 使后续步骤经
 *       {@link CircuitBreakerFlow} 执行；熔开或失败时进入 recover/onError 流程，
 *       避免"依赖故障后每请求都抛异常"。经熔断器执行的失败异常会被包装为
 *       {@link RuntimeException}（cause 保留原始异常）。</li>
 *   <li><strong>规范强制</strong>：未设置任何条件分支即调用终端抛出
 *       {@link IllegalStateException}。</li>
 * </ol>
 *
 * <p><strong>线程与复用</strong>：实例为流式构建器，组装须在单线程内完成；
 * 组装完成后 {@link #get()} 可重复调用。</p>
 *
 * @param <T> 当前链上流转值的类型
 * @author CH
 * @since 4.0.0.42
 */
public final class Branch<T> {

    /**
     * 条件分支：谓词 + 动作；nullAware 标记允许 null 入参（仅 whenNull 使用）。
     * 内部统一以 Object 签名存储，类型安全由公开泛型门面保证。
     */
    private record Case(Predicate<Object> condition, Function<Object, Object> action, boolean nullAware) {
    }

    /**
     * 条件组：按序首中胜；otherwise 以恒真条件追加并封组。
     */
    private static final class Group {

        /** 组内候选分支。 */
        final List<Case> cases = new ArrayList<>();
    }

    /** 阶段抽象。 */
    private interface Stage {
    }

    /** 条件组阶段。 */
    private record GroupStage(Group group) implements Stage {
    }

    /** 异常恢复注册阶段。 */
    private record RecoverStage(Function<Throwable, Object> fallback) implements Stage {
    }

    /** 异常终止注册阶段。 */
    private record OnErrorStage(Consumer<Throwable> handler) implements Stage {
    }

    /** 熔断保护切换阶段。 */
    private record ProtectStage(CircuitBreakerFlow flow) implements Stage {
    }

    /** 初始种子。 */
    private final Object seed;

    /** 已登记的阶段序列。 */
    private final List<Stage> stages = new ArrayList<>();

    /** 当前未封组的条件组；null 表示无开放组。 */
    private Group openGroup;

    /** 是否已设置过任意条件分支（规范校验依据）。 */
    private boolean hasCondition;

    private Branch(Object seed) {
        this.seed = seed;
    }

    /**
     * 创建分支链。
     *
     * @param seed 初始值，可为 null
     * @param <T>  值类型
     * @return 分支链
     */
    @SuppressWarnings("unchecked")
    public static <T> Branch<T> of(T seed) {
        return (Branch<T>) new Branch(seed);
    }

    /**
     * 图像管线常用入口：以字节数组起链。
     *
     * @param data 图像等二进制数据，可为 null
     * @return 字节数组分支链
     */
    public static Branch<byte[]> ofBytes(byte[] data) {
        return of(data);
    }

    /* ---------------- 条件组 ---------------- */

    /**
     * 开启条件组并加入首个分支，返回组视图以续写 elseIf/otherwise/end。
     *
     * <p>当前值为 null 时整组跳过（见类注释 null 免疫契约），谓词不会被调用。</p>
     *
     * @param condition 触发条件
     * @param action    命中后的动作
     * @param <R>       动作产出类型
     * @return 条件组视图
     */
    @SuppressWarnings("unchecked")
    public <R> WhenGroup<T, R> when(Predicate<T> condition, Function<? super T, ? extends R> action) {
        openGroup = new Group();
        stages.add(new GroupStage(openGroup));
        addCase(condition, action, false);
        return (WhenGroup<T, R>) new WhenGroup<>(this);
    }

    /**
     * 当前值为 null 时执行（唯一允许动作入参为 null 的入口）。
     *
     * @param action 动作，入参为 null
     * @param <R>    动作产出类型
     * @return 条件组视图
     */
    @SuppressWarnings("unchecked")
    public <R> WhenGroup<T, R> whenNull(Function<? super T, ? extends R> action) {
        openGroup = new Group();
        stages.add(new GroupStage(openGroup));
        addRawCase(v -> true, action, true);
        return (WhenGroup<T, R>) new WhenGroup<>(this);
    }

    /**
     * 当前值为空集合 / 空 Map / 空字符串 / 空数组时执行；其余类型视为不命中。
     *
     * @param action 命中后的动作
     * @param <R>    动作产出类型
     * @return 条件组视图
     */
    public <R> WhenGroup<T, R> whenNone(Function<? super T, ? extends R> action) {
        return when(Branch::isNone, action);
    }

    /**
     * 元素个数为 0 或数值为 0 时执行；其余类型视为不命中。
     *
     * @param action 命中后的动作
     * @param <R>    动作产出类型
     * @return 条件组视图
     */
    public <R> WhenGroup<T, R> whenZero(Function<? super T, ? extends R> action) {
        return when(Branch::isZeroOrNone, action);
    }

    /**
     * 元素个数为 1 或数值为 1 时执行；其余类型视为不命中。
     *
     * @param action 命中后的动作
     * @param <R>    动作产出类型
     * @return 条件组视图
     */
    public <R> WhenGroup<T, R> whenOne(Function<? super T, ? extends R> action) {
        return when(v -> sizeOf(v) == 1 || isNumberEquals(v, 1d), action);
    }

    /**
     * 当前值为 Boolean.TRUE 时执行；其余类型视为不命中。
     *
     * @param action 命中后的动作
     * @param <R>    动作产出类型
     * @return 条件组视图
     */
    public <R> WhenGroup<T, R> whenTrue(Function<? super T, ? extends R> action) {
        return when(v -> v instanceof Boolean b && b, action);
    }

    /* ---------------- 异常双通道 ---------------- */

    /**
     * 注册异常恢复：此后步骤抛出的异常交由 fallback 生成兜底值并续接后续链。
     *
     * @param fallback 兜底函数，入参为捕获到的异常
     * @param <R>      兜底值类型
     * @return 类型切换后的分支链
     */
    @SuppressWarnings("unchecked")
    public <R> Branch<R> recover(Function<Throwable, R> fallback) {
        stages.add(new RecoverStage((Function<Throwable, Object>) fallback));
        return (Branch<R>) this;
    }

    /**
     * 注册异常出口：此后步骤抛出的异常交由 handler 消费，链随即终止，
     * 结果取异常前最近一次成功值。
     *
     * @param handler 异常消费者
     * @return 本链
     */
    public Branch<T> onError(Consumer<Throwable> handler) {
        stages.add(new OnErrorStage(handler));
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
        stages.add(new ProtectStage(CircuitBreakerFlow.of(breakerName)));
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
        stages.add(new ProtectStage(flow));
        return this;
    }

    /* ---------------- 终端 ---------------- */

    /**
     * 折叠求值。未设置任何条件分支时抛出 {@link IllegalStateException}。
     *
     * @return 链上最终值（可能为 null）
     */
    @SuppressWarnings("unchecked")
    public T get() {
        if (!hasCondition) {
            throw new IllegalStateException(
                    "Branch 缺少条件分支：至少设置一个 when/elseIf/otherwise 后才能 get()");
        }
        Object current = seed;
        Object lastGood = seed;
        Function<Throwable, Object> recover = null;
        Consumer<Throwable> onError = null;
        CircuitBreakerFlow breaker = null;

        for (Stage stage : stages) {
            if (stage instanceof RecoverStage r) {
                recover = r.fallback();
            } else if (stage instanceof OnErrorStage o) {
                onError = o.handler();
            } else if (stage instanceof ProtectStage p) {
                breaker = p.flow();
            } else if (stage instanceof GroupStage g) {
                Object before = current;
                try {
                    current = evalGroup(g.group(), current, breaker, recover, onError);
                    lastGood = current;
                } catch (TerminatedSignal signal) {
                    return (T) lastGood;
                }
            }
        }
        return (T) current;
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

    /* ---------------- 内部：阶段登记 ---------------- */

    /**
     * 向开放组追加常规分支。
     */
    @SuppressWarnings("unchecked")
    private void addCase(Predicate<?> condition, Function<?, ?> action, boolean nullAware) {
        openGroup.cases.add(new Case((Predicate<Object>) condition,
                (Function<Object, Object>) action, nullAware));
    }

    /**
     * 向开放组追加内部签名分支（whenNull 用）。
     */
    private void addRawCase(Predicate<Object> condition, Function<?, ?> action, boolean nullAware) {
        openGroup.cases.add(new Case(condition, (Function<Object, Object>) action, nullAware));
    }

    /* ---------------- 内部求值 ---------------- */

    /**
     * 求值一个条件组，返回产出值。
     * 若被 onError 终止则抛出 {@link TerminatedSignal}（由 get() 统一捕获）。
     */
    private Object evalGroup(Group group, Object current,
                             CircuitBreakerFlow breaker,
                             Function<Throwable, Object> recover,
                             Consumer<Throwable> onError) {
        List<Case> cases = group.cases;
        // null 值：仅 nullAware 分支可介入，其余整组跳过
        if (current == null) {
            for (Case c : cases) {
                if (!c.nullAware()) {
                    continue;
                }
                return runProtected(() -> c.action().apply(null), breaker, recover, onError);
            }
            return current;
        }
        // 非 null：按序首中胜（nullAware 分支仅在值为 null 时有意义，此处跳过）
        for (Case c : cases) {
            if (c.nullAware() || !c.condition().test(current)) {
                continue;
            }
            return runProtected(() -> c.action().apply(current), breaker, recover, onError);
        }
        return current;
    }

    /**
     * 经熔断器执行动作并套接异常处理；
     * 被 onError 终止时抛出 {@link TerminatedSignal}。
     */
    private Object runProtected(java.util.concurrent.Callable<Object> action,
                                CircuitBreakerFlow breaker,
                                Function<Throwable, Object> recover,
                                Consumer<Throwable> onError) {
        try {
            return breaker != null ? breaker.execute(action) : action.call();
        } catch (RuntimeException | Error t) {
            if (recover != null) {
                return recover.apply(t);
            }
            if (onError != null) {
                onError.accept(t);
                throw new TerminatedSignal();
            }
            throw t;
        } catch (Exception e) {
            // 理论不可达：动作 lambda 与熔断器均不抛受检异常
            throw new IllegalStateException(e);
        }
    }

    /**
     * onError 终止链的内部信号（不会逃逸出 get()）。
     */
    private static final class TerminatedSignal extends Error {
    }

    /* ---------------- 工具方法 ---------------- */

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
     * @param v      待判定值
     * @param target 目标值
     * @return 相等返回 true
     */
    private static boolean isNumberEquals(Object v, double target) {
        return v instanceof Number n && n.doubleValue() == target;
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

    /**
     * 条件组视图：组内分支共享同一输入类型 T，任一分支命中后产出 R。
     * 通过 {@link #elseIf} 续加分支，{@link #otherwise} 收尾或 {@link #end}
     * 省略 else 后回到主线 {@link Branch}{@code <R>}。
     *
     * <p>亦可在组上直接注册 recover/onError/protect，作用域覆盖本组及其后步骤，
     * 注册后仍停留在组视图内。</p>
     *
     * @param <T> 组输入类型
     * @param <R> 组产出类型
     */
    public static final class WhenGroup<T, R> {

        /** 宿主链（裸类型操作内部结构）。 */
        private final Branch<R> owner;

        private WhenGroup(Branch<R> owner) {
            this.owner = owner;
        }

        /**
         * 追加分支；谓词与动作的入参均为组的原始输入类型 T。
         *
         * @param condition 触发条件
         * @param action    命中后的动作
         * @return 本组视图
         */
        @SuppressWarnings("unchecked")
        public WhenGroup<T, R> elseIf(Predicate<T> condition, Function<? super T, ? extends R> action) {
            owner.addCase(condition, action, false);
            return this;
        }

        /**
         * 以恒真分支收尾（等价 else），回到主线。
         *
         * @param action 兜底动作
         * @return 主线
         */
        @SuppressWarnings("unchecked")
        public Branch<R> otherwise(Function<? super T, ? extends R> action) {
            owner.addCase(v -> true, action, false);
            return owner;
        }

        /**
         * 省略 else，直接回到主线；组内无命中时透传原值。
         *
         * @return 主线
         */
        public Branch<R> end() {
            return owner;
        }

        /**
         * 在组上下文注册异常恢复（覆盖本组及其后步骤）。
         *
         * @param fallback 兜底函数
         * @return 本组视图
         */
        public WhenGroup<T, R> recover(Function<Throwable, R> fallback) {
            owner.stages.add(new RecoverStage((Function<Throwable, Object>) fallback));
            return this;
        }

        /**
         * 在组上下文注册异常出口（覆盖本组及其后步骤）。
         *
         * @param handler 异常消费者
         * @return 本组视图
         */
        public WhenGroup<T, R> onError(Consumer<Throwable> handler) {
            owner.stages.add(new OnErrorStage(handler));
            return this;
        }

        /**
         * 在组上下文挂接熔断保护（覆盖本组及其后步骤）。
         *
         * @param breakerName 熔断器名称
         * @return 本组视图
         */
        public WhenGroup<T, R> protect(String breakerName) {
            owner.stages.add(new ProtectStage(CircuitBreakerFlow.of(breakerName)));
            return this;
        }

        /**
         * 立即求值整条链（含本组）。
         *
         * @return 最终值
         */
        public R get() {
            return owner.get();
        }
    }
}
