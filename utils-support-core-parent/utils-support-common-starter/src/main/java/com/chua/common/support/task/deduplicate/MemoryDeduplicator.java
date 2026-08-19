package com.chua.common.support.task.deduplicate;

import com.chua.common.support.spi.annotations.SpiDefault;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 内存去重器，基于 ConcurrentHashMap 实现。
 * <p>
 * 按 key 判重，支持 TTL 自动过期清理，默认 5 分钟。
 * </p>
 *
 * @author CH
 * @since 4.0.0.41
 */
@Slf4j
@SpiDefault
public class MemoryDeduplicator implements Deduplicator {

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
    /** processed */
    private final Map<String, Long> processed;
    /** Cleanup执行器 */
    private final ScheduledExecutorService cleanupExecutor;

    /**
     * 构造去重器，使用默认 TTL 5 分钟。
     */
    public MemoryDeduplicator() {
        this(DEFAULT_TTL_MS);
    }

    /**
     * 构造去重器，指定 TTL。
     *
     * @param ttlMs TTL 毫秒数，超过该时间未访问的 key 将被清理
     */
    public MemoryDeduplicator(long ttlMs) {
        this.ttlMs = ttlMs;
        this.processed = new ConcurrentHashMap<>();
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "deduplicator-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.cleanupExecutor.scheduleAtFixedRate(this::cleanup,
                CLEANUP_INTERVAL_MS, CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    /** 是否Duplicate */
    public boolean isDuplicate(String key) {
        return processed.containsKey(key);
    }

    @Override
    /** 标记Processed */
    public void markProcessed(String key) {
        processed.put(key, System.currentTimeMillis());
    }

    @Override
    /** Clear */
    public void clear() {
        processed.clear();
    }

    @Override
    /** 获取大小 */
    public int size() {
        return processed.size();
    }

    /**
     * 清理过期的 key。
     */
    private void cleanup() {
        long now = System.currentTimeMillis();
        long threshold = now - ttlMs;
        processed.values().removeIf(timestamp -> timestamp < threshold);
    }
}
