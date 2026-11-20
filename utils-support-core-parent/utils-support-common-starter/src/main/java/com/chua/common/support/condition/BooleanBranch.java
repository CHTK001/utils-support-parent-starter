package com.chua.common.support.condition;

import java.io.PrintStream;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * {@link Precondition.Branch} 的默认实现：持有条件判定结果与候选对象，按链式顺序求值，
 * 每一步追加到共享的 {@link TraceLog}。
 *
 * @param <T> 候选类型
 * @author CH
 * @since 4.0.0.53
 */
final class BooleanBranch<T> implements Precondition.Branch<T> {

    /**
     * 条件是否成立。
     */
    private final boolean matched;

    /**
     * 候选对象，纯布尔条件时为 null。
     */
    private final T item;

    /**
     * 链上共享的流程日志。
     */
    private final TraceLog log;

    BooleanBranch(boolean matched, T item, TraceLog log) {
        this.matched = matched;
        this.item = item;
        this.log = log;
    }

    @Override
    public boolean isMatched() {
        return matched;
    }

    @Override
    public T item() {
        return item;
    }

    @Override
    public Precondition.Branch<T> ifTrue(Runnable action) {
        if (matched) {
            log.add("ifTrue", null, Step.State.RUN, null);
            if (action != null) {
                action.run();
            }
        } else {
            log.add("ifTrue", null, Step.State.SKIP, null);
        }
        return this;
    }

    @Override
    public Precondition.Branch<T> ifTrue(Consumer<? super T> action) {
        if (matched) {
            log.add("ifTrue", null, Step.State.RUN, null);
            if (action != null) {
                action.accept(item);
            }
        } else {
            log.add("ifTrue", null, Step.State.SKIP, null);
        }
        return this;
    }

    @Override
    public Precondition.Branch<T> ifFalse(Runnable action) {
        if (!matched) {
            log.add("ifFalse", null, Step.State.RUN, null);
            if (action != null) {
                action.run();
            }
        } else {
            log.add("ifFalse", null, Step.State.SKIP, null);
        }
        return this;
    }

    @Override
    public Precondition.Branch<T> ifFalse(Consumer<? super T> action) {
        if (!matched) {
            log.add("ifFalse", null, Step.State.RUN, null);
            if (action != null) {
                action.accept(item);
            }
        } else {
            log.add("ifFalse", null, Step.State.SKIP, null);
        }
        return this;
    }

    @Override
    public <R> Precondition.Value<T, R> ifTrueGet(Supplier<? extends R> action) {
        if (matched && action != null) {
            R next = action.get();
            log.add("ifTrueGet", null, Step.State.RUN, next);
            return new ValueBranch<>(true, item, next, log);
        }
        log.add("ifTrueGet", null, Step.State.SKIP, null);
        return new ValueBranch<>(false, item, null, log);
    }

    @Override
    public <R> Precondition.Value<T, R> ifTrueGet(Function<? super T, ? extends R> action) {
        if (matched && action != null) {
            R next = action.apply(item);
            log.add("ifTrueGet", null, Step.State.RUN, next);
            return new ValueBranch<>(true, item, next, log);
        }
        log.add("ifTrueGet", null, Step.State.SKIP, null);
        return new ValueBranch<>(false, item, null, log);
    }

    @Override
    public Precondition.Branch<T> elseIf(boolean condition, Runnable action) {
        return elseIf((String) null, condition, action);
    }

    @Override
    public Precondition.Branch<T> elseIf(boolean condition, Consumer<? super T> action) {
        return elseIf((String) null, condition, action);
    }

    @Override
    public Precondition.Branch<T> elseIf(Function<? super T, Boolean> condition, Consumer<? super T> action) {
        boolean hit = condition != null && Boolean.TRUE.equals(condition.apply(item));
        if (matched || !hit) {
            log.add("elseIf", null, matched ? Step.State.SKIP : Step.State.MISS, null);
            return this;
        }
        log.add("elseIf", null, Step.State.HIT, null);
        if (action != null) {
            action.accept(item);
        }
        return new BooleanBranch<>(true, item, log);
    }

    @Override
    public Precondition.Branch<T> elseIf(String label, boolean condition, Runnable action) {
        if (matched) {
            log.add("elseIf", label, Step.State.SKIP, null);
            return this;
        }
        if (!condition) {
            log.add("elseIf", label, Step.State.MISS, null);
            return this;
        }
        log.add("elseIf", label, Step.State.HIT, null);
        if (action != null) {
            action.run();
        }
        return new BooleanBranch<>(true, item, log);
    }

    @Override
    public Precondition.Branch<T> elseIf(String label, boolean condition, Consumer<? super T> action) {
        if (matched) {
            log.add("elseIf", label, Step.State.SKIP, null);
            return this;
        }
        if (!condition) {
            log.add("elseIf", label, Step.State.MISS, null);
            return this;
        }
        log.add("elseIf", label, Step.State.HIT, null);
        if (action != null) {
            action.accept(item);
        }
        return new BooleanBranch<>(true, item, log);
    }

    @Override
    public Precondition.Branch<T> elseIf(String label, Function<? super T, Boolean> condition,
                                         Consumer<? super T> action) {
        boolean hit = condition != null && Boolean.TRUE.equals(condition.apply(item));
        if (matched || !hit) {
            log.add("elseIf", label, matched ? Step.State.SKIP : Step.State.MISS, null);
            return this;
        }
        log.add("elseIf", label, Step.State.HIT, null);
        if (action != null) {
            action.accept(item);
        }
        return new BooleanBranch<>(true, item, log);
    }

    @Override
    public void orElse(Runnable action) {
        if (!matched) {
            log.add("orElse", null, Step.State.RUN, null);
            if (action != null) {
                action.run();
            }
        } else {
            log.add("orElse", null, Step.State.SKIP, null);
        }
    }

    @Override
    public void orElse(Consumer<? super T> action) {
        if (!matched) {
            log.add("orElse", null, Step.State.RUN, null);
            if (action != null) {
                action.accept(item);
            }
        } else {
            log.add("orElse", null, Step.State.SKIP, null);
        }
    }

    @Override
    public void otherwise(Runnable action) {
        orElse(action);
    }

    @Override
    public void otherwise(Consumer<? super T> action) {
        orElse(action);
    }

    @Override
    public Precondition.Branch<T> peek(Consumer<? super T> action) {
        if (matched) {
            log.add("peek", null, Step.State.RUN, null);
            if (action != null) {
                action.accept(item);
            }
        } else {
            log.add("peek", null, Step.State.SKIP, null);
        }
        return this;
    }

    @Override
    public Stream<T> stream() {
        return matched ? Stream.ofNullable(item) : Stream.empty();
    }

    @Override
    public String trace() {
        return TraceRenderer.render("Precondition.Branch flow", log.steps(), List.of("matched: " + matched));
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
        return "BooleanBranch{matched=" + matched + ", item=" + item + '}';
    }
}
