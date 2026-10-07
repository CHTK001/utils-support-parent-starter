package com.chua.common.support.ai.branch;

import com.chua.common.support.ai.rule.Action;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 分支同步/异步双执行用例。
 *
 * <p>{@code when / then} 链的定义只有一套（同步谓词与消费者），
 * 同步与异步只在执行层：{@code execute} 当前线程直接求值返回，
 * {@code executeAsync} 交给 Reactor 调度并以三个回调
 * {@code subscribe(onNext, onError, onComplete)} 交付结果。</p>
 */
class BranchAsyncTest {

    /**
     * 异步执行：结果走 onNext、完成通知走 onComplete，且在 Reactor 线程上回调。
     */
    @Test
    void asyncDeliversViaThreeCallbacks() throws InterruptedException {
        List<BranchResult> results = new CopyOnWriteArrayList<>();
        List<String> callbacks = new CopyOnWriteArrayList<>();
        AtomicReference<String> threadName = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Branches.when(ctx -> ctx.intOf("risk", 0) >= 90)
                .message("高风险拦截")
                .then(ctx -> ctx.action(Action.REJECT))
                .otherwise(ctx -> ctx.action(Action.PASS))
                .executeAsync(Map.of("risk", 95))
                .subscribe(
                        result -> {
                            threadName.set(Thread.currentThread().getName());
                            results.add(result);
                            callbacks.add("onNext");
                        },
                        error -> callbacks.add("onError"),
                        () -> {
                            callbacks.add("onComplete");
                            done.countDown();
                        });

        assertTrue(done.await(5, TimeUnit.SECONDS), "异步执行超时");
        assertEquals(List.of("onNext", "onComplete"), callbacks);
        assertTrue(threadName.get().contains("boundedElastic"),
                "应在 Reactor 调度线程回调，实际线程: " + threadName.get());

        BranchResult result = results.get(0);
        assertEquals("branch-1", result.hitId().orElseThrow());
        assertEquals("高风险拦截", result.reason().orElseThrow());
        assertEquals(Action.REJECT, result.action());
    }

    /**
     * 未命中也走 onNext（空命中结果），onComplete 照常通知。
     */
    @Test
    void asyncMissGoesOnNext() throws InterruptedException {
        AtomicReference<BranchResult> holder = new AtomicReference<>();
        List<String> callbacks = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        Branches.when(ctx -> false)
                .then(ctx -> fail("不应命中"))
                .executeAsync(Map.of())
                .subscribe(
                        result -> {
                            holder.set(result);
                            callbacks.add("onNext");
                        },
                        error -> callbacks.add("onError"),
                        () -> {
                            callbacks.add("onComplete");
                            done.countDown();
                        });

        assertTrue(done.await(5, TimeUnit.SECONDS), "异步执行超时");
        assertEquals(List.of("onNext", "onComplete"), callbacks);
        assertNotNull(holder.get());
        assertFalse(holder.get().anyHit());
    }

    /**
     * 求值/动作抛异常走 onError，onNext 与 onComplete 均不触发，后续动作不执行。
     */
    @Test
    void asyncErrorGoesToOnError() throws InterruptedException {
        AtomicBoolean laterActionRan = new AtomicBoolean();
        AtomicReference<Throwable> error = new AtomicReference<>();
        List<String> callbacks = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        BranchChain chain = Branches.when(ctx -> true)
                .then(ctx -> {
                    throw new IllegalStateException("boom");
                })
                .then(ctx -> laterActionRan.set(true))
                .chain();

        chain.executeAsync(Map.of()).subscribe(
                result -> callbacks.add("onNext"),
                thrown -> {
                    error.set(thrown);
                    callbacks.add("onError");
                    done.countDown();
                },
                () -> callbacks.add("onComplete"));

        assertTrue(done.await(5, TimeUnit.SECONDS), "异步执行超时");
        assertEquals(List.of("onError"), callbacks);
        assertInstanceOf(IllegalStateException.class, error.get());
        assertFalse(laterActionRan.get(), "报错后后续动作不应执行");
    }

    /**
     * 独占语义在异步执行下保持：首个命中即停，后续分支条件不再求值。
     */
    @Test
    void asyncKeepsExclusiveSemantics() {
        AtomicBoolean secondChecked = new AtomicBoolean();
        AtomicBoolean fallbackChecked = new AtomicBoolean();

        BranchResult result = Branches.when(ctx -> ctx.intOf("risk", 0) >= 90)
                .then(ctx -> ctx.set("level", "high"))
                .when(ctx -> {
                    secondChecked.set(true);
                    return ctx.intOf("risk", 0) >= 60;
                })
                .then(ctx -> ctx.set("level", "medium"))
                .otherwise(ctx -> fallbackChecked.set(true))
                .executeAsync(Map.of("risk", 95))
                .block();

        assertNotNull(result);
        assertEquals("high", result.value("level").orElseThrow());
        assertFalse(secondChecked.get(), "首条命中后后续分支条件不应求值");
        assertFalse(fallbackChecked.get(), "首条命中后兜底分支动作不应执行");
    }

    /**
     * executeAsync 是冷流：每次订阅全新上下文重新求值，且与同步 execute 语义一致。
     */
    @Test
    void asyncIsColdAndReusable() {
        BranchChain chain = Branches.when(ctx -> ctx.intOf("risk", 0) >= 90)
                .then(ctx -> ctx.action(Action.REJECT))
                .otherwise(ctx -> ctx.action(Action.PASS))
                .chain();

        assertEquals(Action.REJECT, chain.executeAsync(Map.of("risk", 99)).block().action());
        assertEquals(Action.PASS, chain.executeAsync(Map.of("risk", 1)).block().action());
        assertEquals(Action.REJECT, chain.execute(Map.of("risk", 99)).action());
    }
}
