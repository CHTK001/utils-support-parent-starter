package com.chua.common.support.condition;

import java.io.PrintStream;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 候选资格判断 —— 「这个对象现在有没有资格参与调用」。
 *
 * <p>与冷却的分工：资格失败是<b>配置 / 状态问题</b>（渠道停用、模型下线、
 * 账号过期、额度为 0），不该记冷却；冷却失败是<b>上游临时拒绝</b>（429、
 * 额度耗尽），要等一段时间再回来。两者混在一起，一个配置错误就会把整池账号
 * 全打成冷却。</p>
 *
 * <p>可组合：{@code Precondition.and(a, b)} / {@code a.and(b).negate()}。</p>
 *
 * <p>同时也是 if/else 链式分支的统一入口：静态方法把一个已判定的条件包装成
 * 分支执行器（{@link Branch} / {@link Value} / {@link Try}），用
 * {@code ifTrue / elseIf / orElse} 代替 if/else，用 {@code tryGet / tryRun}
 * 把「有异常」当作「条件不成立」处理。</p>
 *
 * <pre>{@code
 * // 布尔条件，无返回：代替 if/else
 * Precondition.ifTrue(balance >= price)
 *         .ifTrue(() -> account.pay(price))
 *         .orElse(() -> notifyInsufficient());
 *
 * // 候选对象 + 资格条件：分支里能拿到对象本身
 * Precondition.of(user, Precondition.notNull(User::getEmail))
 *         .ifTrue(u -> mailer.send(u))
 *         .otherwise(u -> log.warn("用户无邮箱: {}", u.getId()));
 *
 * // 有返回：代替 if/else 赋值与三元表达式
 * String level = Precondition.ifTrue(score >= 60)
 *         .ifTrueGet(() -> "PASS")
 *         .orElse(() -> "FAIL");
 *
 * // 异常即条件：无异常拿到精确值，有异常 orElse 继续兜底
 * LocalDateTime time = Precondition.tryGet(() -> LocalDateTime.parse(text))
 *         .orElseGet(ex -> parseByNlp(text));
 * }</pre>
 *
 * <p>分支执行支持流程打印：链上每一步的命中情况都会被记录，
 * {@code print()} / {@code trace()} 输出竖向流程图，便于排查
 * 「为什么走了这个分支」。带 label 的入口 / elseIf 重载可以在流程图里
 * 显示业务可读的条件名：</p>
 *
 * <pre>{@code
 * Precondition.ifTrue("score>=60", score >= 60)
 *         .elseIf("vip", isVip, () -> giveDiscount())
 *         .orElse(() -> normalPrice())
 *         .print();
 * }</pre>
 *
 * @param <T> 候选类型
 * @author CH
 * @since 4.0.0.53
 */
@FunctionalInterface
public interface Precondition<T> {

    /**
     * 判断候选是否有资格。
     *
     * @param item 候选
     * @return true 表示有资格
     */
    boolean test(T item);

    /**
     * 与另一个条件取交集（短路）。
     *
     * @param other 另一个条件
     * @return 组合条件
     */
    default Precondition<T> and(Precondition<? super T> other) {
        return item -> test(item) && other.test(item);
    }

    /**
     * 与另一个条件取并集（短路）。
     *
     * @param other 另一个条件
     * @return 组合条件
     */
    default Precondition<T> or(Precondition<? super T> other) {
        return item -> test(item) || other.test(item);
    }

    /**
     * 取反。
     *
     * @return 取反后的条件
     */
    default Precondition<T> negate() {
        return item -> !test(item);
    }

    /**
     * 恒为真。
     *
     * @param <T> 候选类型
     * @return 条件
     */
    static <T> Precondition<T> always() {
        return item -> true;
    }

    /**
     * 恒为假（用于临时全禁）。
     *
     * @param <T> 候选类型
     * @return 条件
     */
    static <T> Precondition<T> never() {
        return item -> false;
    }

    /**
     * 取值的条件：提取的字段非 null 即通过。
     *
     * @param getter 字段提取器
     * @param <T>    候选类型
     * @param <V>    字段类型
     * @return 条件
     */
    static <T, V> Precondition<T> notNull(Function<? super T, ? extends V> getter) {
        return item -> item != null && getter.apply(item) != null;
    }

