package com.chua.common.support.network.server;

import com.chua.common.support.collection.wrap.Wraps;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 服务器指标收集器。
 *
 * <p>记录请求总数、活跃请求数、错误数、启动时间等运行时指标。
 * 协议无关，所有 Server 实现共享同一指标实例。</p>
 *
 * <h2>百万连接下的设计约束</h2>
 * <p>本类的方法全部位于每请求 / 每连接的热路径上，因此：</p>
 * <ul>
 *   <li>计数一律使用 {@link LongAdder}，避免单点 {@code AtomicLong} 在几十个核上成为缓存行争用热点；</li>
 *   <li>延迟直方图只在「命中桶」上自增一次，累积值改到读取时计算
 *       （见 {@link #getLatencyHistogram()}）。原实现每次请求都要从命中桶一路加到末桶，
 *       分片数越多、请求越快，这条循环的相对开销越大；</li>
 *   <li>峰值只在「超过当前峰值」时才走 CAS，稳态下只是一次普通读，不引入争用；</li>
 *   <li>连接数用 {@link AtomicInteger}（需要按上限做 CAS 抢占），总量用 {@link LongAdder}。</li>
 * </ul>
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
     * 峰值活跃请求数。
     */
    private final AtomicInteger peakActive = new AtomicInteger();

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
     * 当前活跃连接数（需要按 maxConnections 做 CAS 抢占，故不能是 LongAdder）。
     */
    private final AtomicInteger activeConnections = new AtomicInteger();

    /**
     * 峰值并发连接数。
     */
    private final AtomicInteger peakConnections = new AtomicInteger();

    /**
     * 累计接受过的连接数。
     */
    private final LongAdder totalConnections = new LongAdder();

    /**
     * 因超过 maxConnections 被拒绝的连接数。
     */
    private final LongAdder rejectedConnections = new LongAdder();

    /**
     * 延迟桶上界（毫秒）。
     */
    private static final long[] LATENCY_BUCKETS = {1, 5, 10, 50, 100, 500, 1000, 5000};

    /**
     * 各延迟桶的请求数（非累积，累积值在读取时计算）。
     */
    private final LongAdder[] latencyBuckets = createLatencyBuckets();

    /**
     * 超出最大桶（{@code >5000ms}）的请求数。
     */
    private final LongAdder latencyOverflow = new LongAdder();

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
     * <p>分桶只在命中桶上自增一次，不向上累积：累积语义由 {@link #getLatencyHistogram()}
     * 在读取时重建，读取频率远低于写入频率，因此把 O(桶数) 的循环从热路径挪到了冷路径。</p>
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
                latencyBuckets[i].increment();
                return;
            }
        }
        latencyOverflow.increment();
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
     * <p>返回的是累积分布（{@code le_Xms} 表示「耗时 ≤ X 毫秒」的请求数），
     * 与历史实现保持一致；内部计数为单桶值，此处按桶顺序累加还原。</p>
     *
     * @return 延迟直方图
     */
    public Map<String, Long> getLatencyHistogram() {
        Map<String, Long> histogram = Wraps.linkedMap();
        long cumulative = 0;
        for (int i = 0; i < LATENCY_BUCKETS.length; i++) {
            cumulative += latencyBuckets[i].sum();
            histogram.put("le_" + LATENCY_BUCKETS[i] + "ms", cumulative);
        }
        long total = latencyCount.sum();
        histogram.put("gt_" + LATENCY_BUCKETS[LATENCY_BUCKETS.length - 1] + "ms",
                Math.max(total - cumulative, 0));
        histogram.put("le_inf", total);
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
     * <p>峰值在 {@link #incrementActive()} 之后按需刷新：只有当前活跃数超过历史峰值时才发生
     * 一次 CAS，稳态下无额外开销。</p>
     *
     * @return 峰值活跃请求数
     */
    public int getPeakActive() {
        return peakActive.get();
    }

    /**
     * 刷新峰值活跃请求数（由 {@link AbstractServer} 在请求进入时调用）。
     *
     * @param current 当前活跃请求数
     */
    void updatePeakActive(long current) {
        int value = current > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) current;
        int peak = peakActive.get();
        if (value > peak) {
            peakActive.accumulateAndGet(value, Math::max);
        }
    }

    /**
     * 尝试登记一个新连接。
     *
     * <p>用 CAS 一次完成「判上限 + 自增 + 刷峰值」，避免先读后写导致超过 {@code limit}
     * 的瞬时超发（高并发 accept 下必然发生）。</p>
     *
     * @param limit 连接数上限，{@code <=0} 表示不限制
     * @return 登记成功返回 true；已达上限返回 false（调用方应直接关闭该连接）
     */
    public boolean tryOpenConnection(int limit) {
        while (true) {
            int current = activeConnections.get();
            if (limit > 0 && current >= limit) {
                rejectedConnections.increment();
                return false;
            }
            if (activeConnections.compareAndSet(current, current + 1)) {
                totalConnections.increment();
                int peak = peakConnections.get();
                if (current + 1 > peak) {
                    peakConnections.accumulateAndGet(current + 1, Math::max);
                }
                return true;
            }
        }
    }

    /**
     * 登记一个连接关闭。
     *
     * <p>用 CAS 递减而不是「先判断再自减」：后者在并发关闭时会出现两个线程同时看到
     * {@code 1 > 0} 然后各自自减，把活跃数打到 -1。活跃数一旦为负，
     * {@link #tryOpenConnection(int)} 的上限判定就会多放行一个连接，且这 1 个名额
     * 永远补不回来（长时间运行后表现为「连接数总比真实值少」）。</p>
     */
    public void closeConnection() {
        while (true) {
            int current = activeConnections.get();
            if (current <= 0) {
                // 重复释放：保持 0，不制造负数
                return;
            }
            if (activeConnections.compareAndSet(current, current - 1)) {
                return;
            }
        }
    }

    /**
     * 获取当前活跃连接数。
     *
     * @return 活跃连接数
     */
    public int getActiveConnections() {
        return activeConnections.get();
    }

    /**
     * 获取峰值连接数。
     *
     * @return 峰值并发连接数
     */
    public int getPeakConnections() {
        return peakConnections.get();
    }

    /**
     * 获取累计接受的连接数。
     *
     * @return 累计连接数
     */
    public long getTotalConnections() {
        return totalConnections.sum();
    }

    /**
     * 获取被连接上限拒绝的连接数。
     *
     * @return 拒绝数
     */
    public long getRejectedConnections() {
        return rejectedConnections.sum();
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
     *
     * <p>连接数不在此重置：它是「当前存量」，清零会让 {@link #tryOpenConnection(int)}
     * 的上限判断失真，进而放行超过 {@code maxConnections} 的连接。</p>
     */
    public void reset() {
        totalRequests.reset();
        activeRequests.reset();
        peakActive.set(0);
        errorCount.reset();
        totalLatencyNanos.reset();
        latencyCount.reset();
        maxLatencyNanos.set(0);
        latencyOverflow.reset();
        for (LongAdder latencyBucket : latencyBuckets) {
            latencyBucket.reset();
        }
    }

    /**
     * 重置连接存量计数（服务停止时调用）。
     *
     * <p>只清「当前存量」：{@link #getTotalConnections()} 与拒绝数属于累计值，跨重启保留，
     * 否则监控上「重启丢了全部连接统计」会掩盖问题。</p>
     */
    void resetConnections() {
        activeConnections.set(0);
        peakConnections.set(0);
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
