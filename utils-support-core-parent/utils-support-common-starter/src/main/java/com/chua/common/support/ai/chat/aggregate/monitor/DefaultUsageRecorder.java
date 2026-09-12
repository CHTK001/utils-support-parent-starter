package com.chua.common.support.ai.chat.aggregate.monitor;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.utils.CollectionUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedList;
import java.util.List;

/**
* 默认用量记录器 — 基于 {@link AiUsage} 的内存实现。
*
* <p>保留最近 1000 条记录，所有读写均在锁内进行以保证线程安全。
* 适合单机低并发场景；如需分布式统计请接入独立的指标后端。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class DefaultUsageRecorder implements UsageRecorder {

    /**
    * 内存中保留的最大记录条数；超过此值时按 FIFO 淘汰最早记录
     */
    private static final int MAX_RECORDS = 1000;

    /**
    * 用量记录链表，链表头为最新记录
     */
    private final LinkedList<AiUsage> records = new LinkedList<>();

    /**
    * 读写锁对象，保护 {@link #records} 的并发访问
     */
    private final Object lock = new Object();

    /**
    * 记录一次 AI 调用用量；若超过最大保留数则丢弃最早记录。
    *
    * @param usage 本次调用的用量数据，允许为 null（null 时静默忽略）
     */
    @Override
    public void record(AiUsage usage) {
        if (usage == null) {
            return;
        }
        synchronized (lock) {
            records.addFirst(usage);
            if (records.size() > MAX_RECORDS) {
                records.removeLast();
            }
        }
    }

    /**
    * 汇总当前内存中的用量数据并返回统计快照。
    *
    * <p>统计项：总调用次数、总 token 数、平均延迟（毫秒）。
    * 对应的 getter 为 null 的记录会被忽略，避免影响聚合结果。</p>
    *
    * @return 用量统计快照
     */
    @Override
    public UsageStats stats() {
        List<AiUsage> snapshot;
        synchronized (lock) {
            snapshot = CollectionUtils.newArrayList(records);
        }
        long total = snapshot.size();
        long tokens = snapshot.stream()
                .filter(u -> u.getTotalTokens() != null)
                .mapToLong(AiUsage::getTotalTokens)
                .sum();
        double avgLatency = snapshot.stream()
                .filter(u -> u.getDurationMillis() != null)
                .mapToLong(AiUsage::getDurationMillis)
                .average()
                .orElse(0);
        return UsageStats.builder()
                .totalCalls(total)
                .totalTokens(tokens)
                .avgLatencyMs(avgLatency)
                .build();
    }

    /**
    * 清空内存中的全部用量记录。
     */
    @Override
    public void reset() {
        synchronized (lock) {
            records.clear();
        }
    }
}
