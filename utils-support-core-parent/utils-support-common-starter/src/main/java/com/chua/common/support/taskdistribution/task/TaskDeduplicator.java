package com.chua.common.support.taskdistribution.task;

import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 任务去重器。
 *
 * <p>基于 taskId 进行去重，防止同一任务被重复派发。
 * 内部使用 ConcurrentHashMap + TTL 自动清理过期记录。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class TaskDeduplicator {

    /**
     * 已处理任务缓存
     */
    private final Map<String, Long> processed = new ConcurrentHashMap<>();

    /**
     * TTL（毫秒），默认 5 分钟
     */
    private final long ttlMillis;

    /**
     * 清理定时器
     */
    private final ScheduledExecutorService cleanupScheduler;

    /**
     * 默认 TTL：5 分钟
     */
    private static final long DEFAULT_TTL = 300000;

    /**
     * 构造任务去重器。
     */
    public TaskDeduplicator() {
        this(DEFAULT_TTL);
    }

    /**
     * 构造任务去重器。
     *
     * @param ttlMillis 去重记录 TTL（毫秒）
     */
    public TaskDeduplicator(long ttlMillis) {
        this.ttlMillis = ttlMillis > 0 ? ttlMillis : DEFAULT_TTL;
        this.cleanupScheduler = ThreadUtils.newSingleThreadScheduledExecutor(
                ThreadUtils.newThreadFactory("task-dedup-cleanup"));
        this.cleanupScheduler.scheduleAtFixedRate(this::cleanup, ttlMillis, ttlMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 检查是否为重复任务。
     *
     * @param taskId 任务 ID
     * @return true 表示重复
     */
    public boolean isDuplicate(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long timestamp = processed.putIfAbsent(taskId, now);
        if (timestamp == null) {
            return false;
        }
        boolean duplicate = (now - timestamp) < ttlMillis;
        if (duplicate) {
            log.warn("检测到重复任务: {}", taskId);
        }
        return duplicate;
    }

    /**
     * 标记任务已处理。
     *
     * @param taskId 任务 ID
     */
    public void markProcessed(String taskId) {
        if (taskId != null && !taskId.isEmpty()) {
            processed.put(taskId, System.currentTimeMillis());
        }
    }

    /**
     * 移除任务去重标记。
     *
     * @param taskId 任务 ID
     */
    public void remove(String taskId) {
        if (taskId != null) {
            processed.remove(taskId);
        }
    }

    /**
     * 清理过期记录。
     */
    private void cleanup() {
        long now = System.currentTimeMillis();
        processed.entrySet().removeIf(entry -> (now - entry.getValue()) >= ttlMillis);
    }

    /**
     * 关闭去重器。
     */
    public void close() {
        cleanupScheduler.shutdown();
        processed.clear();
    }
}