package com.chua.common.support.task.retry;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重试流同步/异步双执行用例。
 *
 * <p>链式配置只有一套，执行分两种：{@code execute} 当前线程阻塞执行，
 * {@code executeAsync} 交给 {@code boundedElastic} 调度线程执行并以三个回调
 * {@code subscribe(onNext, onError, onComplete)} 交付结果。</p>
 */
class RetryFlowTest {

    /**
     * 异步重试成功：前两次失败、第三次成功，结果走 onNext。
     */
    @Test
    void asyncRetriesThenSucceeds() throws InterruptedException {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Object> holder = new AtomicReference<>();
        List<String> callbacks = new CopyOnWriteArrayList<>();
        AtomicReference<String> threadName = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        RetryFlow.of("test-retry")
                .maxRetries(3)
                .executeAsync(() -> {
                    if (attempts.incrementAndGet() < 3) {
                        throw new IllegalStateException("boom-" + attempts.get());
                    }
                    return "ok";
                })
                .subscribe(
                        value -> {
                            threadName.set(Thread.currentThread().getName());
                            holder.set(value);
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
        assertEquals("ok", holder.get());
        assertEquals(3, attempts.get());
    }

    /**
     * 重试耗尽走 onError，异常本体与同步 execute 一致（最后一次异常）。
     */
    @Test
    void asyncErrorAfterRetriesExhausted() throws InterruptedException {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();
        List<String> callbacks = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        RetryFlow.of("test-exhausted")
                .maxRetries(2)
                .executeAsync(() -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("always");
                })
                .subscribe(
                        value -> callbacks.add("onNext"),
                        thrown -> {
                            error.set(unwrap(thrown));
                            callbacks.add("onError");
                            done.countDown();
                        },
                        () -> callbacks.add("onComplete"));

        assertTrue(done.await(5, TimeUnit.SECONDS), "异步执行超时");
        assertEquals(List.of("onError"), callbacks);
        assertInstanceOf(IllegalStateException.class, error.get());
        assertEquals("always", error.get().getMessage());
        assertEquals(3, attempts.get(), "初次 + 2 次重试");
    }

    /**
     * 降级走 onNext：重试耗尽后 fallback 返回。
     */
    @Test
    void asyncFallbackGoesOnNext() {
        String result = RetryFlow.of("test-fallback")
                .maxRetries(1)
                .fallback(() -> "degraded")
                .<String>executeAsync(() -> {
                    throw new IllegalStateException("fail");
                })
                .block();

        assertEquals("degraded", result);
    }

    /**
     * 不可重试异常立即失败，不再尝试。
     */
    @Test
    void asyncNonRetryableErrorFailsFast() throws InterruptedException {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        RetryFlow.of("test-fast-fail")
                .maxRetries(5)
                .retryOnException(e -> e instanceof IllegalArgumentException)
                .executeAsync(() -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("fatal");
                })
                .subscribe(
                        value -> { },
                        thrown -> {
                            error.set(unwrap(thrown));
                            done.countDown();
                        },
                        () -> { });

        assertTrue(done.await(5, TimeUnit.SECONDS), "异步执行超时");
        assertInstanceOf(IllegalStateException.class, error.get());
        assertEquals(1, attempts.get(), "不可重试异常只执行一次");
    }

    /**
     * 无返回值形态 + 异步装配冻结配置。
     */
    @Test
    void runnableAsyncAndFreeze() {
        RetryFlow flow = RetryFlow.of("test-runnable");
        Mono<Void> mono = flow.executeAsync(() -> { });
        mono.block();

        assertThrows(IllegalStateException.class, () -> flow.maxRetries(9));
        assertThrows(IllegalStateException.class, () -> flow.fallback(() -> "x"));
    }

    /**
     * 解包 Reactor 可能包装的 {@link java.util.concurrent.CompletionException}。
     *
     * @param thrown 回调收到的异常
     * @return 业务异常本体
     */
    private static Throwable unwrap(Throwable thrown) {
        Throwable current = thrown;
        while (current instanceof java.util.concurrent.CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
