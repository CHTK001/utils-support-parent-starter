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
                })
                .end();
        assertEquals(0, invoked.get(), "终端调用前不应执行任何动作");
        assertEquals("a!", branch.get());
        assertEquals(1, invoked.get());
    }

    @Test
    void whenHitAndMiss() {
        assertEquals("big", Branch.of(10)
                .when(v -> v > 5, v -> "big")
                .otherwise(v -> "small")
                .get());
        assertEquals("small", Branch.of(1)
                .when(v -> v > 5, v -> "big")
                .otherwise(v -> "small")
                .get());
    }

    @Test
    void firstMatchWinsInGroup() {
        String r = Branch.of(5)
                .when(v -> v > 10, v -> ">10")
                .elseIf(v -> v > 3, v -> ">3")
                .elseIf(v -> v > 1, v -> ">1")
                .otherwise(v -> "other")
                .get();
        assertEquals(">3", r);
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
        assertEquals("empty", Branch.of(List.of())
                .whenNone(v -> "empty")
                .get());
        assertEquals("zero", Branch.of(0)
                .whenZero(v -> "zero")
                .get());
        assertEquals("one", Branch.of(1)
                .whenOne(v -> "one")
                .get());
        assertEquals("single", Branch.of(List.of("x"))
                .whenOne(v -> "single")
                .get());
        assertEquals("yes", Branch.of(Boolean.TRUE)
                .whenTrue(v -> "yes")
                .get());
        // 类型不符视为不命中，透传原值
        assertEquals("raw", Branch.of("raw")
                .whenTrue(v -> "yes")
                .get());
    }

    @Test
    void recoverDeclaredBeforeRiskyStepContinuesChain() {
        String r = Branch.of("input")
                .recover(e -> "recovered:" + e.getMessage())
                .when(v -> true, v -> {
                    throw new IllegalStateException("boom");
                })
                .end()
                .when(v -> true, v -> v + "-tail")
                .get();
        assertEquals("recovered:boom-tail", r);
    }

    @Test
    void onErrorStopsWithLastGoodValue() {
        String r = Branch.of("init")
                .onError(e -> { /* 仅消费 */ })
                .when(v -> true, v -> "ok")
                .end()
                .when(v -> true, v -> {
                    throw new RuntimeException("bad");
                })
                .end()
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
                })
                .end();
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
                .recover(e -> "fallback-1")
                .protect(uniqueName, 1, 1, 60_000L)
                .when(v -> true, v -> {
                    underlying.incrementAndGet();
                    throw new RuntimeException("down");
                })
                .get();
        assertEquals("fallback-1", first);
        assertEquals(1, underlying.get());

        // 第二次：已熔开 → 底层不再执行，直接走兜底
        String second = Branch.of("in")
                .recover(e -> "fallback-2")
                .protect(uniqueName)
                .when(v -> true, v -> {
                    underlying.incrementAndGet();
                    return "real";
                })
                .get();
        assertEquals("fallback-2", second);
        assertEquals(1, underlying.get(), "熔开状态下底层不应被执行");
    }

    @Test
    void afterBranchResetsScopeAndPassesResult() {
        // 前段：recover 覆盖风险步骤并生效
        List<String> first = Branch.of("seed")
                .<List<String>>recover(e -> List.of("r1"))
                .when(v -> true, v -> {
                    throw new IllegalStateException("s1");
                })
                .get();
        assertEquals(List.of("r1"), first);

        // 后段：afterBranch 后作用域清零，无 recover 时异常穿透
        Branch<String> boom = Branch.of("x")
                .when(v -> true, v -> {
                    throw new IllegalStateException("s2");
                })
                .end();
        IllegalStateException ex = assertThrows(IllegalStateException.class, boom::get);
        assertEquals("s2", ex.getMessage());
    }

    @Test
    void sequentialWhensAreIndependentGuards() {
        String r = Branch.of("data")
                .when(v -> true, v -> v + "-1")
                .end()
                .when(v -> false, v -> v + "-2")
                .end()
                .when(v -> true, v -> v + "-3")
                .get();
        assertEquals("data-1-3", r);
    }

    @Test
    void bytesEntryPointWithTypeSafeActions() {
        // byte[] 专属入口：lambda 直接收到强类型 byte[]，无需转换
        int length = Branch.ofBytes(new byte[]{1, 2, 3})
                .when(b -> b.length > 0, b -> b.length)
                .get();
        assertEquals(3, length);

        // 非 byte[] 值对 whenBytes 不命中，透传原值
        String untouched = Branch.of("not-bytes")
                .whenBytes(b -> true, b -> "hit")
                .get();
        assertEquals("not-bytes", untouched);
    }
}
