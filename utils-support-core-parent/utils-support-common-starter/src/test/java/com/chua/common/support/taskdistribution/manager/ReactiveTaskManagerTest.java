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

        reactor.core.publisher.Mono<Boolean> cancelMono = reactive.cancel(taskId);
        StepVerifier.create(cancelMono)
                .assertNext(b -> assertTrue(b))
                .verifyComplete();
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
        StepVerifier.create(flux)
                .thenAwait()
                .verifyTimeout(java.time.Duration.ofSeconds(2));

        // 触发完成
        manager.handleResult(TaskResult.success(taskId, "watch-result", "w1"));

        StepVerifier.create(flux)
                .assertNext(r -> {
                    assertEquals(taskId, r.getTaskId());
                    assertTrue(r.isSuccess());
                    assertEquals("watch-result", r.getData());
                })
                .verifyComplete();
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
