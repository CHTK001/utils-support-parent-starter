package com.chua.common.support.ai.decision;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 决策求值同步/异步双执行用例。
 *
 * <p>决策只定义一种批量形态（{@code decide} 逐问题联合求值），
 * 同步与异步只在执行层：{@code decide} 当前线程直接求值，
 * {@code decideAsync} 交给 {@code boundedElastic} 调度线程求值并以三个回调
 * {@code subscribe(onNext, onError, onComplete)} 交付结果。</p>
 */
class DecisionAsyncTest {

    /**
     * 异步求值：批次走 onNext、完成通知走 onComplete，且在 Reactor 调度线程上回调。
     */
    @Test
    void asyncDecidesViaThreeCallbacks() throws InterruptedException {
        DecisionProvider provider = DefaultDecisionProvider.of(Map.of(
                "risk", query -> new Decision("risk", DecisionType.NOUL, "true",
                        Map.of("true", 0.9D, "false", 0.1D))));

        DecisionRequest request = DecisionRequest.of(null,
                DecisionQuery.noul("risk", "风险高吗？"));

        AtomicReference<DecisionBatch> holder = new AtomicReference<>();
        List<String> callbacks = new CopyOnWriteArrayList<>();
        AtomicReference<String> threadName = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        provider.decideAsync(request).subscribe(
                batch -> {
                    threadName.set(Thread.currentThread().getName());
                    holder.set(batch);
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
        assertEquals("true", holder.get().answers(request).get("risk").label());
    }

    /**
     * 求值失败走 onError，onNext 与 onComplete 均不触发。
     */
    @Test
    void asyncErrorGoesToOnError() throws InterruptedException {
        DecisionProvider provider = DefaultDecisionProvider.of(Map.of(
                "risk", query -> {
                    throw new IllegalStateException("服务故障");
                }));

        AtomicReference<Throwable> error = new AtomicReference<>();
        List<String> callbacks = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        provider.decideAsync(DecisionRequest.of(null,
                        DecisionQuery.noul("risk", "风险高吗？")))
                .subscribe(
                        batch -> callbacks.add("onNext"),
                        thrown -> {
                            error.set(thrown);
                            callbacks.add("onError");
                            done.countDown();
                        },
                        () -> callbacks.add("onComplete"));

        assertTrue(done.await(5, TimeUnit.SECONDS), "异步执行超时");
        assertEquals(List.of("onError"), callbacks);
        assertInstanceOf(DecisionException.class, error.get());
    }

    /**
     * decideAsync 是冷流：每次订阅重新求值，且与同步 decide 语义一致。
     */
    @Test
    void asyncIsColdAndReusable() {
        AtomicInteger calls = new AtomicInteger();
        DecisionProvider provider = DefaultDecisionProvider.of(Map.of(
                "risk", query -> {
                    calls.incrementAndGet();
                    return new Decision("risk", DecisionType.NOUL, "false",
                            Map.of("true", 0.2D, "false", 0.8D));
                }));

        DecisionRequest request = DecisionRequest.of(null,
                DecisionQuery.noul("risk", "风险高吗？"));

        Mono<DecisionBatch> mono = provider.decideAsync(request);

        assertEquals("false", mono.block().answers(request).get("risk").label());
        assertEquals("false", mono.block().answers(request).get("risk").label());
        assertEquals("false", provider.decide(request).answers(request).get("risk").label());
        assertEquals(3, calls.get());
    }
}
