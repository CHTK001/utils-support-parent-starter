package com.chua.common.support.task.pipeline;

import com.chua.common.support.task.pipeline.builder.PipelineBuilder;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流水线同步/异步双执行用例。
 *
 * <p>流水线只定义一套（builder 组装节点图），同步与异步只在执行层：
 * {@code execute} 当前线程直接执行，{@code executeAsync} 交给
 * {@code boundedElastic} 调度线程执行并以三个回调
 * {@code subscribe(onNext, onError, onComplete)} 交付结果。</p>
 */
class PipelineAsyncTest {

    /**
     * 组装一条两节点流水线。
     *
     * @return 流水线
     */
    private static Pipeline pipeline() {
        return PipelineBuilder.newBuilder("demo")
                .task("step1", ctx -> null).taskEnd()
                .task("step2", ctx -> null).taskEnd()
                .build();
    }

    /**
     * 异步执行：上下文走 onNext、完成通知走 onComplete，且在 Reactor 调度线程上回调。
     */
    @Test
    void asyncExecutesViaThreeCallbacks() throws InterruptedException {
        AtomicReference<PipelineContext<String>> holder = new AtomicReference<>();
        List<String> callbacks = new CopyOnWriteArrayList<>();
        AtomicReference<String> threadName = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        pipeline().<String>executeAsync("input").subscribe(
                ctx -> {
                    threadName.set(Thread.currentThread().getName());
                    holder.set(ctx);
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
        assertEquals(List.of("step1", "step2"), holder.get().getHistory());
    }

    /**
     * 节点异常走 onError，onNext 与 onComplete 均不触发。
     */
    @Test
    void asyncErrorGoesToOnError() throws InterruptedException {
        AtomicReference<Throwable> error = new AtomicReference<>();
        List<String> callbacks = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        PipelineBuilder.newBuilder("demo-broken")
                .task("bad", ctx -> {
                    throw new IllegalStateException("boom");
                }).taskEnd()
                .build()
                .<String>executeAsync("input")
                .subscribe(
                        ctx -> callbacks.add("onNext"),
                        thrown -> {
                            error.set(thrown);
                            callbacks.add("onError");
                            done.countDown();
                        },
                        () -> callbacks.add("onComplete"));

        assertTrue(done.await(5, TimeUnit.SECONDS), "异步执行超时");
        assertEquals(List.of("onError"), callbacks);
        assertNotNull(error.get());
    }

    /**
     * executeAsync 是冷流：每次订阅创建新上下文重新执行，且与同步 execute 语义一致。
     */
    @Test
    void asyncIsColdAndReusable() {
        Pipeline pipeline = pipeline();
        Mono<PipelineContext<String>> mono = pipeline.executeAsync("input");

        PipelineContext<String> first = mono.block();
        PipelineContext<String> second = mono.block();

        assertEquals(List.of("step1", "step2"), first.getHistory());
        assertEquals(List.of("step1", "step2"), second.getHistory());
        assertEquals(List.of("step1", "step2"), pipeline.<String>execute("input").getHistory());
    }

    /**
     * 复用已有上下文形态也有异步入口。
     */
    @Test
    void asyncWithExistingContext() {
        Pipeline pipeline = pipeline();
        PipelineContext<String> ctx = new PipelineContext<>(pipeline.getId(), "input");

        PipelineContext<String> result = pipeline.executeAsync(ctx).block();

        assertEquals(List.of("step1", "step2"), result.getHistory());
    }
}