    /**
     * 布尔字段的条件：提取值为 {@link Boolean#TRUE} 才通过。
     *
     * @param getter 字段提取器
     * @param <T>    候选类型
     * @return 条件
     */
    static <T> Precondition<T> isTrue(Function<? super T, Boolean> getter) {
        return item -> item != null && Boolean.TRUE.equals(getter.apply(item));
    }

    // ------------------------------------------------------------------
    // 分支入口：代替 if / else
    // ------------------------------------------------------------------

    /**
     * 以一个布尔条件进入分支执行：条件成立走 ifTrue，不成立走 orElse / otherwise。
     *
     * @param condition 布尔条件
     * @param <T>       候选类型（通常无需指定，按调用点推断）
     * @return 分支执行器
     */
    static <T> Branch<T> of(boolean condition) {
        return of((String) null, condition);
    }

    /**
     * 以一个带业务标签的布尔条件进入分支执行，标签会出现在
     * {@code print()} / {@code trace()} 的流程图里。
     *
     * @param label     条件标签
     * @param condition 布尔条件
     * @param <T>       候选类型（通常无需指定，按调用点推断）
     * @return 分支执行器
     */
    static <T> Branch<T> of(String label, boolean condition) {
        TraceLog log = new TraceLog();
        log.add("if", label, condition ? Step.State.HIT : Step.State.MISS, null);
        return new BooleanBranch<>(condition, null, log);
    }

    /**
     * 以「候选对象 + 资格条件」进入分支执行，分支内可拿到候选对象。
     *
     * @param item      候选对象
     * @param condition 资格条件
     * @param <T>       候选类型
     * @return 分支执行器
     */
    static <T> Branch<T> of(T item, Precondition<? super T> condition) {
        boolean matched = condition != null && condition.test(item);
        TraceLog log = new TraceLog();
        log.add("of", null, matched ? Step.State.HIT : Step.State.MISS, null);
        return new BooleanBranch<>(matched, item, log);
    }

    /**
     * 以一个布尔条件进入分支执行，{@link #of(boolean)} 的别名。
     *
     * @param condition 布尔条件
     * @param <T>       候选类型（通常无需指定，按调用点推断）
     * @return 分支执行器
     */
    static <T> Branch<T> ifTrue(boolean condition) {
        return of((String) null, condition);
    }

    /**
     * 以一个带业务标签的布尔条件进入分支执行，{@link #of(String, boolean)} 的别名。
     *
     * @param label     条件标签
     * @param condition 布尔条件
     * @param <T>       候选类型（通常无需指定，按调用点推断）
     * @return 分支执行器
     */
    static <T> Branch<T> ifTrue(String label, boolean condition) {
        return of(label, condition);
    }

    /**
     * 以「值非空」为条件进入分支执行，分支内可拿到该值。
     *
     * @param value 待判断的值
     * @param <T>   值类型（通常无需指定，按调用点推断）
     * @return 分支执行器
     */
    static <T> Branch<T> ifNotNull(T value) {
        return ifNotNull((String) null, value);
    }

    /**
     * 以「带业务标签的值非空」为条件进入分支执行，分支内可拿到该值。
     *
     * @param label 条件标签
     * @param value 待判断的值
     * @param <T>   值类型（通常无需指定，按调用点推断）
     * @return 分支执行器
     */
    static <T> Branch<T> ifNotNull(String label, T value) {
        boolean matched = value != null;
        TraceLog log = new TraceLog();
        log.add("ifNotNull", label, matched ? Step.State.HIT : Step.State.MISS, null);
        return new BooleanBranch<>(matched, value, log);
    }

    /**
     * 用当前条件判断候选对象，进入分支执行；分支内可拿到候选对象。
     *
     * @param item 候选对象
     * @return 分支执行器
     */
    default Branch<T> on(T item) {
        boolean matched = test(item);
        TraceLog log = new TraceLog();
        log.add("on", null, matched ? Step.State.HIT : Step.State.MISS, null);
        return new BooleanBranch<>(matched, item, log);
    }

