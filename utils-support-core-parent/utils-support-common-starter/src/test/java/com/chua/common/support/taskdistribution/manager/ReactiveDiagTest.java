package com.chua.common.support.taskdistribution.manager;

import com.chua.common.support.taskdistribution.task.Task;
import com.chua.common.support.taskdistribution.task.TaskResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 最小化诊断测试，逐步验证响应式门面各环节。
 *
 * @author CH
 * @since 4.0.0.43
 */
class ReactiveDiagTest {

    private TaskManager manager;
    private ReactiveTaskManager reactive;

    @BeforeEach
    void setUp() {
        manager = new TaskManager(false);
        reactive = new ReactiveTaskManager(manager);
    }

    @Test
    void step1_directMono() {
        System.out.println("[DIAG] step1 开始");
        String value = reactor.core.publisher.Mono.just("hello").block();
        assertEquals("hello", value);
        System.out.println("[DIAG] step1 通过");
    }

    @Test
    void step2_fromCallable() {
        System.out.println("[DIAG] step2 开始");
        String value = reactor.core.publisher.Mono.fromCallable(() -> "world")
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .block();
        assertEquals("world", value);
        System.out.println("[DIAG] step2 通过");
    }

    @Test
    void step3_fromFuture() {
        System.out.println("[DIAG] step3 开始");
        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
        new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            future.complete("future-value");
        }).start();
        String value = reactor.core.publisher.Mono.fromFuture(future)
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .block();
        assertEquals("future-value", value);
        System.out.println("[DIAG] step3 通过");
    }

    @Test
    void step4_submitSync() {
        System.out.println("[DIAG] step4 开始（同步 handleResult）");
        String taskId = UUID.randomUUID().toString();
        Task<String> task = Task.<String>builder()
                .taskId(taskId).taskType("test").payload("x").build();

        // 先同步提交，再手动触发回调（模拟已完成场景的时序）
        manager.addTask(task, null);

        java.util.concurrent.CompletableFuture<TaskResult<?>> probe = new java.util.concurrent.CompletableFuture<>();
        manager.addStateListener(new TaskStateListener() {
            @Override
            public void onCompleted(TaskResult<?> result) {
                probe.complete(result);
            }
        });
        manager.handleResult(TaskResult.success(taskId, "sync-result", "w1"));

        TaskResult<?> result = reactor.core.publisher.Mono.fromFuture(probe).block();
        assertNotNull(result);
        assertTrue(result.isSuccess());
        System.out.println("[DIAG] step4 通过");
    }

    @Test
    void step5_reactiveSubmitAsyncWorker() {
        System.out.println("[DIAG] step5 开始（reactive.submit + 异步工作线程）");
        String taskId = UUID.randomUUID().toString();
        Task<String> task = Task.<String>builder()
                .taskId(taskId).taskType("test").payload("x").build();

        // 先启动工作线程，再订阅，避免时序竞争
        Thread worker = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            System.out.println("[DIAG] 工作线程触发 handleResult");
            manager.handleResult(TaskResult.success(taskId, "async-result", "w1"));
        });
        worker.start();

        TaskResult<?> result = reactive.submit(task).block(java.time.Duration.ofSeconds(3));
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("async-result", result.getData());
        try { worker.join(2000); } catch (InterruptedException ignored) {}
        System.out.println("[DIAG] step5 通过");
    }

    @Test
    void step6_reactiveSubmitSyncBeforeSubscribe() {
        System.out.println("[DIAG] step6 开始（submit 先注册，handleResult 后触发）");
        String taskId = UUID.randomUUID().toString();
        Task<String> task = Task.<String>builder()
                .taskId(taskId).taskType("test").payload("x").build();

        reactor.core.publisher.Mono<TaskResult<String>> mono = reactive.submit(task);
        System.out.println("[DIAG] mono 已创建，尚未订阅");

        new Thread(() -> {
            try { Thread.sleep(30); } catch (InterruptedException ignored) {}
            System.out.println("[DIAG] 主流程触发 handleResult");
            manager.handleResult(TaskResult.success(taskId, "sync2", "w1"));
        }).start();

        TaskResult<?> result = mono.block(java.time.Duration.ofSeconds(3));
        assertNotNull(result);
        System.out.println("[DIAG] step6 通过");
    }
}
