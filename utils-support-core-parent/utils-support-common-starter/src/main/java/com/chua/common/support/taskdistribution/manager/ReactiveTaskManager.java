package com.chua.common.support.taskdistribution.manager;

import com.chua.common.support.taskdistribution.task.Task;
import com.chua.common.support.taskdistribution.task.TaskCallback;
import com.chua.common.support.taskdistribution.task.TaskResult;
import com.chua.common.support.taskdistribution.task.TaskStatus;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务管理器响应式门面，将 {@link TaskManager} 的回调式 API 包装为 Reactor 响应式 API。
 *
 * <p>所有方法通过 {@code boundedElastic} 调度器执行，避免阻塞 Reactor 事件循环线程。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * TaskManager manager = new TaskManager();
 * ReactiveTaskManager reactive = new ReactiveTaskManager(manager);
 *
 * // 提交任务并等待结果
 * Task<String> task = Task.<String>builder()
 *         .taskType("data-process")
 *         .payload("input-data")
 *         .build();
 * Mono<TaskResult<String>> result = reactive.submit(task);
 *
 * // 订阅任务状态变更流
 * Flux<TaskResult<String>> watch = reactive.watch(task.getTaskId());
 *
 * // 取消任务
 * Mono<Boolean> cancelled = reactive.cancel(task.getTaskId());
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class ReactiveTaskManager {

    /**
     * 底层同步任务管理器
     */
    private final TaskManager manager;

    /**
     * taskId -> submit 回调对应的 Sink（单次发射）
     */
    private final ConcurrentHashMap<String, Sinks.One<TaskResult<?>>> submitSinks = new ConcurrentHashMap<>();

    /**
     * taskId -> watch 监听器集合（用于清理）
     */
    private final ConcurrentHashMap<String, List<TaskStateListener>> watchListeners = new ConcurrentHashMap<>();

    /**
     * 构造响应式门面，包装指定任务管理器。
     *
     * @param manager 底层同步任务管理器，不可为 null
     */
    public ReactiveTaskManager(TaskManager manager) {
        if (manager == null) {
            throw new IllegalArgumentException("TaskManager must not be null");
        }
        this.manager = manager;
    }

    /**
     * 获取底层任务管理器实例。
     *
     * @return 底层 TaskManager
     */
    public TaskManager getManager() {
        return manager;
    }

    // ==================== 提交 / 查询 ====================

    /**
     * 提交任务并返回结果的 Mono。
     *
     * <p>任务提交后立即返回 Mono，当任务执行完成（SUCCESS / FAILED / CANCELLED / TIMEOUT）时发射结果；
     * 若任务已存在则直接返回当前结果。</p>
     *
     * @param task 任务，taskId 必须已设置
     * @param <T>  负载数据类型
     * @return 任务结果 Mono，任务不存在时发射错误
     */
    @SuppressWarnings("unchecked")
    public <T> Mono<TaskResult<T>> submit(Task<T> task) {
        String taskId = task.getTaskId();
        if (taskId == null) {
            return Mono.error(new IllegalArgumentException("Task taskId must not be null"));
        }
        // 任务已完成，直接返回结果
        TaskResult<?> existing = manager.getResult(taskId);
        if (existing != null) {
            return Mono.just((TaskResult<T>) existing);
        }
        // 通过 Sinks.One 等待单次完成信号
        Sinks.One<TaskResult<?>> sink = Sinks.one();
        submitSinks.put(taskId, sink);
        TaskCallback callback = new TaskCallback() {
            @Override
            public void onResult(TaskResult<?> result) {
                sink.tryEmitValue(result);
                submitSinks.remove(taskId);
            }
            @Override
            public void onError(String tid, String error) {
                sink.tryEmitError(new RuntimeException(error));
                submitSinks.remove(tid);
            }
            @Override
            public void onTimeout(String tid) {
                sink.tryEmitError(new RuntimeException("Task timeout: " + tid));
                submitSinks.remove(tid);
            }
        };
        manager.addTask(task, callback);
        return (Mono<TaskResult<T>>) (Mono<?>) Mono.from(sink.asMono())
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * 获取任务当前状态。
     *
     * @param taskId 任务 ID
     * @return 任务状态 Mono，不存在返回空 Mono
     */
    public Mono<TaskStatus> getStatus(String taskId) {
        return Mono.fromCallable(() -> manager.getStatus(taskId))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .filter(status -> status != null);
    }

    /**
     * 获取任务结果（若已完成）。
     *
     * @param taskId 任务 ID
     * @return 任务结果 Mono，不存在或未完成返回空 Mono
     */
    @SuppressWarnings("unchecked")
    public <T> Mono<TaskResult<T>> getResult(String taskId) {
        return Mono.fromCallable(() -> (TaskResult<T>) manager.getResult(taskId))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .filter(r -> r != null);
    }

    // ==================== 状态监听 ====================

    /**
     * 订阅指定任务的完整状态变更流。
     *
     * <p>每当事件监听器触发 onCompleted 时发射 {@link TaskResult}，
     * 流持续到任务进入终态（SUCCESS / FAILED / CANCELLED / TIMEOUT）后自动取消订阅。</p>
     *
     * @param taskId 任务 ID
     * @param <T>    结果类型
     * @return 任务结果流
     */
    @SuppressWarnings("unchecked")
    public <T> Flux<TaskResult<T>> watch(String taskId) {
        Sinks.Many<TaskResult<?>> sink = Sinks.many().multicast().onBackpressureBuffer();
        TaskStateListener listener = new TaskStateListener() {
            @Override
            public void onStateChanged(String id, TaskStatus oldState, TaskStatus newState) {
                // 状态变更不发射，仅关注完成事件
            }
            @Override
            public void onCompleted(TaskResult<?> result) {
                if (taskId.equals(result.getTaskId())) {
                    sink.tryEmitNext(result);
                    // 终态后取消监听（通过结果的成功标志判断）
                    boolean terminal = !result.isSuccess()
                            || manager.getStatus(taskId) == TaskStatus.FAILED
                            || manager.getStatus(taskId) == TaskStatus.CANCELLED
                            || manager.getStatus(taskId) == TaskStatus.TIMEOUT;
                    if (terminal) {
                        unwatch(taskId);
                        sink.tryEmitComplete();
                    }
                }
            }
        };
        manager.addStateListener(listener);
        watchListeners.put(taskId, List.of(listener));

        return sink.asFlux()
                .timeout(Duration.ofSeconds(300))
                .doOnCancel(() -> unwatch(taskId))
                .map(r -> (TaskResult<T>) r);
    }

    /**
     * 取消对指定任务的状态监听。
     *
     * @param taskId 任务 ID
     */
    public void unwatch(String taskId) {
        List<TaskStateListener> listeners = watchListeners.remove(taskId);
        if (listeners != null) {
            for (TaskStateListener listener : listeners) {
                manager.removeStateListener(listener);
            }
        }
    }

    // ==================== 生命周期控制 ====================

    /**
     * 取消任务。
     *
     * @param taskId 任务 ID
     * @return 取消成功返回 Mono.TRUE
     */
    public Mono<Boolean> cancel(String taskId) {
        return Mono.fromCallable(() -> manager.cancel(taskId))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .doOnSuccess(success -> {
                    if (success) {
                        Sinks.One<TaskResult<?>> sink = submitSinks.remove(taskId);
                        if (sink != null) {
                            sink.tryEmitError(new RuntimeException("Task cancelled: " + taskId));
                        }
                    }
                });
    }

    /**
     * 暂停任务（服务端不再派发，工作端可继续执行已有任务）。
     *
     * @param taskId 任务 ID
     * @return 暂停成功返回 Mono.TRUE
     */
    public Mono<Boolean> pause(String taskId) {
        return Mono.fromCallable(() -> manager.pause(taskId))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * 恢复暂停的任务。
     *
     * @param taskId 任务 ID
     * @return 恢复成功返回 Mono.TRUE
     */
    public Mono<Boolean> resume(String taskId) {
        return Mono.fromCallable(() -> manager.resume(taskId))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * 关闭响应式门面，清理所有监听器和 Sink。
     */
    public void close() {
        watchListeners.clear();
        submitSinks.values().forEach(sink -> sink.tryEmitError(new RuntimeException("ReactiveTaskManager closed")));
        submitSinks.clear();
        log.info("ReactiveTaskManager 已关闭");
    }
}
