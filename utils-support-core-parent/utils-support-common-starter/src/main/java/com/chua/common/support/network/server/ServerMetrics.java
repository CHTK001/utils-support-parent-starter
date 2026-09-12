package com.chua.common.support.network.server;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
* 服务器指标收集器。
*
* <p>记录请求总数、活跃请求数、错误数、启动时间等运行时指标。
* 协议无关，所有 Server 实现共享同一指标实例。</p>
*
* @author CH
* @since 2024/12/20
 */
public class ServerMetrics {

    /**
    * 服务器启动时间戳（毫秒）。
     */
    private final long startTime = System.currentTimeMillis();

    /**
    * 请求总数计数器。
     */
    private final LongAdder totalRequests = new LongAdder();

    /**
    * 当前活跃请求数计数器。
     */
    private final LongAdder activeRequests = new LongAdder();

    /**
    * 错误总数计数器。
     */
    private final LongAdder errorCount = new LongAdder();

    /**
    * 最近一次错误发生的时间戳（毫秒）。
     */
    private final AtomicLong lastErrorTime = new AtomicLong(0);

    /**
    * 请求延迟总和（纳秒）。
     */
    private final LongAdder totalLatencyNanos = new LongAdder();

    /**
    * 已记录延迟的请求数量。
     */
    private final LongAdder latencyCount = new LongAdder();

    /**
    * 最大请求延迟（纳秒）。
     */
    private final AtomicLong maxLatencyNanos = new AtomicLong();

    /**
    * 延迟桶上界（毫秒）。
     */
    private static final long[] LATENCY_BUCKETS = {1, 5, 10, 50, 100, 500, 1000, 5000};

    /**
    * 各延迟桶的累计请求数。
     */
    private final LongAdder[] latencyBuckets = createLatencyBuckets();

    /**
    * 记录一次请求开始。
     */
    public void incrementActive() {
        activeRequests.increment();
    }

    /**
    * 记录一次请求结束。
     */
    public void decrementActive() {
        activeRequests.decrement();
    }

    /**
    * 记录一次请求（成功或失败）。
     */
    public void incrementRequests() {
        totalRequests.increment();
    }

    /**
    * 记录一次错误。
     */
    public void incrementErrors() {
        errorCount.increment();
        lastErrorTime.set(System.currentTimeMillis());
    }

    /**
    * 记录一次请求端到端延迟。
    *
    * @param elapsedNanos 请求耗时（纳秒）
     */
    public void recordLatency(long elapsedNanos) {
        long latency = Math.max(elapsedNanos, 0);
        totalLatencyNanos.add(latency);
        latencyCount.increment();
        maxLatencyNanos.accumulateAndGet(latency, Math::max);

        long elapsedMillis = latency / 1_000_000;
        for (int i = 0; i < LATENCY_BUCKETS.length; i++) {
            if (elapsedMillis <= LATENCY_BUCKETS[i]) {
                for (int j = i; j < latencyBuckets.length; j++) {
                    latencyBuckets[j].increment();
                }
                return;
            }
        }
    }

    /**
    * 获取平均请求延迟（毫秒）。
    *
    * @return 平均请求延迟
     */
    public double getAverageLatencyMillis() {
        long count = latencyCount.sum();
        if (count == 0) {
            return 0;
        }
        return (double) totalLatencyNanos.sum() / count / 1_000_000;
    }

    /**
    * 获取最大请求延迟（毫秒）。
    *
    * @return 最大请求延迟
     */
    public long getMaxLatencyMillis() {
        return maxLatencyNanos.get() / 1_000_000;
    }

    /**
    * 获取延迟直方图快照，键为桶上界（毫秒）。
    *
    * @return 延迟直方图
     */
    public Map<String, Long> getLatencyHistogram() {
        Map<String, Long> histogram = new LinkedHashMap<>();
        for (int i = 0; i < LATENCY_BUCKETS.length; i++) {
            histogram.put("le_" + LATENCY_BUCKETS[i] + "ms", latencyBuckets[i].sum());
        }
        histogram.put("gt_" + LATENCY_BUCKETS[LATENCY_BUCKETS.length - 1] + "ms",
                latencyCount.sum() - latencyBuckets[latencyBuckets.length - 1].sum());
        histogram.put("le_inf", latencyCount.sum());
        return histogram;
    }

    /**
    * 获取请求总数。
    *
    * @return 请求总数
     */
    public long getTotalRequests() {
        return totalRequests.sum();
    }

    /**
    * 获取当前活跃请求数。
    *
    * @return 活跃请求数
     */
    public long getActiveRequests() {
        return activeRequests.sum();
    }

    /**
    * 获取峰值活跃请求数。
    *
    * @return 峰值活跃请求数
     */
    public int getPeakActive() {
        return 0;
    }

    /**
    * 获取错误总数。
    *
    * @return 错误总数
     */
    public long getErrorCount() {
        return errorCount.sum();
    }

    /**
    * 获取服务器运行时长（毫秒）。
    *
    * @return 运行时长
     */
    public long getUptime() {
        return System.currentTimeMillis() - startTime;
    }

    /**
    * 获取最近一次错误的时间戳。
    *
    * @return 错误时间戳，无错误时返回 0
     */
    public long getLastErrorTime() {
        return lastErrorTime.get();
    }

    /**
    * 获取服务器启动时间戳。
    *
    * @return 启动时间戳
     */
    public long getStartTime() {
        return startTime;
    }

    /**
    * 重置所有计数，不重置启动时间。
     */
    public void reset() {
        totalRequests.reset();
        activeRequests.reset();
        errorCount.reset();
        totalLatencyNanos.reset();
        latencyCount.reset();
        maxLatencyNanos.set(0);
        for (LongAdder latencyBucket : latencyBuckets) {
            latencyBucket.reset();
        }
    }

    /**
    * 创建延迟桶计数器。
    *
    * @return 延迟桶计数器数组
     */
    private LongAdder[] createLatencyBuckets() {
        LongAdder[] buckets = new LongAdder[LATENCY_BUCKETS.length];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new LongAdder();
        }
        return buckets;
    }
}
