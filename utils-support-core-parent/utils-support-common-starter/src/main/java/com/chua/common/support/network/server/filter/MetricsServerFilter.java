package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.utils.ThreadUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 指标收集过滤器。
 *
 * <p>在过滤器链中拦截每个请求，记录端到端延迟，并按固定周期向回调输出聚合指标。
 * 指标包含：p50 / p75 / p90 / p95 / p99 延迟、QPS、TPS 等。</p>
 *
 * <p>使用方式：</p>
 * <pre>{@code
 * server.addFilter(new MetricsServerFilter(
 *     snapshot -> System.out.println(snapshot),
 *     10
 * ));
 * }</pre>
 *
 * @author CH
 * @since 2026/07/24
 */
public class MetricsServerFilter implements ServerFilter {

    /**
     * 指标快照。
     */
    public static class MetricsSnapshot {
        /** 总数requests */
        private final long totalRequests;
        /** 错误数量 */
        private final long errorCount;
        /** 是否激活requests */
        private final long activeRequests;
        /** AVGlatencyMS */
        private final double avgLatencyMs;
        /** 最大值latencyMS */
        private final long maxLatencyMs;
        /** P50ms */
        private final double p50Ms;
        /** P75ms */
        private final double p75Ms;
        /** P90ms */
        private final double p90Ms;
        /** P95ms */
        private final double p95Ms;
        /** P99ms */
        private final double p99Ms;
        /** QPS */
        private final double qps;
        /** TPS */
        private final double tps;
        /** UptimeMS */
        private final long uptimeMs;

        /**
         * 创建 MetricsSnapshot 实例
         * @param totalRequests totalRequests
         * @param errorCount errorCount
         * @param activeRequests activeRequests
         * @param avgLatencyMs avgLatencyMs
         * @param maxLatencyMs maxLatencyMs
         * @param p50Ms p50Ms
         * @param p75Ms p75Ms
         * @param p90Ms p90Ms
         * @param p95Ms p95Ms
         * @param p99Ms p99Ms
         * @param qps qps
         * @param tps tps
         * @param uptimeMs uptimeMs
         */
        public MetricsSnapshot(long totalRequests, long errorCount, long activeRequests,
                               double avgLatencyMs, long maxLatencyMs,
                               double p50Ms, double p75Ms, double p90Ms, double p95Ms, double p99Ms,
                               double qps, double tps, long uptimeMs) {
            this.totalRequests = totalRequests;
            this.errorCount = errorCount;
            this.activeRequests = activeRequests;
            this.avgLatencyMs = avgLatencyMs;
            this.maxLatencyMs = maxLatencyMs;
            this.p50Ms = p50Ms;
            this.p75Ms = p75Ms;
            this.p90Ms = p90Ms;
            this.p95Ms = p95Ms;
            this.p99Ms = p99Ms;
            this.qps = qps;
            this.tps = tps;
            this.uptimeMs = uptimeMs;
        }

        /** 获取总计Requests */
        public long getTotalRequests() { return totalRequests; }
        /** 获取记录错误计算数量 */
        public long getErrorCount() { return errorCount; }
        /** 获取ActiveRequests */
        public long getActiveRequests() { return activeRequests; }
        /** 获取平均值LatencyMs */
        public double getAvgLatencyMs() { return avgLatencyMs; }
        /** 获取最大值LatencyMs */
        public long getMaxLatencyMs() { return maxLatencyMs; }
        /** 获取Ms */
        public double getP50Ms() { return p50Ms; }
        /** 获取Ms */
        public double getP75Ms() { return p75Ms; }
        /** 获取Ms */
        public double getP90Ms() { return p90Ms; }
        /** 获取Ms */
        public double getP95Ms() { return p95Ms; }
        /** 获取Ms */
        public double getP99Ms() { return p99Ms; }
        /** 获取Qps */
        public double getQps() { return qps; }
        /** 获取Tps */
        public double getTps() { return tps; }
        /** 获取UptimeMs */
        public long getUptimeMs() { return uptimeMs; }

        @Override
        /** ToString */
        public String toString() {
            return "MetricsSnapshot{" +
                    "totalRequests=" + totalRequests +
                    ", errorCount=" + errorCount +
                    ", activeRequests=" + activeRequests +
                    ", avgLatencyMs=" + String.format("%.2f", avgLatencyMs) +
                    ", maxLatencyMs=" + maxLatencyMs +
                    ", p50Ms=" + String.format("%.2f", p50Ms) +
                    ", p75Ms=" + String.format("%.2f", p75Ms) +
                    ", p90Ms=" + String.format("%.2f", p90Ms) +
                    ", p95Ms=" + String.format("%.2f", p95Ms) +
                    ", p99Ms=" + String.format("%.2f", p99Ms) +
                    ", qps=" + String.format("%.2f", qps) +
                    ", tps=" + String.format("%.2f", tps) +
                    ", uptimeMs=" + uptimeMs +
                    '}';
        }
    }

