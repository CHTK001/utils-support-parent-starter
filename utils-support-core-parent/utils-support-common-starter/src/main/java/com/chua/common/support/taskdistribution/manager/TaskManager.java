package com.chua.common.support.taskdistribution.manager;

import com.chua.common.support.taskdistribution.store.TaskStore;
import com.chua.common.support.taskdistribution.task.Task;
import com.chua.common.support.taskdistribution.task.TaskCallback;
import com.chua.common.support.taskdistribution.task.TaskResult;
import com.chua.common.support.taskdistribution.task.TaskStatus;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 任务管理器。
 *
 * <p>维护所有待执行、执行中、已完成的任务状态，支持超时检测、断线重发、
 * 取消、暂停、恢复和状态变更通知。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class TaskManager {

    /**
     * 任务状态映射：taskId -> TaskHolder
     */
    private final Map<String, TaskHolder> tasks = new ConcurrentHashMap<>();

/**
 * 状态变更监听器
 */
private final List<TaskStateListener> stateListeners = new CopyOnWriteArrayList<>();

/**
 * 任务持久化存储
 */
private TaskStore store;

/**
 * 超时检测定时器
 */
    private final ScheduledExecutorService timeoutScheduler;

    /**
     * 默认超时检测间隔（毫秒）
     */
    private static final long TIMEOUT_CHECK_INTERVAL = 1000;

    /**
     * 构造任务管理器，默认启用超时检测。
     */
    public TaskManager() {
        this(true);
    }

    /**
     * 构造任务管理器。
     *
     * @param enableTimeoutCheck 是否启用超时检测
     */
    public TaskManager(boolean enableTimeoutCheck) {
        if (enableTimeoutCheck) {
            timeoutScheduler = ThreadUtils.newSingleThreadScheduledExecutor(
                    ThreadUtils.newThreadFactory("task-manager-timeout"));
            timeoutScheduler.scheduleAtFixedRate(
                    this::checkTimeouts,
                    TIMEOUT_CHECK_INTERVAL,
                    TIMEOUT_CHECK_INTERVAL,
                    TimeUnit.MILLISECONDS
            );
        } else {
            timeoutScheduler = null;
        }
    }

    /**
     * 设置持久化存储。
     *
     * @param store 存储实现
     */
    public void setStore(TaskStore store) {
        this.store = store;
    }

    /**
     * 注册状态变更监听器。
     *
     * @param listener 监听器
     */
    public void addStateListener(TaskStateListener listener) {
        if (listener != null) {
            stateListeners.add(listener);
        }
    }

    /**
     * 移除状态变更监听器。
     *
     * @param listener 监听器
     */
    public void removeStateListener(TaskStateListener listener) {
        stateListeners.remove(listener);
    }

    /**
     * 添加任务。
     *
     * @param task     任务
     * @param callback 回调
     */
    public void addTask(Task<?> task, TaskCallback callback) {
        if (task == null || task.getTaskId() == null) {
            return;
        }
        TaskHolder holder = new TaskHolder(task, callback);
        tasks.put(task.getTaskId(), holder);
        if (store != null) {
            store.saveTask(task, TaskStatus.PENDING);
        }
        log.debug("任务添加: {}", task.getTaskId());
    }

    /**
     * 更新任务状态并通知监听器。
     *
     * @param taskId  任务 ID
     * @param status  新状态
     */
    public void updateStatus(String taskId, TaskStatus status) {
        TaskHolder holder = tasks.get(taskId);
        if (holder == null) {
            return;
        }
        TaskStatus oldStatus = holder.status;
        holder.status = status;
        if (status == TaskStatus.SUCCESS || status == TaskStatus.FAILED
                || status == TaskStatus.CANCELLED || status == TaskStatus.TIMEOUT) {
            holder.completedAt = System.currentTimeMillis();
        }
        if (store != null) {
            store.updateStatus(taskId, status);
        }
        notifyStateChanged(taskId, oldStatus, status);
    }

    /**
     * 通知状态变更。
     */
    private void notifyStateChanged(String taskId, TaskStatus oldState, TaskStatus newState) {
        for (TaskStateListener listener : stateListeners) {
            try {
                listener.onStateChanged(taskId, oldState, newState);
            } catch (Exception e) {
                log.warn("状态监听器异常: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 通知任务完成。
     */
    private void notifyCompleted(TaskResult<?> result) {
        for (TaskStateListener listener : stateListeners) {
            try {
                listener.onCompleted(result);
            } catch (Exception e) {
                log.warn("完成监听器异常: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 取消任务。
     *
     * @param taskId 任务 ID
     * @return true 表示取消成功
     */
    public boolean cancel(String taskId) {
        TaskHolder holder = tasks.get(taskId);
        if (holder == null) {
            return false;
        }
        if (holder.status == TaskStatus.SUCCESS || holder.status == TaskStatus.FAILED
                || holder.status == TaskStatus.CANCELLED) {
            return false;
        }
        updateStatus(taskId, TaskStatus.CANCELLED);
        if (holder.callback != null) {
            holder.callback.onError(taskId, "任务已被取消");
        }
        log.info("任务已取消: {}", taskId);
        return true;
    }

    /**
     * 暂停任务（服务端不再派发，工作端可继续执行）。
     *
     * @param taskId 任务 ID
     * @return true 表示暂停成功
     */
    public boolean pause(String taskId) {
        TaskHolder holder = tasks.get(taskId);
        if (holder == null || holder.status != TaskStatus.PENDING) {
            return false;
        }
        updateStatus(taskId, TaskStatus.PAUSED);
        log.info("任务已暂停: {}", taskId);
        return true;
    }

    /**
     * 恢复暂停的任务。
     *
     * @param taskId 任务 ID
     * @return true 表示恢复成功
     */
    public boolean resume(String taskId) {
        TaskHolder holder = tasks.get(taskId);
        if (holder == null || holder.status != TaskStatus.PAUSED) {
            return false;
        }
        updateStatus(taskId, TaskStatus.PENDING);
        log.info("任务已恢复: {}", taskId);
        return true;
    }

    /**
     * 处理任务结果。
     *
     * @param result 执行结果
     */
    public void handleResult(TaskResult<?> result) {
        if (result == null || result.getTaskId() == null) {
            return;
        }
        TaskHolder holder = tasks.get(result.getTaskId());
        if (holder == null) {
            log.warn("结果无对应任务: {}", result.getTaskId());
            return;
        }
        holder.result = result;
        if (result.isSuccess()) {
            updateStatus(result.getTaskId(), TaskStatus.SUCCESS);
            if (holder.callback != null) {
                holder.callback.onResult(result);
            }
        } else {
            updateStatus(result.getTaskId(), TaskStatus.FAILED);
            if (holder.callback != null) {
                holder.callback.onError(result.getTaskId(), result.getErrorMessage());
            }
        }
        if (store != null) {
            store.saveResult(result);
        }
        notifyCompleted(result);
    }

    /**
     * 获取任务状态。
     *
     * @param taskId 任务 ID
     * @return 任务状态，不存在返回 null
     */
    public TaskStatus getStatus(String taskId) {
        TaskHolder holder = tasks.get(taskId);
        return holder != null ? holder.status : null;
    }

    /**
     * 获取任务结果。
     *
     * @param taskId 任务 ID
     * @return 任务结果，不存在返回 null
     */
    public TaskResult<?> getResult(String taskId) {
        TaskHolder holder = tasks.get(taskId);
        return holder != null ? holder.result : null;
    }

    /**
     * 获取任务。
     *
     * @param taskId 任务 ID
     * @return 任务，不存在返回 null
     */
    public Task<?> getTask(String taskId) {
        TaskHolder holder = tasks.get(taskId);
        return holder != null ? holder.task : null;
    }

    /**
     * 获取所有待重试的任务。
     *
     * @return 待重试任务列表（含回调）
     */
    public List<Map.Entry<Task<?>, TaskCallback>> getRetryableTasks() {
        return tasks.values().stream()
                .filter(h -> h.status == TaskStatus.FAILED || h.status == TaskStatus.TIMEOUT)
                .filter(h -> h.retryCount < h.task.getMaxRetries())
                .map(h -> {
                    h.retryCount++;
                    return Map.<Task<?>, TaskCallback>entry(h.task, h.callback);
                })
                .collect(Collectors.toList());
    }

    /**
     * 获取所有待派发的任务（PENDING 状态，未暂停）。
     *
     * @return 待派发任务列表
     */
    public List<Task<?>> getPendingTasks() {
        return tasks.values().stream()
                .filter(h -> h.status == TaskStatus.PENDING)
                .map(h -> h.task)
                .collect(Collectors.toList());
    }

    /**
     * 超时检测。
     */
    private void checkTimeouts() {
        long now = System.currentTimeMillis();
        for (TaskHolder holder : tasks.values()) {
            if (holder.status == TaskStatus.RUNNING || holder.status == TaskStatus.PENDING) {
                long elapsed = now - holder.createdAt;
                if (elapsed > holder.task.getTimeoutMs()) {
                    updateStatus(holder.task.getTaskId(), TaskStatus.TIMEOUT);
                    log.warn("任务超时: {}, 超时设置: {}ms", holder.task.getTaskId(), holder.task.getTimeoutMs());
                    if (holder.callback != null) {
                        holder.callback.onTimeout(holder.task.getTaskId());
                    }
                }
            }
        }
    }

    /**
     * 获取所有任务视图。
     *
     * @return 任务视图列表
     */
    public List<TaskView> getAllTasks() {
        List<TaskView> views = new ArrayList<>();
        for (TaskHolder holder : tasks.values()) {
            views.add(new TaskView(
                    holder.task.getTaskId(),
                    holder.task.getTaskType(),
                    holder.status,
                    holder.retryCount,
                    holder.createdAt,
                    holder.completedAt
            ));
        }
        return views;
    }

    /**
     * 恢复未完成任务（应用重启后调用）。
     * <p>从持久化存储加载 PENDING / RUNNING 状态的任务，重置为 PENDING 待重新派发。</p>
     *
     * @return 恢复的任务列表
     */
    public List<Task<?>> recover() {
        if (store == null) {
            return List.of();
        }
        List<Task<?>> recovered = store.getRecoverableTasks();
        for (Task<?> task : recovered) {
            if (task != null && task.getTaskId() != null && !tasks.containsKey(task.getTaskId())) {
                tasks.put(task.getTaskId(), new TaskHolder(task, null));
                updateStatus(task.getTaskId(), TaskStatus.PENDING);
                log.info("任务恢复: {}", task.getTaskId());
            }
        }
        return recovered;
    }

    /**
     * 清理已完成任务。
     */
    public void cleanCompleted() {
        long now = System.currentTimeMillis();
        long expireMs = 60000;
        tasks.values().removeIf(holder -> {
            if (holder.completedAt > 0 && now - holder.completedAt > expireMs) {
                return true;
            }
            return false;
        });
    }

    /**
     * 销毁定时器。
     */
    public void destroy() {
        if (timeoutScheduler != null) {
            timeoutScheduler.shutdown();
        }
        stateListeners.clear();
    }

    /**
     * 任务持有者（内部数据结构）。
     */
    private static class TaskHolder {
        final Task<?> task;
        final TaskCallback callback;
        volatile TaskStatus status;
        volatile int retryCount;
        volatile long createdAt;
        volatile long completedAt;
        volatile TaskResult<?> result;

        TaskHolder(Task<?> task, TaskCallback callback) {
            this.task = task;
            this.callback = callback;
            this.status = TaskStatus.PENDING;
            this.createdAt = System.currentTimeMillis();
            this.retryCount = 0;
            this.completedAt = 0;
        }
    }

    /**
     * 任务视图（对外只读展示）。
     *
     * @param taskId      任务 ID
     * @param taskType    任务类型
     * @param status      状态
     * @param retryCount  重试次数
     * @param createdAt   创建时间
     * @param completedAt 完成时间
     * @author CH
     * @since 4.0.0.42
     */
    public record TaskView(
            String taskId,
            String taskType,
            TaskStatus status,
            int retryCount,
            long createdAt,
            long completedAt
    ) {
    }
}