    /**
     * 尝试执行一个动作（异常即条件）：无异常 = 条件成立，有异常 = 条件不成立，
     * 由 {@code orElse / otherwise} 继续。只捕获 {@link Exception}，Error 照常上抛。
     *
     * @param action 可抛异常的动作
     * @return 执行结果
     */
    static Try<Void> tryRun(ThrowingRunnable action) {
        TraceLog log = new TraceLog();
        try {
            if (action != null) {
                action.run();
            }
            log.add("tryRun", null, Step.State.HIT, null);
            return new ExceptionBranch<>(null, null, log);
        } catch (Exception e) {
            log.add("tryRun", null, Step.State.MISS, e);
            return new ExceptionBranch<>(null, e, log);
        }
    }

    /**
     * 尝试取一个值（异常即条件）：无异常 = 条件成立（拿到值），有异常 = 条件不成立，
     * 由 {@code orElseGet / otherwiseGet / orElseGetDefault} 兜底。
     * 只捕获 {@link Exception}，Error 照常上抛。
     *
     * @param action 可抛异常的取值动作
     * @param <R>    值类型
     * @return 执行结果
     */
    static <R> Try<R> tryGet(ThrowingSupplier<? extends R> action) {
        TraceLog log = new TraceLog();
        try {
            R value = action == null ? null : action.get();
            log.add("tryGet", null, Step.State.HIT, null);
            return new ExceptionBranch<>(value, null, log);
        } catch (Exception e) {
            log.add("tryGet", null, Step.State.MISS, e);
            return new ExceptionBranch<>(null, e, log);
        }
    }

    /**
     * 可抛异常的动作：让 lambda 免写 try/catch。
     */
    @FunctionalInterface
    interface ThrowingRunnable {

        /**
         * 执行动作。
         *
         * @throws Exception 执行中可能抛出的异常
         */
        void run() throws Exception;
    }

    /**
     * 可抛异常的取值：让 lambda 免写 try/catch。
     *
     * @param <R> 值类型
     */
    @FunctionalInterface
    interface ThrowingSupplier<R> {

        /**
         * 取值。
         *
         * @return 值
         * @throws Exception 执行中可能抛出的异常
         */
        R get() throws Exception;
    }

    /**
     * 无返回分支：条件成立走 ifTrue，不成立走 orElse / otherwise；
     * 支持 elseIf 串联 else-if 阶梯、peek 观察、stream 桥接。
     *
     * <p>按链式顺序求值，等价于顺序的 if / else if / else：
     * 链首至多一次 {@code ifTrue / ifFalse}，其后接若干 {@code elseIf}，
     * 以 {@code orElse / otherwise} 收尾；前面分支命中后，后续 elseIf 与
     * orElse 不再执行。</p>
     *
     * @param <T> 候选类型
     */
    interface Branch<T> {

        /**
         * 条件成立时执行动作（不需要候选对象）。
         *
         * @param action 动作
         * @return 当前分支，可继续链式
         */
        Branch<T> ifTrue(Runnable action);

        /**
         * 条件成立时执行动作，动作可拿到候选对象。
         *
         * @param action 动作
         * @return 当前分支，可继续链式
         */
        Branch<T> ifTrue(Consumer<? super T> action);

        /**
         * 条件不成立时执行动作（不需要候选对象）。
         *
         * @param action 动作
         * @return 当前分支，可继续链式
         */
        Branch<T> ifFalse(Runnable action);

        /**
         * 条件不成立时执行动作，动作可拿到候选对象。
         *
         * @param action 动作
         * @return 当前分支，可继续链式
         */
        Branch<T> ifFalse(Consumer<? super T> action);

        /**
         * 条件成立时取值，把无返回分支转为有返回结果，可继续接 elseIf / orElse / otherwise。
         *
         * @param action 取值动作
         * @param <R>    值类型
         * @return 条件结果
         */
        <R> Value<T, R> ifTrueGet(Supplier<? extends R> action);

        /**
         * 条件成立时取值（动作可拿到候选对象），把无返回分支转为有返回结果，
         * 可继续接 elseIf / orElse / otherwise。
         *
         * @param action 取值动作
         * @param <R>    值类型
         * @return 条件结果
         */
        <R> Value<T, R> ifTrueGet(Function<? super T, ? extends R> action);