    /**
     * 指标回调接口。
     */
    public interface MetricsCallback {
        void onMetrics(MetricsSnapshot snapshot);
    }

    /** Callback */
    private final MetricsCallback callback;
    /** Period秒 */
    private final int periodSeconds;
    /** Latencies */
    private final ConcurrentLinkedQueue<Long> latencies;
    /** 总数requests */
    private final LongAdder totalRequests = new LongAdder();
    /** 错误数量 */
    private final LongAdder errorCount = new LongAdder();
    /** 总数latencynanos */
    private final LongAdder totalLatencyNanos = new LongAdder();
    /** 最大值latencynanos */
    private final AtomicLong maxLatencyNanos = new AtomicLong();
    private volatile ScheduledFuture<?> scheduledFuture;
    /** 开始时间 */
    private final long startTime = System.currentTimeMillis();
    /** lastPeriodRequests */
    private volatile long lastPeriodRequests = 0;
    /** lastPeriodTimestamp */
    private volatile long lastPeriodTimestamp;

    /**
     * 创建指标收集过滤器。
     *
     * @param callback      指标回调
     * @param periodSeconds 输出周期（秒）
     */
    public MetricsServerFilter(MetricsCallback callback, int periodSeconds) {
        this.callback = Objects.requireNonNull(callback, "callback must not be null");
        this.periodSeconds = periodSeconds > 0 ? periodSeconds : 10;
        this.latencies = new ConcurrentLinkedQueue<>();
        this.lastPeriodTimestamp = System.currentTimeMillis();
    }

    @Override
    /** 获取Order */
    public int getOrder() {
        return Integer.MIN_VALUE + 100;
    }

    @Override
    /** 初始化 */
    public void init(ServerFilterConfig config) throws Exception {
        ScheduledExecutorService scheduler = ThreadUtils.newDaemonSingleThreadScheduledExecutor("metrics-scheduler");
        scheduledFuture = scheduler.scheduleAtFixedRate(
                this::computeAndCallback, 0, periodSeconds, TimeUnit.SECONDS);
    }

    @Override
    /** 销毁 */
    public void destroy() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
        computeAndCallback();
    }

    @Override
    /** Do过滤 */
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        totalRequests.increment();
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long elapsedNanos = System.nanoTime() - start;
            totalLatencyNanos.add(elapsedNanos);
            maxLatencyNanos.accumulateAndGet(elapsedNanos, Math::max);
            latencies.add(elapsedNanos);
            int status = response.getStatus();
            if (status >= 400) {
                errorCount.increment();
            }
        }
    }

    /** ComputeAndCallback */
    private void computeAndCallback() {
        long now = System.currentTimeMillis();
        long total = totalRequests.sum();
        long errors = errorCount.sum();
        long totalLatency = totalLatencyNanos.sum();
        long maxLatency = maxLatencyNanos.get();
        long uptime = now - startTime;

        double avgLatencyMs = total > 0 ? (double) totalLatency / total / 1_000_000 : 0;
        long maxLatencyMs = maxLatency / 1_000_000;

        List<Long> snapshot;
        synchronized (latencies) {
            snapshot = new ArrayList<>(latencies);
            latencies.clear();
        }
        snapshot.sort(Long::compareTo);

        double p50 = 0;
        double p75 = 0;
        double p90 = 0;
        double p95 = 0;
        double p99 = 0;
        if (!snapshot.isEmpty()) {
            p50 = getPercentile(snapshot, 0.50) / 1_000_000.0;
            p75 = getPercentile(snapshot, 0.75) / 1_000_000.0;
            p90 = getPercentile(snapshot, 0.90) / 1_000_000.0;
            p95 = getPercentile(snapshot, 0.95) / 1_000_000.0;
            p99 = getPercentile(snapshot, 0.99) / 1_000_000.0;
        }

        long periodRequests = total - lastPeriodRequests;
        double periodSeconds = Math.max((now - lastPeriodTimestamp) / 1000.0, 0.001);
        double qps = periodRequests / periodSeconds;
        double tps = (periodRequests - Math.min(errors, periodRequests)) / periodSeconds;

        lastPeriodRequests = total;
        lastPeriodTimestamp = now;

        MetricsSnapshot snap = new MetricsSnapshot(
                total, errors, 0,
                avgLatencyMs, maxLatencyMs,
                p50, p75, p90, p95, p99,
                qps, tps, uptime
        );
        callback.onMetrics(snap);
    }

    /** 获取Percentile */
    private static long getPercentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }
}
