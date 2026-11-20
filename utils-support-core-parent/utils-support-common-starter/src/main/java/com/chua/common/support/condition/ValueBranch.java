package com.chua.common.support.condition;

import java.io.PrintStream;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * {@link Precondition.Value} 的默认实现：条件成立持有产出值，不成立时由
 * orElse 系列兜底，每一步追加到共享的 {@link TraceLog}。
 *
 * @param <T> 候选类型
 * @param <R> 值类型
 * @author CH
 * @since 4.0.0.53
 */
final class ValueBranch<T, R> implements Precondition.Value<T, R> {

    /**
     * 条件是否成立。
     */
    private final boolean matched;

    /**
     * 候选对象。
     */
    private final T item;

    /**
     * 条件成立时的值。
     */
    private final R value;

    /**
     * 链上共享的流程日志。
     */
    private final TraceLog log;

    ValueBranch(boolean matched, T item, R value, TraceLog log) {
        this.matched = matched;
        this.item = item;
        this.value = value;
        this.log = log;
    }

    @Override
    public boolean isMatched() {
        return matched;
    }

    @Override
    public R get() {
        return value;
    }

    @Override
    public <R2> Precondition.Value<T, R2> ifTrueGet(Supplier<? extends R2> action) {
        if (matched && action != null) {
            R2 next = action.get();
            log.add("ifTrueGet", null, Step.State.RUN, next);
            return new ValueBranch<>(true, item, next, log);
        }
        log.add("ifTrueGet", null, Step.State.SKIP, null);
        return new ValueBranch<>(false, item, null, log);
    }

    @Override
    public <R2> Precondition.Value<T, R2> ifTrueGet(Function<? super T, ? extends R2> action) {
        if (matched && action != null) {
            R2 next = action.apply(item);
            log.add("ifTrueGet", null, Step.State.RUN, next);
            return new ValueBranch<>(true, item, next, log);
        }
        log.add("ifTrueGet", null, Step.State.SKIP, null);
        return new ValueBranch<>(false, item, null, log);
    }

    @Override
    public Precondition.Value<T, R> elseIf(boolean condition, Supplier<? extends R> action) {
        return elseIf((String) null, condition, action);
    }

    @Override
    public Precondition.Value<T, R> elseIf(Function<? super T, Boolean> condition,
                                           Function<? super T, ? extends R> action) {
        return elseIf((String) null, condition, action);
    }

    @Override
    public Precondition.Value<T, R> elseIf(String label, boolean condition, Supplier<? extends R> action) {
        if (matched) {
            log.add("elseIf", label, Step.State.SKIP, null);
            return this;
        }
        if (!condition) {
            log.add("elseIf", label, Step.State.MISS, null);
            return this;
        }
        R next = action == null ? null : action.get();
        log.add("elseIf", label, Step.State.HIT, next);
        return new ValueBranch<>(true, item, next, log);
    }

    @Override
    public Precondition.Value<T, R> elseIf(String label, Function<? super T, Boolean> condition,
                                           Function<? super T, ? extends R> action) {
        boolean hit = condition != null && Boolean.TRUE.equals(condition.apply(item));
        if (matched || !hit) {
            log.add("elseIf", label, matched ? Step.State.SKIP : Step.State.MISS, null);
            return this;
        }
        R next = action == null ? null : action.apply(item);
        log.add("elseIf", label, Step.State.HIT, next);
        return new ValueBranch<>(true, item, next, log);
    }

    @Override
    public R orElse(Supplier<? extends R> action) {
        if (matched) {
            log.add("orElse", null, Step.State.SKIP, null);
            return value;
        }
        R fallback = action == null ? null : action.get();
        log.add("orElse", null, Step.State.RUN, fallback);
        return fallback;
    }

    @Override
    public R orElse(Function<? super T, ? extends R> action) {
        if (matched) {
            log.add("orElse", null, Step.State.SKIP, null);
            return value;
        }
        R fallback = action == null ? null : action.apply(item);
        log.add("orElse", null, Step.State.RUN, fallback);
        return fallback;
    }

    @Override
    public R otherwise(Supplier<? extends R> action) {
        return orElse(action);
    }

    @Override
    public R otherwise(Function<? super T, ? extends R> action) {
        return orElse(action);
    }

    @Override
    public R orElseGetDefault(Class<? super R> type) {
        if (matched) {
            log.add("orElseGetDefault", null, Step.State.SKIP, null);
            return value;
        }
        R fallback = PreconditionDefaults.defaultValue(type);
        log.add("orElseGetDefault", null, Step.State.RUN, fallback);
        return fallback;
    }

    @Override
    public <X extends Throwable> R orElseThrow(Supplier<? extends X> exceptionSupplier) throws X {
        if (!matched) {
            X thrown = Objects.requireNonNull(exceptionSupplier, "exceptionSupplier").get();
            log.add("orElseThrow", null, Step.State.RUN,
                    "throws " + thrown.getClass().getName());
            throw thrown;
        }
        log.add("orElseThrow", null, Step.State.SKIP, null);
        return value;
    }

    @Override
    public Precondition.Value<T, R> peek(Consumer<? super R> action) {
        if (matched) {
            log.add("peek", null, Step.State.RUN, value);
            if (action != null) {
                action.accept(value);
            }
        } else {
            log.add("peek", null, Step.State.SKIP, null);
        }
        return this;
    }

    @Override
    public Stream<R> stream() {
        return matched ? Stream.ofNullable(value) : Stream.empty();
    }

    @Override
    public String trace() {
        return TraceRenderer.render("Precondition.Value flow", log.steps(), List.of("result: " + value));
    }

    @Override
    public void print() {
        System.out.println(trace());
    }

    @Override
    public void print(PrintStream out) {
        (out == null ? System.out : out).println(trace());
    }

    /**
     * 调试视图（紧凑单行，流程图请用 {@link #trace()}）。
     *
     * @return 描述
     */
    @Override
    public String toString() {
        return "ValueBranch{matched=" + matched + ", value=" + value + '}';
    }
}
