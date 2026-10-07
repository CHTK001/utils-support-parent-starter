package com.chua.common.support.task.async;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异步任务链同步/异步双执行用例。
 *
 * <p>{@code task} 链的定义只有一套（同步形态的任务），
 * 同步与异步只在执行层：{@code execute} 当前线程直接执行返回，
 * {@code executeAsync} 交给异步提供者执行并以三个回调
 * {@code subscribe(onNext, onError, onComplete)} 交付结果。</p>
 */
class AsyncFlowChainTest {

    /**
     * 链按声明顺序求值：有返回值步骤流转新值，无返回值步骤保留当前值，
     * 结果列表与步骤一一对应。
     */
    @Test
    void chainRunsInOrderAndCollectsStepResults() {
        List<String> sideEffects = new ArrayList<>();

        AsyncFlowChain<String> chain = AsyncFlow.of()
                .task(() -> "cfg")
                .task(cfg -> cfg + "-loaded")
                .task(v -> {
                    sideEffects.add(v);
                })
                .task(() -> {
                    sideEffects.add("done");
                });

        assertEquals(List.of("cfg", "cfg-loaded", "cfg-loaded", "cfg-loaded"), chain.execute());
        assertEquals(List.of("cfg-loaded", "done"), sideEffects);
    }

    /**
     * 异步执行：结果走 onNext、完成通知走 onComplete，且在 Reactor 调度线程上回调。
     */
    @Test
    void asyncDeliversViaThreeCallbacks() throws InterruptedException {
        List<Object> delivered = new CopyOnWriteArrayList<>();
        List<String> callbacks = new CopyOnWriteArrayList<>();
        AtomicReference<String> threadName = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        AsyncFlow.of()
                .task(() -> "a")
                .task(v -> v + "b")
                .executeAsync()
                .subscribe(
                        list -> {
                            threadName.set(Thread.currentThread().getName());
                            delivered.add(list);
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
        assertEquals(List.of("a", "ab"), delivered.get(0));
    }

    /**
     * 步骤抛异常走 onError，onNext 与 onComplete 均不触发，后续步骤不执行。
     */
    @Test
    void asyncErrorGoesToOnErrorAndStopsLaterSteps() throws InterruptedException {
        AtomicBoolean laterRan = new AtomicBoolean();
        AtomicReference<Throwable> error = new AtomicReference<>();
        List<String> callbacks = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        AsyncFlow.of()
                .task(() -> "x")
                .task(AsyncFlowChainTest::explode)
                .task(() -> laterRan.set(true))
                .executeAsync()
                .subscribe(
                        list -> callbacks.add("onNext"),
                        thrown -> {
                            error.set(unwrap(thrown));
                            callbacks.add("onError");
                            done.countDown();
                        },
                        () -> callbacks.add("onComplete"));

        assertTrue(done.await(5, TimeUnit.SECONDS), "异步执行超时");
        assertEquals(List.of("onError"), callbacks);
        assertInstanceOf(IllegalStateException.class, error.get());
        assertFalse(laterRan.get(), "报错后后续步骤不应执行");
    }

    /**
     * 同步执行同样短路：异常直接抛出，后续步骤不执行。
     */
    @Test
    void syncExecutePropagatesErrorAndStopsLaterSteps() {
        AtomicBoolean laterRan = new AtomicBoolean();

        AsyncFlowChain<String> chain = AsyncFlow.of()
                .task(() -> "x")
                .task(AsyncFlowChainTest::explode)
                .task(() -> laterRan.set(true));

        assertThrows(IllegalStateException.class, chain::execute);
        assertFalse(laterRan.get(), "报错后后续步骤不应执行");
    }

    /**
     * 执行一次即冻结链：execute / executeAsync 调用后再追加步骤抛异常；执行本身可重复。
     */
    @Test
    void executeFreezesChainButAllowsRerun() {
        AsyncFlowChain<String> chain = AsyncFlow.of().task(() -> "v");

        assertEquals(List.of("v"), chain.execute());
        assertEquals(List.of("v"), chain.execute());

        assertThrows(IllegalStateException.class, () -> chain.task(() -> "more"));
        assertThrows(IllegalStateException.class, () -> chain.task(() -> { }));

        AsyncFlowChain<String> coldChain = AsyncFlow.of().task(() -> "v");
        coldChain.executeAsync();
        assertThrows(IllegalStateException.class, () -> coldChain.task(v -> { }));
    }

    /**
     * executeAsync 是冷流：每次订阅经提供者重新执行一遍，结果随执行推进。
     */
    @Test
    void asyncIsColdAndReusable() {
        AtomicInteger runs = new AtomicInteger();
        Mono<List<Object>> mono = AsyncFlow.of()
                .task(runs::incrementAndGet)
                .executeAsync();

        assertEquals(List.of(1), mono.block());
        assertEquals(List.of(2), mono.block());
        assertEquals(2, runs.get());
    }

    /**
     * 恒抛异常的步骤（供错误用例复用）。
     *
     * @param ignored 入参
     * @return 永不返回
     */
    private static String explode(String ignored) {
        throw new IllegalStateException("boom");
    }

    /**
     * 解包 Reactor 可能包装的 {@link CompletionException}。
     *
     * @param thrown 回调收到的异常
     * @return 业务异常本体
     */
    private static Throwable unwrap(Throwable thrown) {
        Throwable current = thrown;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
