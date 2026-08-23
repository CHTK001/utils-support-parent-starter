package com.chua.common.support.taskdistribution.manager;

import com.chua.common.support.taskdistribution.task.Task;
import com.chua.common.support.taskdistribution.task.TaskResult;
import com.chua.common.support.taskdistribution.task.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReactiveTaskManager 单元测试，验证响应式任务门面核心能力。
 *
 * @author CH
 * @since 4.0.0.43
 */
class ReactiveTaskManagerTest {

    /**
     * 底层同步任务管理器
     */
    private TaskManager manager;

    /**
     * 响应式门面
     */
    private ReactiveTaskManager reactive;

    @BeforeEach
    void setUp() {
        manager = new TaskManager(false);
        reactive = new ReactiveTaskManager(manager);
    }

    @AfterEach
    void tearDown() {
        if (reactive != null) {
            reactive.close();
        }
    }

    // ==================== submit ====================

    @Test
    void submitCompletesOnSuccess() {
        String taskId = UUID.randomUUID().toString();
        Task<String> task = Task.<String>builder()
                .taskId(taskId)
                .taskType("test")
                .payload("hello")
                .build();

        // 在另一线程模拟工作端回调
        Thread worker = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            manager.handleResult(TaskResult.success(taskId, "result-data", "worker-1"));
        });
        worker.start();

        StepVerifier.create(reactive.submit(task))
                .assertNext(r -> {
                    assertEquals(taskId, r.getTaskId());
                    assertTrue(r.isSuccess());
                    assertEquals("result-data", r.getData());
                })
                .verifyComplete();

        try { worker.join(2000); } catch (InterruptedException ignored) {}
    }

    @Test
    void submitCompletesOnFailure() {
        String taskId = UUID.randomUUID().toString();
        Task<String> task = Task.<String>builder()
                .taskId(taskId)
                .taskType("test")
                .payload("fail-me")
                .build();

        Thread worker = new Thread(() -> {
            try { Thread.sleep(30); } catch (InterruptedException ignored) {}
            manager.handleResult(TaskResult.failure(taskId, "something went wrong", "worker-1"));
        });
        worker.start();

        StepVerifier.create(reactive.submit(task))
                .assertNext(r -> assertFalse(r.isSuccess()))
                .verifyComplete();

        try { worker.join(2000); } catch (InterruptedException ignored) {}
    }

    @Test
    void submitReturnsExistingResultWhenAlreadyCompleted() {
        String taskId = UUID.randomUUID().toString();
        // 必须先注册任务，handleResult 对未知任务会忽略
        manager.addTask(
                Task.<String>builder().taskId(taskId).taskType("test").build(),
                null);
        manager.handleResult(TaskResult.success(taskId, "cached", "w1"));

        Task<String> task = Task.<String>builder()
                .taskId(taskId)
                .taskType("test")
                .payload("x")
                .build();

        StepVerifier.create(reactive.submit(task))
                .assertNext(r -> assertEquals("cached", r.getData()))
                .verifyComplete();
    }

    @Test
    void submitNullTaskIdErrors() {
        Task<String> task = Task.<String>builder()
                .taskType("test")
                .payload("no-id")
                .build();

        StepVerifier.create(reactive.submit(task))
                .expectErrorMatches(e -> e instanceof IllegalArgumentException)
                .verify();
    }

    // ==================== getStatus ====================

    @Test
    void getStatusReturnsCurrentStatus() {
        String taskId = UUID.randomUUID().toString();
        manager.addTask(
                Task.<String>builder().taskId(taskId).taskType("t").build(),
                null);
        manager.updateStatus(taskId, TaskStatus.RUNNING);

        StepVerifier.create(reactive.getStatus(taskId))
                .assertNext(s -> assertEquals(TaskStatus.RUNNING, s))
                .verifyComplete();
    }

    @Test
    void getStatusReturnsEmptyForUnknownTask() {
        StepVerifier.create(reactive.getStatus("nonexistent"))
                .verifyComplete();
    }

    // ==================== cancel ====================

    @Test
    void cancelReturnsTrueAndCompletesSubmit() {
        String taskId = UUID.randomUUID().toString();
        Task<String> task = Task.<String>builder()
                .taskId(taskId)
                .taskType("test")
                .payload("x")
                .build();
        // 先注册任务，否则 cancel 因任务不存在返回 false
        reactor.core.publisher.Mono<TaskResult<String>> submitMono = reactive.submit(task);

        StepVerifier.create(reactive.cancel(taskId))
                .assertNext(b -> assertTrue(b))
                .verifyComplete();

        // 取消后 submit Mono 应以异常完成
        StepVerifier.create(submitMono)
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    void cancelReturnsFalseForUnknownTask() {
        StepVerifier.create(reactive.cancel("unknown-task"))
                .assertNext(b -> assertFalse(b))
                .verifyComplete();
    }

    // ==================== watch ====================

    @Test
    void watchEmitsResultOnCompletion() {
        String taskId = UUID.randomUUID().toString();
        Task<String> task = Task.<String>builder()
                .taskId(taskId)
                .taskType("test")
                .payload("watch-me")
                .build();
        manager.addTask(task, null);

        reactor.core.publisher.Flux<TaskResult<String>> flux = reactive.watch(taskId);

        // 在后台线程触发完成，StepVerifier 等待发射后流自动完成
        Thread worker = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            manager.handleResult(TaskResult.success(taskId, "watch-result", "w1"));
        });
        worker.start();

        StepVerifier.create(flux)
                .assertNext(r -> {
                    assertEquals(taskId, r.getTaskId());
                    assertTrue(r.isSuccess());
                    assertEquals("watch-result", r.getData());
                })
                .verifyComplete();

        try { worker.join(2000); } catch (InterruptedException ignored) {}
    }

    // ==================== pause / resume ====================

    @Test
    void pauseAndResume() {
        String taskId = UUID.randomUUID().toString();
        manager.addTask(
                Task.<String>builder().taskId(taskId).taskType("test").build(),
                null);

        StepVerifier.create(reactive.pause(taskId))
                .assertNext(b -> assertTrue(b))
                .verifyComplete();

        assertEquals(TaskStatus.PAUSED, manager.getStatus(taskId));

        StepVerifier.create(reactive.resume(taskId))
                .assertNext(b -> assertTrue(b))
                .verifyComplete();

        assertEquals(TaskStatus.PENDING, manager.getStatus(taskId));
    }

    @Test
    void pauseUnknownTaskReturnsFalse() {
        StepVerifier.create(reactive.pause("no-such-task"))
                .assertNext(b -> assertFalse(b))
                .verifyComplete();
    }

    // ==================== 链式构建 API ====================

    @Test
    void fluentBuildProducesConfiguredTask() {
        Task<String> task = reactive.task("email-send", "a@b.c")
                .traceId("trace-xyz")
                .tag("scene", "test")
                .shard(4, "region-a")
                .timeout(java.time.Duration.ofSeconds(15))
                .maxRetries(5)
                .priority(com.chua.common.support.taskdistribution.task.TaskPriority.HIGH)
                .build();

        assertEquals("email-send", task.getTaskType());
        assertEquals("a@b.c", task.getPayload());
        assertEquals("trace-xyz", task.getTraceId());
        assertEquals("test", task.getTags().get("scene"));
        assertEquals(4, task.getShardCount());
        assertEquals("region-a", task.getShardKey());
        assertEquals(15000L, task.getTimeoutMs());
        assertEquals(5, task.getMaxRetries());
        assertNotNull(task.getTaskId());
    }

    @Test
    void fluentSubmitCompletesWithWorkerResult() {
        // 复用同一 Fluent 引用：build 与 submit 共享同一个 taskId
        ReactiveTaskManager.TaskFluent<String> fluent = reactive.task("job-x", "payload")
                .tag("k", "v")
                .timeout(java.time.Duration.ofSeconds(10));
        String taskId = fluent.build().getTaskId();

        Thread worker = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            manager.handleResult(TaskResult.success(taskId, "fluent-ok", "w1"));
        });
        worker.start();

        StepVerifier.create(fluent.submit())
                .assertNext(r -> {
                    assertTrue(r.isSuccess());
                    assertEquals(taskId, r.getTaskId());
                    assertEquals("fluent-ok", r.getData());
                })
                .verifyComplete();

        try { worker.join(2000); } catch (InterruptedException ignored) {}
    }

    @Test
    void fluentSubmitOnRegisteredTaskCompletes() {
        Task<String> task = reactive.task("job-y", "data-1")
                .traceId("tr-1")
                .build();
        manager.addTask(task, null);

        Thread worker = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            manager.handleResult(TaskResult.success(task.getTaskId(), "y-result", "w1"));
        });
        worker.start();

        reactor.core.publisher.Mono<TaskResult<String>> mono = reactive.submit(task);
        StepVerifier.create(mono)
                .assertNext(r -> {
                    assertTrue(r.isSuccess());
                    assertEquals("y-result", r.getData());
                })
                .verifyComplete();

        try { worker.join(2000); } catch (InterruptedException ignored) {}
    }

    // ==================== 资源管理 ====================

    @Test
    void closeEmitsErrorToPendingSinks() {
        String taskId = UUID.randomUUID().toString();
        Task<String> task = Task.<String>builder()
                .taskId(taskId)
                .taskType("test")
                .payload("x")
                .build();
        reactor.core.publisher.Mono<TaskResult<String>> mono = reactive.submit(task);

        reactive.close();

        StepVerifier.create(mono)
                .expectError(RuntimeException.class)
                .verify();
    }
}