        /**
         * else-if：前面的分支都未命中且 condition 成立时执行动作，
         * 等价于 if/else if 中的 else if。
         *
         * @param condition else-if 条件
         * @param action    动作
         * @return 当前分支（本分支已命中时）或命中后的新分支，可继续链式
         */
        Branch<T> elseIf(boolean condition, Runnable action);

        /**
         * else-if：前面的分支都未命中且 condition 成立时执行动作，动作可拿到候选对象。
         *
         * @param condition else-if 条件
         * @param action    动作
         * @return 当前分支（本分支已命中时）或命中后的新分支，可继续链式
         */
        Branch<T> elseIf(boolean condition, Consumer<? super T> action);

        /**
         * else-if：前面的分支都未命中且条件对候选对象成立时执行动作。
         *
         * @param condition 基于候选对象的 else-if 条件
         * @param action    动作
         * @return 当前分支（本分支已命中时）或命中后的新分支，可继续链式
         */
        Branch<T> elseIf(Function<? super T, Boolean> condition, Consumer<? super T> action);

        /**
         * 带标签的 else-if：标签会出现在 {@code print()} / {@code trace()} 的流程图里。
         *
         * @param label     条件标签
         * @param condition else-if 条件
         * @param action    动作
         * @return 当前分支（本分支已命中时）或命中后的新分支，可继续链式
         */
        Branch<T> elseIf(String label, boolean condition, Runnable action);

        /**
         * 带标签的 else-if：标签会出现在 {@code print()} / {@code trace()} 的流程图里。
         *
         * @param label     条件标签
         * @param condition else-if 条件
         * @param action    动作
         * @return 当前分支（本分支已命中时）或命中后的新分支，可继续链式
         */
        Branch<T> elseIf(String label, boolean condition, Consumer<? super T> action);

        /**
         * 带标签的 else-if：标签会出现在 {@code print()} / {@code trace()} 的流程图里。
         *
         * @param label     条件标签
         * @param condition 基于候选对象的 else-if 条件
         * @param action    动作
         * @return 当前分支（本分支已命中时）或命中后的新分支，可继续链式
         */
        Branch<T> elseIf(String label, Function<? super T, Boolean> condition, Consumer<? super T> action);

        /**
         * 否则分支：条件不成立时执行动作（代替 if/else 的 else，无返回）。
         *
         * @param action else 动作
         */
        void orElse(Runnable action);

        /**
         * 否则分支：条件不成立时执行动作，动作可拿到候选对象。
         *
         * @param action else 动作
         */
        void orElse(Consumer<? super T> action);

        /**
         * 否则分支，{@link #orElse(Runnable)} 的别名。
         *
         * @param action else 动作
         */
        void otherwise(Runnable action);

        /**
         * 否则分支，{@link #orElse(Consumer)} 的别名。
         *
         * @param action else 动作
         */
        void otherwise(Consumer<? super T> action);

        /**
         * 观察候选对象（条件成立时），不改变流向，可继续链式。
         *
         * @param action 观察动作
         * @return 当前分支
         */
        Branch<T> peek(Consumer<? super T> action);

        /**
         * 桥接 Stream：条件成立且候选对象非 null 时给 1 个元素，否则空流
         * （纯布尔条件没有候选对象，流恒为空）。
         *
         * @return 0..1 个元素的流
         */
        Stream<T> stream();

        /**
         * 条件是否成立。
         *
         * @return true 表示成立
         */
        boolean isMatched();

        /**
         * 候选对象，纯布尔条件时为 null。
         *
         * @return 候选对象
         */
        T item();

        /**
         * 渲染本次分支执行流程（含每一步的命中情况与最终是否命中）。
         *
         * @return 多行流程文本
         */
        String trace();

        /**
         * 将本次分支执行流程打印到标准输出。
         */
        void print();

        /**
         * 将本次分支执行流程打印到指定输出流。
         *
         * @param out 输出流，为 null 时打印到标准输出
         */
        void print(PrintStream out);
    }

    /**
     * 有返回分支：条件成立持有 {@code ifTrueGet} 产出的值，不成立时由
     * {@code orElse / otherwise / orElseGetDefault} 产出兜底值；
     * 支持 elseIf 串联、peek 观察、stream 桥接。
     *
     * @param <T> 候选类型
     * @param <R> 值类型
     */
    interface Value<T, R> {

