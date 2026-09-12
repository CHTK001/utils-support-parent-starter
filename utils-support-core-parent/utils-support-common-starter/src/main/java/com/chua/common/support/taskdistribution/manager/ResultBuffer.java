package com.chua.common.support.taskdistribution.manager;

import com.chua.common.support.taskdistribution.store.TaskStore;
import com.chua.common.support.taskdistribution.task.TaskResult;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
* 结果缓冲器。
*
* <p>缓存任务结果，防止发布端掉线导致结果丢失。
* 支持 TTL 自动清理和持久化存储集成。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class ResultBuffer {

    /**
    * 结果缓冲：任务id -> 结果entry
     */
    private final Map<String, ResultEntry> buffer = new ConcurrentHashMap<>();

    /**
    * 持久化存储
     */
    private TaskStore store;

    /**
    * 默认 TTL（毫秒）
     */
    private static final long DEFAULT_TTL = 60000;

    /**
    * TTL（毫秒）
     */
    private final long ttlMillis;

    /**
    * 清理定时器
     */
    private final ScheduledExecutorService cleanupScheduler;

    /**
    * 构造结果缓冲器，默认 TTL 60 秒。
     */
    public ResultBuffer() {
        this(DEFAULT_TTL);
    }

    /**
    * 构造结果缓冲器。
    *
    * @param ttlMillis 结果缓存 TTL（毫秒），<=0 表示永不过期
     */
    public ResultBuffer(long ttlMillis) {
        this.ttlMillis = ttlMillis > 0 ? ttlMillis : Long.MAX_VALUE;
        this.cleanupScheduler = ThreadUtils.newSingleThreadScheduledExecutor(
                ThreadUtils.newThreadFactory("result-buffer-cleanup"));
        this.cleanupScheduler.scheduleAtFixedRate(this::cleanup, 30000, 30000, TimeUnit.MILLISECONDS);
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
    * 缓存结果。
    *
    * @param taskId 任务 标识
    * @param result 执行结果
     */
    public void cacheResult(String taskId, TaskResult<?> result) {
        if (taskId != null && result != null) {
            buffer.put(taskId, new ResultEntry(result, System.currentTimeMillis()));
            if (store != null) {
                store.saveResult(result);
            }
        }
    }

    /**
    * 获取结果。
    *
    * @param taskId 任务 标识
    * @return 任务结果，不存在或已过期返回 空
     */
    public TaskResult<?> getResult(String taskId) {
        ResultEntry entry = buffer.get(taskId);
        if (entry == null) {
            if (store != null) {
                return store.getResult(taskId);
            }
            return null;
        }
        if (System.currentTimeMillis() - entry.timestamp > ttlMillis) {
            buffer.remove(taskId);
            return null;
        }
        return entry.result;
    }

    /**
    * 移除结果。
    *
    * @param taskId 任务 标识
    * @return 被移除的结果，不存在返回 空
     */
    public TaskResult<?> remove(String taskId) {
        ResultEntry entry = buffer.remove(taskId);
        if (entry != null) {
            return entry.result;
        }
        return null;
    }

    /**
    * 是否包含指定任务结果。
    *
    * @param taskId 任务 标识
    * @return true 表示存在
     */
    public boolean contains(String taskId) {
        ResultEntry entry = buffer.get(taskId);
        if (entry == null) {
            return false;
        }
        if (System.currentTimeMillis() - entry.timestamp > ttlMillis) {
            buffer.remove(taskId);
            return false;
        }
        return true;
    }

    /**
    * 缓存大小。
    *
    * @return 结果数量
     */
    public int size() {
        return buffer.size();
    }

    /**
    * 清理过期结果。
     */
    private void cleanup() {
        long now = System.currentTimeMillis();
        buffer.entrySet().removeIf(entry -> (now - entry.getValue().timestamp) > ttlMillis);
    }

    /**
    * 清空所有结果。
     */
    public void clear() {
        buffer.clear();
    }

    /**
    * 关闭清理定时器。
     */
    public void close() {
        cleanupScheduler.shutdown();
        buffer.clear();
    }

    /**
    * 结果条目。
    * @author CH
    * @since 4.0.0
     */
    private static class ResultEntry {
        final TaskResult<?> result; // 结果
        final long timestamp; // 时间戳

        ResultEntry(TaskResult<?> result, long timestamp) {
            this.result = result;
            this.timestamp = timestamp;
        }
    }
}