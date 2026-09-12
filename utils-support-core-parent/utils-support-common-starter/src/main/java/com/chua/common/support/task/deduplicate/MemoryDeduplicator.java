package com.chua.common.support.task.deduplicate;

import com.chua.common.support.spi.annotations.SpiDefault;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
* 内存去重器，基于 并发哈希映射 实现。
* <p>
* 按 键 判重，支持 TTL 自动过期清理，默认 5 分钟。
* 实现 {@link AutoCloseable}：不再使用时必须调用 {@link #close()}
* 释放内部清理线程，防止线程泄漏。
* </p>
*
* @author CH
* @since 4.0.0.41
 */
@Slf4j
@SpiDefault
public class MemoryDeduplicator implements Deduplicator, AutoCloseable {

    /**
    * 默认 TTL，5 分钟
     */
    private static final long DEFAULT_TTL_MS = 5 * 60 * 1000L;

    /**
    * 清理线程执行间隔，1 分钟
     */
    private static final long CLEANUP_INTERVAL_MS = 60_000L;

    /** TTLMS */
    private final long ttlMs;
    /** 处理 */
    private final Map<String, Long> processed;
    /** Cleanup执行器 */
    private final ScheduledThreadPoolExecutor cleanupExecutor;

    /**
    * 构造去重器，使用默认 TTL 5 分钟。
     */
    public MemoryDeduplicator() {
        this(DEFAULT_TTL_MS);
    }

    /**
    * 构造去重器，指定 TTL。
    *
    * @param ttlMs TTL 毫秒数，超过该时间未访问的 键 将被清理
     */
    public MemoryDeduplicator(long ttlMs) {
        this.ttlMs = ttlMs;
        this.processed = new ConcurrentHashMap<>();
        // P3C 1.6：显式构造线程池（核心 1、无上限队列、命名守护线程），禁用工厂方法
        this.cleanupExecutor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "deduplicator-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.cleanupExecutor.scheduleAtFixedRate(this::cleanup,
                CLEANUP_INTERVAL_MS, CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
    * 判断 键 是否已处理过。
    *
    * @param key 去重 键
    * @return true 表示已处理（重复）
     */
    @Override
    public boolean isDuplicate(String key) {
        return processed.containsKey(key);
    }

    /**
    * 标记 键 为已处理。
    *
    * @param key 去重 键
     */
    @Override
    public void markProcessed(String key) {
        processed.put(key, System.currentTimeMillis());
    }

    /**
    * 清空所有处理记录。
     */
    @Override
    public void clear() {
        processed.clear();
    }

    /**
    * 获取当前记录数。
    *
    * @return 记录数
     */
    @Override
    public int size() {
        return processed.size();
    }

    /**
    * 释放清理线程：未关闭的实例在长时间运行环境中会造成线程泄漏。
     */
    @Override
    public void close() {
        cleanupExecutor.shutdownNow();
        log.debug("MemoryDeduplicator closed, remaining keys={}", processed.size());
    }

    /**
    * 清理过期的 键。
     */
    private void cleanup() {
        long now = System.currentTimeMillis();
        long threshold = now - ttlMs;
        processed.values().removeIf(timestamp -> timestamp < threshold);
    }
}