        /**
         * 条件成立时取值（不依赖候选对象）。
         *
         * @param action 取值动作
         * @param <R2>   新值类型
         * @return 条件结果，可继续接 orElse / otherwise
         */
        <R2> Value<T, R2> ifTrueGet(Supplier<? extends R2> action);

        /**
         * 条件成立时取值，取值动作可拿到候选对象。
         *
         * @param action 取值动作
         * @param <R2>   新值类型
         * @return 条件结果，可继续接 orElse / otherwise
         */
        <R2> Value<T, R2> ifTrueGet(Function<? super T, ? extends R2> action);

        /**
         * else-if 取值：本分支未命中且 condition 成立时用 action 产出值。
         *
         * @param condition else-if 条件
         * @param action    取值动作
         * @return 本分支（已命中时）或命中后的新分支
         */
        Value<T, R> elseIf(boolean condition, Supplier<? extends R> action);

        /**
         * else-if 取值：本分支未命中且条件对候选对象成立时用 action 产出值。
         *
         * @param condition 基于候选对象的 else-if 条件
         * @param action    取值动作
         * @return 本分支（已命中时）或命中后的新分支
         */
        Value<T, R> elseIf(Function<? super T, Boolean> condition, Function<? super T, ? extends R> action);

        /**
         * 带标签的 else-if 取值：标签会出现在 {@code print()} / {@code trace()} 的流程图里。
         *
         * @param label     条件标签
         * @param condition else-if 条件
         * @param action    取值动作
         * @return 本分支（已命中时）或命中后的新分支
         */
        Value<T, R> elseIf(String label, boolean condition, Supplier<? extends R> action);

        /**
         * 带标签的 else-if 取值：标签会出现在 {@code print()} / {@code trace()} 的流程图里。
         *
         * @param label     条件标签
         * @param condition 基于候选对象的 else-if 条件
         * @param action    取值动作
         * @return 本分支（已命中时）或命中后的新分支
         */
        Value<T, R> elseIf(String label, Function<? super T, Boolean> condition,
                           Function<? super T, ? extends R> action);

        /**
         * 否则取值：条件不成立时用 action 产出兜底值（不依赖候选对象）。
         *
         * @param action 兜底取值动作
         * @return 条件成立时的原值；不成立时的兜底值
         */
        R orElse(Supplier<? extends R> action);

        /**
         * 否则取值：条件不成立时用 action 产出兜底值，动作可拿到候选对象。
         *
         * @param action 兜底取值动作
         * @return 条件成立时的原值；不成立时的兜底值
         */
        R orElse(Function<? super T, ? extends R> action);

        /**
         * 否则取值，{@link #orElse(Supplier)} 的别名。
         *
         * @param action 兜底取值动作
         * @return 条件成立时的原值；不成立时的兜底值
         */
        R otherwise(Supplier<? extends R> action);

        /**
         * 否则取值，{@link #orElse(Function)} 的别名。
         *
         * @param action 兜底取值动作
         * @return 条件成立时的原值；不成立时的兜底值
         */
        R otherwise(Function<? super T, ? extends R> action);

        /**
         * 条件不成立时返回 JDK 内置类型的默认值（数值 0、String ""、空集合等）；
         * 条件成立时原样返回值。仅覆盖 JDK 内置类型，未登记的类型（含业务类型）
         * 返回 null，不做反射实例化。
         *
         * @param type 返回类型（可传其父类型，如 {@code List.class}）
         * @return 条件成立时的原值；不成立时的 JDK 默认值
         */
        R orElseGetDefault(Class<? super R> type);

        /**
         * 条件不成立时抛出异常。
         *
         * @param exceptionSupplier 异常供给
         * @param <X>               异常类型
         * @return 条件成立时的值
         * @throws X 条件不成立时抛出
         */
        <X extends Throwable> R orElseThrow(Supplier<? extends X> exceptionSupplier) throws X;

        /**
         * 观察结果值（条件成立时），不改变流向，可继续链式。
         *
         * @param action 观察动作
         * @return 当前结果
         */
        Value<T, R> peek(Consumer<? super R> action);

        /**
         * 桥接 Stream：条件成立时给 1 个结果元素，否则空流。
         *
         * @return 0..1 个元素的流
         */
        Stream<R> stream();

