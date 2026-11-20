package com.chua.common.support.condition;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * {@link Precondition.Try} 的默认实现：无异常持有值，有异常持有异常，
 * 每一步追加到共享的 {@link TraceLog}。
 *
 * @param <R> 成功时的值类型
 * @author CH
 * @since 4.0.0.53
 */
final class ExceptionBranch<R> implements Precondition.Try<R> {

    /**
     * 无异常时的值。
     */
    private final R value;

    /**
     * 有异常时的异常。
     */
    private final Exception error;

    /**
     * 链上共享的流程日志。
     */
    private final TraceLog log;

    ExceptionBranch(R value, Exception error, TraceLog log) {
        this.value = value;
        this.error = error;
        this.log = log;
    }

    @Override
    public boolean hasError() {
        return error != null;
    }

    @Override
    public boolean isSuccess() {
        return error == null;
    }

    @Override
    public R get() {
        return value;
    }

    @Override
    public Exception error() {
        return error;
    }

    @Override
    public void orElse(Consumer<Throwable> handler) {
        if (error != null) {
            log.add("orElse", null, Step.State.RUN, error);
            if (handler != null) {
                handler.accept(error);
            }
        } else {
            log.add("orElse", null, Step.State.SKIP, null);
        }
    }

    @Override
    public void otherwise(Consumer<Throwable> handler) {
        orElse(handler);
    }

    @Override
    public R orElseGet(Function<Throwable, ? extends R> fallback) {
        if (error == null) {
            log.add("orElseGet", null, Step.State.SKIP, null);
            return value;
        }
        R next = fallback == null ? null : fallback.apply(error);
        log.add("orElseGet", null, Step.State.RUN, next);
        return next;
    }

    @Override
    public R otherwiseGet(Function<Throwable, ? extends R> fallback) {
        return orElseGet(fallback);
    }

    @Override
    public R orElseGetDefault(Class<? super R> type) {
        if (error == null) {
            log.add("orElseGetDefault", null, Step.State.SKIP, null);
            return value;
        }
        R fallback = PreconditionDefaults.defaultValue(type);
        log.add("orElseGetDefault", null, Step.State.RUN, fallback);
        return fallback;
    }

    @Override
    public R orElseThrow() throws Throwable {
        if (error != null) {
            log.add("orElseThrow", null, Step.State.RUN, "throws " + error.getClass().getName());
            throw error;
        }
        log.add("orElseThrow", null, Step.State.SKIP, null);
        return value;
    }

    @Override
    public <X extends Throwable> R orElseThrow(Function<Throwable, ? extends X> mapper) throws X {
        if (error != null) {
            X thrown = Objects.requireNonNull(mapper, "mapper").apply(error);
            log.add("orElseThrow", null, Step.State.RUN,
                    "throws " + thrown.getClass().getName());
            throw thrown;
        }
        log.add("orElseThrow", null, Step.State.SKIP, null);
        return value;
    }

    @Override
    public Precondition.Try<R> peek(Consumer<? super R> action) {
        if (error == null) {
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
    public Precondition.Try<R> peekError(Consumer<Throwable> action) {
        if (error != null) {
            log.add("peekError", null, Step.State.RUN, error);
            if (action != null) {
                action.accept(error);
            }
        } else {
            log.add("peekError", null, Step.State.SKIP, null);
        }
        return this;
    }

    @Override
    public Stream<R> stream() {
        return error == null ? Stream.ofNullable(value) : Stream.empty();
    }

    @Override
    public String trace() {
        List<String> footers = new ArrayList<>();
        footers.add("value: " + value);
        footers.add(error == null ? "error: -" : "error: " + error);
        return TraceRenderer.render("Precondition.Try flow", log.steps(), footers);
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
        return "ExceptionBranch{success=" + (error == null) + ", value=" + value + ", error=" + error + '}';
    }
}
