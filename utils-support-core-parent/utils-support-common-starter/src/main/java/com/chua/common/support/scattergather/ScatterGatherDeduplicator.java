package com.chua.common.support.scattergather;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 请求去重器。
 * <p>基于 requestId 进行去重，防止同一请求重复执行。</p>
 *
 * @author CH
 */
@Slf4j
public class ScatterGatherDeduplicator {

    /**
     * 已处理请求缓存
     */
    private final Map<String, Long> processed = new ConcurrentHashMap<>();

    /**
     * TTL（毫秒）
     */
    private final long ttlMillis;

    /**
     * 清理执行器
     */
    private final ScheduledExecutorService cleanupExecutor;

    /**
     * 构造去重器。
     *
     * @param ttlMillis TTL时间（毫秒）
     */
    public ScatterGatherDeduplicator(long ttlMillis) {
        this.ttlMillis = ttlMillis;
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scatter-gather-dedup-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.cleanupExecutor.scheduleAtFixedRate(this::cleanup, ttlMillis, ttlMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 检查是否为重复请求。
     *
     * @param requestId 请求ID
     * @return true 重复
     */
    public boolean isDuplicate(String requestId) {
        if (requestId == null || requestId.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long timestamp = processed.putIfAbsent(requestId, now);
        return timestamp != null && (now - timestamp) < ttlMillis;
    }

    /**
     * 标记请求已处理。
     *
     * @param requestId 请求ID
     */
    public void markProcessed(String requestId) {
        if (requestId != null && !requestId.isEmpty()) {
            processed.put(requestId, System.currentTimeMillis());
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
        cleanupExecutor.shutdown();
        processed.clear();
    }
}
