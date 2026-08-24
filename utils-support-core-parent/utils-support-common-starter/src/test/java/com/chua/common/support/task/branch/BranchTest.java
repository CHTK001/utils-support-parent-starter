package com.chua.common.support.task.branch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Branch} 单元测试。
 *
 * @author CH
 * @since 4.0.0.42
 */
class BranchTest {

    @Test
    void lazyUntilTerminal() {
        AtomicInteger invoked = new AtomicInteger();
        Branch<String> branch = Branch.of("a")
                .when(v -> true, v -> {
                    invoked.incrementAndGet();
                    return v + "!";
                });
        assertEquals(0, invoked.get(), "终端调用前不应执行任何动作");
        assertEquals("a!", branch.get());
        assertEquals(1, invoked.get());
    }

    @Test
    void whenHitAndMiss() {
        assertEquals("big", Branch.of(10)
                .when(v -> (int) v > 5, v -> "big")
                .otherwise(v -> "small")
                .get());
        assertEquals("small", Branch.of(1)
                .when(v -> (int) v > 5, v -> "big")
                .otherwise(v -> "small")
                .get());
    }

    @Test
    void firstMatchWinsInGroup() {
        String r = Branch.of(5)
                .when(v -> (int) v > 10, v -> ">10")
                .elseIf(v -> (int) v > 3, v -> ">3")
                .elseIf(v -> (int) v > 1, v -> ">1")
                .otherwise(v -> "other")
                .get();
        assertEquals(">3", r);
    }

    @Test
    void otherwiseWithoutWhenFails() {
        assertThrows(IllegalStateException.class, () -> Branch.of(1).otherwise(v -> 2).get());
    }

    @Test
    void elseIfAfterOtherwiseSealedFails() {
        assertThrows(IllegalStateException.class,
                () -> Branch.of(1).when(v -> false, v -> v).otherwise(v -> v).elseIf(v -> true, v -> v));
    }

    @Test
    void nullImmunityPredicateNeverInvoked() {
        AtomicBoolean predicateCalled = new AtomicBoolean(false);
        Integer r = Branch.<Integer>of(null)
                .when(v -> {
                    predicateCalled.set(true);
                    return true;
                }, v -> 1)
                .get();
        assertNull(r);
        assertFalse(predicateCalled.get(), "null 值时谓词不应被调用");
    }

    @Test
    void whenNullInterceptsNull() {
        String r = Branch.<String>of(null)
                .whenNull(v -> "fallback")
                .get();
        assertEquals("fallback", r);
    }

    @Test
    void builtInPredicates() {
        assertEquals("empty", Branch.of(List.of()).whenNone(v -> "empty").get());
        assertEquals("zero", Branch.of(0).whenZero(v -> "zero").get());
        assertEquals("one", Branch.of(1).whenOne(v -> "one").get());
        assertEquals("single", Branch.of(List.of("x")).whenOne(v -> "single").get());
        assertEquals("yes", Branch.of(Boolean.TRUE).whenTrue(v -> "yes").get());
        // 类型不符视为不命中，透传原值
        assertEquals("raw", Branch.of("raw").whenTrue(v -> "yes").get());
    }

    @Test
    void recoverContinuesChain() {
        String r = Branch.of("input")
                .when(v -> true, v -> {
                    throw new IllegalStateException("boom");
                })
                .recover(e -> "recovered:" + e.getMessage())
                .when(v -> true, v -> v + "-tail")
                .get();
        assertEquals("recovered:boom-tail", r);
    }

    @Test
    void onErrorStopsWithLastGoodValue() {
        String r = Branch.of("init")
                .when(v -> true, v -> "ok")
                .when(v -> true, v -> {
                    throw new RuntimeException("bad");
                })
                .onError(e -> { /* 仅消费 */ })
                .when(v -> true, v -> "never-reached")
                .get();
        assertEquals("ok", r);
    }

    @Test
    void exceptionEscapesWithoutHandler() {
        assertDoesNotThrow(() -> Branch.of(1).when(v -> true, v -> 2).get());
        Branch<String> bomb = Branch.of("x")
                .when(v -> true, v -> {
                    throw new IllegalArgumentException("no-handler");
                });
        assertThrows(IllegalArgumentException.class, bomb::get);
    }

    @Test
    void requireConditionBeforeGet() {
        Branch<String> noCondition = Branch.of("v").recover(e -> "r");
        IllegalStateException ex = assertThrows(IllegalStateException.class, noCondition::get);
        assertTrue(ex.getMessage().contains("缺少条件分支"));
    }

    @Test
    void protectOpenCircuitRoutesToFallbackWithoutExecuting() {
        String uniqueName = "branch-test-" + System.nanoTime();
        AtomicInteger underlying = new AtomicInteger();

        // 第一次：熔断器闭合但底层失败 → 记一次失败，走 recover
        String first = Branch.of("in")
                .protect(uniqueName, 1, 1, 60_000L)
                .when(v -> true, v -> {
                    underlying.incrementAndGet();
                    throw new RuntimeException("down");
                })
                .recover(e -> "fallback-1")
                .get();
        assertEquals("fallback-1", first);
        assertEquals(1, underlying.get());

        // 第二次：已熔开 → 底层不再执行，直接走兜底
        String second = Branch.of("in")
                .protect(uniqueName)
                .when(v -> true, v -> {
                    underlying.incrementAndGet();
                    return "real";
                })
                .recover(e -> "fallback-2")
                .get();
        assertEquals("fallback-2", second);
        assertEquals(1, underlying.get(), "熔开状态下底层不应被执行");
    }

    @Test
    void afterBranchResetsScopeAndPassesResult() {
        AtomicReference<Throwable> seen = new AtomicReference<>();
        List<String> r = Branch.of("seed")
                .when(v -> true, v -> List.of(v))
                .afterBranch()
                // 新链中前段的 recover 已失效：此处异常将向上传播而非被吞
                .when(v -> !((List<?>) v).isEmpty(), v -> {
                    throw new IllegalStateException("should-propagate");
                })
                .recover(e -> {
                    seen.set(e);
                    return List.of("reset");
                })
                .get();
        assertEquals(List.of("reset"), r);
        assertTrue(seen.get() instanceof IllegalStateException);
    }

    @Test
    void sequentialWhensAreIndependentGuards() {
        String r = Branch.of("data")
                .when(v -> true, v -> v + "-1")
                .when(v -> false, v -> v + "-2")
                .when(v -> true, v -> v + "-3")
                .get();
        assertEquals("data-1-3", r);
    }
}