        /**
         * 条件是否成立。
         *
         * @return true 表示成立
         */
        boolean isMatched();

        /**
         * 条件成立时的值，不成立时为 null。
         *
         * @return 值
         */
        R get();

        /**
         * 渲染本次取值分支流程（含每一步的命中情况与最终结果）。
         *
         * @return 多行流程文本
         */
        String trace();

        /**
         * 将本次取值分支流程打印到标准输出。
         */
        void print();

        /**
         * 将本次取值分支流程打印到指定输出流。
         *
         * @param out 输出流，为 null 时打印到标准输出
         */
        void print(PrintStream out);
    }

    /**
     * 异常条件的执行结果：无异常 = 条件成立（值在 {@link #get()}）；
     * 有异常 = 条件不成立，由 {@code orElse / otherwise} 系列继续。
     *
     * @param <R> 成功时的值类型
     */
    interface Try<R> {

        /**
         * 是否有异常（条件不成立）。
         *
         * @return true 表示有异常
         */
        boolean hasError();

        /**
         * 是否无异常（条件成立）。
         *
         * @return true 表示无异常
         */
        boolean isSuccess();

        /**
         * 无异常时的值（可能为 null）；有异常时恒为 null。
         *
         * @return 值
         */
        R get();

        /**
         * 有异常时的异常对象，无异常时为 null。
         *
         * @return 异常
         */
        Exception error();

        /**
         * 有异常时执行处理（无返回），异常对象交给 handler。
         *
         * @param handler 异常处理动作
         */
        void orElse(Consumer<Throwable> handler);

        /**
         * 有异常时执行处理（无返回），{@link #orElse(Consumer)} 的别名。
         *
         * @param handler 异常处理动作
         */
        void otherwise(Consumer<Throwable> handler);

        /**
         * 有异常时取兜底值（有返回），异常对象交给 fallback。
         *
         * @param fallback 兜底取值动作
         * @return 无异常时的原值；有异常时的兜底值
         */
        R orElseGet(Function<Throwable, ? extends R> fallback);

        /**
         * 有异常时取兜底值（有返回），{@link #orElseGet(Function)} 的别名。
         *
         * @param fallback 兜底取值动作
         * @return 无异常时的原值；有异常时的兜底值
         */
        R otherwiseGet(Function<Throwable, ? extends R> fallback);

        /**
         * 有异常时返回 JDK 内置类型的默认值（数值 0、String ""、空集合等）；
         * 无异常时原样返回值。仅覆盖 JDK 内置类型，未登记的类型（含业务类型）
         * 返回 null，不做反射实例化。
         *
         * @param type 返回类型（可传其父类型，如 {@code List.class}）
         * @return 无异常时的原值；有异常时的 JDK 默认值
         */
        R orElseGetDefault(Class<? super R> type);

        /**
         * 有异常时原样抛出。
         *
         * @return 无异常时的值
         * @throws Throwable 有异常时抛出原异常
         */
        R orElseThrow() throws Throwable;

        /**
         * 有异常时转换后抛出。
         *
         * @param mapper 异常转换
         * @param <X>    目标异常类型
         * @return 无异常时的值
         * @throws X 转换后的异常
         */
        <X extends Throwable> R orElseThrow(Function<Throwable, ? extends X> mapper) throws X;

        /**
         * 观察成功值，不改变流向，可继续链式。
         *
         * @param action 观察动作
         * @return 当前结果
         */
        Try<R> peek(Consumer<? super R> action);

        /**
         * 观察异常（有异常时），不改变流向，可继续链式。
         *
         * @param action 观察动作
         * @return 当前结果
         */
        Try<R> peekError(Consumer<Throwable> action);

        /**
         * 桥接 Stream：无异常时给 1 个值元素，有异常时空流。
         *
         * @return 0..1 个元素的流
         */
        Stream<R> stream();

        /**
         * 渲染本次异常条件执行流程（含每一步的命中情况、最终值或异常）。
         *
         * @return 多行流程文本
         */
        String trace();

        /**
         * 将本次异常条件执行流程打印到标准输出。
         */
        void print();

        /**
         * 将本次异常条件执行流程打印到指定输出流。
         *
         * @param out 输出流，为 null 时打印到标准输出
         */
        void print(PrintStream out);
    }
}
