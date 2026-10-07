package com.chua.common.support.task.async;

import com.chua.common.support.collection.wrap.Wraps;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 异步任务链
 *
 * <p>链式任务的定义与执行门面：用 {@link #task(Supplier)} / {@link #task(Function)} /
 * {@link #task(Runnable)} / {@link #task(Consumer)} 追加任务步骤，步骤按声明顺序串行求值。
 * 与分支门面一致——<strong>链只定义一套（同步形态），执行分两种</strong>：
 * {@link #execute()} 当前线程直接执行并返回结果，
 * {@link #executeAsync()} 交给 {@link AsyncProvider} 执行并以 Reactor
 * 三个回调 {@code subscribe(onNext, onError, onComplete)} 交付结果。
 *
 * <p>步骤语义（方法名统一为 {@code task}，按任务的返回形态区分）：
 * <ul>
 *   <li>有返回值的任务（{@link #task(Supplier)} / {@link #task(Function)}）：产出新值，
 *       链的当前值随之流转，链类型也变为新值类型</li>
 *   <li>无返回值的任务（{@link #task(Runnable)} / {@link #task(Consumer)}）：
 *       只执行副作用，保留当前值与链类型不变</li>
 * </ul>
 *
 * <p>执行结果为与步骤一一对应的列表：每步记录其产出
 * （无返回值步骤记录其保留的当前值），空链返回空列表。
 *
 * <p>执行一次即冻结链：{@code execute} / {@code executeAsync} 调用后再追加
 * 步骤抛 {@link IllegalStateException}；执行本身可重复，
 * {@code executeAsync} 为冷流，每次订阅重新执行一遍。
 *
 * <p>使用示例：
 * <pre>{@code
 * Mono<List<Object>> mono = AsyncFlow.of()
 *         .task(() -> loadConfig())
 *         .task(cfg -> query(cfg))
 *         .task(rows -> { audit(rows); })
 *         .executeAsync();
 * mono.subscribe(onNext, onError, onComplete);
 * }</pre>
 *
 * @param <T> 链当前值类型
 * @author CH
 * @since 4.0.0.43
 */
public final class AsyncFlowChain<T> {

    /**
     * 底座门面（复用其异步提供者）
     */
    private final AsyncFlow flow;

    /**
     * 步骤序列（按声明顺序求值）
     */
    private final List<Function<Object, Object>> steps = Wraps.list();

    /**
     * 冻结标记：执行过一次后禁止再追加步骤
     */
    private boolean frozen;

    /**
     * 创建任务链（包内入口，由 {@link AsyncFlow#task(Supplier)} 等调用）。
     *
     * @param flow 底座门面
     */
    AsyncFlowChain(AsyncFlow flow) {
        this.flow = flow;
    }

    /**
     * 追加带返回值的任务（不消费当前值，产出新值）。
     *
     * @param <R>  新值类型
     * @param task 任务
     * @return 链（类型流转为 {@code R}）
     */
    public <R> AsyncFlowChain<R> task(Supplier<? extends R> task) {
        checkMutable();
        steps.add(prev -> task.get());
        return castTo();
    }

    /**
     * 追加消费当前值并产出新值的任务。
     *
     * @param <R>  新值类型
     * @param task 任务（参数为上一步产出）
     * @return 链（类型流转为 {@code R}）
     */
    public <R> AsyncFlowChain<R> task(Function<? super T, ? extends R> task) {
        checkMutable();
        steps.add(prev -> task.apply(castValue(prev)));
        return castTo();
    }

    /**
     * 追加无返回值的副作用任务（保留当前值）。
     *
     * @param task 任务
     * @return 链（类型与当前值不变）
     */
    public AsyncFlowChain<T> task(Runnable task) {
        checkMutable();
        steps.add(prev -> {
            task.run();
            return prev;
        });
        return this;
    }

    /**
     * 追加消费当前值的副作用任务（保留当前值）。
     *
     * @param task 任务（参数为上一步产出）
     * @return 链（类型与当前值不变）
     */
    public AsyncFlowChain<T> task(Consumer<? super T> task) {
        checkMutable();
        steps.add(prev -> {
            task.accept(castValue(prev));
            return prev;
        });
        return this;
    }

    /**
     * 同步执行：当前线程按声明顺序执行全部步骤。
     *
     * <p>任一步骤抛出异常立即中断，后续步骤不再执行，异常直接抛给调用方；
     * 调用后链被冻结（可重复执行）。
     *
     * @return 与步骤一一对应的结果列表
     */
    public List<Object> execute() {
        frozen = true;
        return run(List.copyOf(steps));
    }

    /**
     * 异步执行：步骤经 {@link AsyncFlow#getProvider()} 在提供者线程按序执行，
     * 结果以 Reactor 三个回调交付于 {@code boundedElastic} 调度线程。
     *
     * <p>回调语义：
     * <ul>
     *   <li>{@code onNext}：结果列表（与步骤一一对应）</li>
     *   <li>{@code onError}：任一步骤抛出异常，后续步骤不再执行</li>
     *   <li>{@code onComplete}：流正常结束</li>
     * </ul>
     *
     * <p>冷流：装配时即冻结链并快照步骤，每次订阅经提供者重新执行一遍。
     *
     * @return 结果列表的冷流
     */
    public Mono<List<Object>> executeAsync() {
        frozen = true;
        List<Function<Object, Object>> snapshot = List.copyOf(steps);
        return Mono.fromCompletionStage(() -> flow.getProvider().supply(() -> run(snapshot)))
                .publishOn(Schedulers.boundedElastic());
    }

    /**
     * 按序执行步骤快照，逐步记录产出。
     *
     * @param snapshot 步骤快照
     * @return 与步骤一一对应的结果列表
     */
    private List<Object> run(List<Function<Object, Object>> snapshot) {
        List<Object> results = Wraps.list(snapshot.size());
        Object value = null;
        for (Function<Object, Object> step : snapshot) {
            value = step.apply(value);
            results.add(value);
        }
        return results;
    }

    /**
     * 执行后禁止再追加步骤。
     */
    private void checkMutable() {
        if (frozen) {
            throw new IllegalStateException("任务链已执行，不能再追加步骤");
        }
    }

    /**
     * 将当前链按新值类型引用（链类型仅编译期生效，步骤运行时逐步流转）。
     *
     * @param <R> 新值类型
     * @return 当前链的新类型引用
     */
    @SuppressWarnings("unchecked")
    private <R> AsyncFlowChain<R> castTo() {
        return (AsyncFlowChain<R>) this;
    }

    /**
     * 将当前值按链类型还原（由本链步骤按序流转而来）。
     *
     * @return 当前值
     */
    @SuppressWarnings("unchecked")
    private T castValue(Object value) {
        return (T) value;
    }
}
