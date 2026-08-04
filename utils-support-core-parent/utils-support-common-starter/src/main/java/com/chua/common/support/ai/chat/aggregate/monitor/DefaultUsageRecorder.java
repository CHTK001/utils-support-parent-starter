package com.chua.common.support.ai.chat.aggregate.monitor;

import com.chua.common.support.ai.AiUsage;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import org.jspecify.annotations.NullUnmarked;

/**
 * 默认用量记录器 — 基于 {@link AiUsage} 的内存实现。
 *
 * <p>保留最近 1000 条记录，使用线程安全的 {@link java.util.concurrent.CopyOnWriteArrayList}。
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
@Slf4j
public class DefaultUsageRecorder implements UsageRecorder {

    private static final int MAX_RECORDS = 1000;
    private final LinkedList<AiUsage> records = new LinkedList<>();
    private final Object lock = new Object();

    @Override
    public void record(AiUsage usage) {
        if (usage == null) return;
        synchronized (lock) {
            records.addFirst(usage);
            if (records.size() > MAX_RECORDS) records.removeLast();
        }
    }

    @Override
    public UsageStats stats() {
        List<AiUsage> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(records);
        }
        long total = snapshot.size();
        long tokens = snapshot.stream()
                .filter(u -> u.getTotalTokens() != null)
                .mapToLong(AiUsage::getTotalTokens).sum();
        double avgLatency = snapshot.stream()
                .filter(u -> u.getDurationMillis() != null)
                .mapToLong(AiUsage::getDurationMillis)
                .average().orElse(0);
        return UsageStats.builder()
                .totalCalls(total)
                .totalTokens(tokens)
                .avgLatencyMs(avgLatency)
                .build();
    }

    @Override
    public void reset() {
        synchronized (lock) {
            records.clear();
        }
    }
}
