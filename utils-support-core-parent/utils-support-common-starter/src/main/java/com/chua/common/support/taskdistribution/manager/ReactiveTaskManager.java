package com.chua.common.support.taskdistribution.manager;

import com.chua.common.support.taskdistribution.task.Task;
import com.chua.common.support.taskdistribution.task.TaskCallback;
import com.chua.common.support.taskdistribution.task.TaskIdGenerator;
import com.chua.common.support.taskdistribution.task.TaskPriority;
import com.chua.common.support.taskdistribution.task.TaskResult;
import com.chua.common.support.taskdistribution.task.TaskStatus;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务管理器响应式门面，将 {@link TaskManager} 的回调式 API 包装为 Reactor 响应式 API。
 *
 * <p>底层使用 {@code CompletableFuture} 桥接回调机制，再通过 {@code Mono.fromFuture()}
 * 转换为 Reactor 响应式流，避免直接使用 Sinks 在测试环境中的调度竞争问题。</p>
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
 * // 订阅状态变更流
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
     * taskId -> CompletableFuture（submit 单次等待）
     */
    private final ConcurrentHashMap<String, CompletableFuture<TaskResult<?>>> submitFutures =
            new ConcurrentHashMap<>();

    /**
     * taskId -> 监听器列表（用于清理）
     */
    private final ConcurrentHashMap<String, List<TaskStateListener>> watchListeners =
            new ConcurrentHashMap<>();

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
     * <p>任务提交后立即返回 Mono，当任务执行完成（SUCCESS / FAILED / CANCELLED / TIMEOUT）
     * 时发射结果；若任务已存在则直接返回当前结果。</p>
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
            return (Mono<TaskResult<T>>) (Mono<?>) Mono.just(existing);
        }
        // 用 CompletableFuture 桥接回调
        CompletableFuture<TaskResult<?>> future = new CompletableFuture<>();
        submitFutures.put(taskId, future);
        TaskCallback callback = new TaskCallback() {
            @Override
            public void onResult(TaskResult<?> result) {
                future.complete(result);
                submitFutures.remove(taskId);
            }
            @Override
            public void onError(String id, String error) {
                // 失败结果若已存储则作为值发射（任务已终态），否则以异常完成（超时/取消）
                TaskResult<?> stored = manager.getResult(id);
                if (stored != null) {
                    future.complete(stored);
                } else {
                    future.completeExceptionally(new RuntimeException(error));
                }
                submitFutures.remove(id);
            }
            @Override
            public void onTimeout(String id) {
                future.completeExceptionally(new RuntimeException("Task timeout: " + id));
                submitFutures.remove(id);
            }
        };
        manager.addTask(task, callback);
        Mono<TaskResult<?>> raw = Mono.fromFuture(future)
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
        return (Mono<TaskResult<T>>) (Mono<?>) raw;
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
     * 订阅指定任务的完成结果流。
     *
     * <p>当事件监听器触发 onCompleted 时发射 {@link TaskResult}，
     * 流持续到任务进入终态后自动完成。</p>
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
                // 只关注完成事件
            }
            @Override
            public void onCompleted(TaskResult<?> result) {
                if (!taskId.equals(result.getTaskId())) {
                    return;
                }
                sink.tryEmitNext(result);
                TaskStatus current = manager.getStatus(taskId);
                if (current == TaskStatus.SUCCESS || current == TaskStatus.FAILED
                        || current == TaskStatus.CANCELLED || current == TaskStatus.TIMEOUT) {
                    unwatch(taskId);
                    sink.tryEmitComplete();
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
                        CompletableFuture<TaskResult<?>> future = submitFutures.remove(taskId);
                        if (future != null && !future.isDone()) {
                            future.completeExceptionally(
                                    new RuntimeException("Task cancelled: " + taskId));
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
     * 关闭响应式门面，清理所有监听器和 Future。
     */
    public void close() {
        watchListeners.clear();
        submitFutures.forEach((id, future) -> {
            if (!future.isDone()) {
                future.completeExceptionally(new RuntimeException("ReactiveTaskManager closed"));
            }
        });
        submitFutures.clear();
        log.info("ReactiveTaskManager 已关闭");
    }

    // ==================== 链式构建 API ====================

    /**
     * 开启链式任务构建，自动生成 taskId。
     *
     * <p>用法：</p>
     * <pre>{@code
     * Mono<TaskResult<String>> result = reactive.task("email-send", payload)
     *         .traceId("trace-123")
     *         .tag("priority", "high")
     *         .timeout(Duration.ofSeconds(10))
     *         .maxRetries(2)
     *         .submit();   // 返回 Mono，订阅后提交
     * }</pre>
     *
     * @param taskType 任务类型
     * @param payload  负载数据
     * @param <T>      负载类型
     * @return 链式构建器
     */
    public <T> TaskFluent<T> task(String taskType, T payload) {
        return new TaskFluent<>(this, taskType, payload);
    }

    /**
     * 链式任务构建器，流式设置任务属性后以 {@link #submit()} 提交。
     *
     * @param <T> 负载数据类型
     * @author CH
     * @since 4.0.0.43
     */
    public static class TaskFluent<T> {

        /**
         * 目标响应式门面
         */
        private final ReactiveTaskManager owner;

        /**
         * 任务类型
         */
        private final String taskType;

        /**
         * 负载数据
         */
        private final T payload;

        /**
         * 自动生成的任务 ID
         */
        private final String taskId = TaskIdGenerator.generateId();

        /**
         * 链路追踪 ID（默认与 taskId 相同）
         */
        private String traceId;

        /**
         * 父任务 ID
         */
        private String parentTaskId;

        /**
         * 任务标签
         */
        private final Map<String, String> tags = new HashMap<>();

        /**
         * 分片数量
         */
        private int shardCount = 1;

        /**
         * 分片键
         */
        private String shardKey;

        /**
         * 超时毫秒数
         */
        private long timeoutMs = 30000;

        /**
         * 最大重试次数
         */
        private int maxRetries = 3;

        /**
         * 任务优先级
         */
        private TaskPriority priority = TaskPriority.MEDIUM;

        /**
         * 构造链式构建器。
         *
         * @param owner    目标门面
         * @param taskType 任务类型
         * @param payload  负载数据
         */
        TaskFluent(ReactiveTaskManager owner, String taskType, T payload) {
            this.owner = owner;
            this.taskType = taskType;
            this.payload = payload;
            this.traceId = taskId;
        }

        /**
         * 设置链路追踪 ID。
         *
         * @param traceId 追踪 ID
         * @return this
         */
        public TaskFluent<T> traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        /**
         * 设置父任务 ID（子任务场景）。
         *
         * @param parentTaskId 父任务 ID
         * @return this
         */
        public TaskFluent<T> parentTaskId(String parentTaskId) {
            this.parentTaskId = parentTaskId;
            return this;
        }

        /**
         * 添加标签。
         *
         * @param key   标签键
         * @param value 标签值
         * @return this
         */
        public TaskFluent<T> tag(String key, String value) {
            tags.put(key, value);
            return this;
        }

        /**
         * 批量添加标签。
         *
         * @param tags 标签映射
         * @return this
         */
        public TaskFluent<T> tags(Map<String, String> tags) {
            if (tags != null) {
                this.tags.putAll(tags);
            }
            return this;
        }

        /**
         * 设置分片。
         *
         * @param count    分片数量
         * @param shardKey 分片键
         * @return this
         */
        public TaskFluent<T> shard(int count, String shardKey) {
            this.shardCount = Math.max(1, count);
            this.shardKey = shardKey;
            return this;
        }

        /**
         * 设置执行超时时间。
         *
         * @param timeout 超时时长
         * @return this
         */
        public TaskFluent<T> timeout(Duration timeout) {
            this.timeoutMs = timeout != null ? timeout.toMillis() : 30000L;
            return this;
        }

        /**
         * 设置最大重试次数。
         *
         * @param maxRetries 重试次数
         * @return this
         */
        public TaskFluent<T> maxRetries(int maxRetries) {
            this.maxRetries = Math.max(0, maxRetries);
            return this;
        }

        /**
         * 设置优先级。
         *
         * @param priority 优先级
         * @return this
         */
        public TaskFluent<T> priority(TaskPriority priority) {
            this.priority = priority != null ? priority : TaskPriority.MEDIUM;
            return this;
        }

        /**
         * 构建任务对象（不提交）。
         *
         * @return 任务实例
         */
        public Task<T> build() {
            return Task.<T>builder()
                    .taskId(taskId)
                    .traceId(traceId)
                    .parentTaskId(parentTaskId)
                    .taskType(taskType)
                    .payload(payload)
                    .tags(tags)
                    .shardCount(shardCount)
                    .shardKey(shardKey)
                    .timeoutMs(timeoutMs)
                    .maxRetries(maxRetries)
                    .priority(priority)
                    .build();
        }

        /**
         * 构建并提交任务，返回结果 Mono。
         *
         * @return 任务结果 Mono
         */
        public Mono<TaskResult<T>> submit() {
            return owner.submit(build());
        }

        /**
         * 构建并提交任务，同时订阅完成事件流。
         *
         * @return 结果与监听流的元组（Mono 结果 + Flux 流）
         */
        public reactor.util.function.Tuple2<Mono<TaskResult<T>>, Flux<TaskResult<T>>> submitAndWatch() {
            Task<T> built = build();
            return reactor.util.function.Tuples.of(
                    owner.submit(built), owner.watch(built.getTaskId()));
        }
    }
}
