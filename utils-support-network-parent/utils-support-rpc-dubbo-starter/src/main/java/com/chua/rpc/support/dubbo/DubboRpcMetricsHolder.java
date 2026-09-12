package com.chua.rpc.support.dubbo;

import com.chua.common.support.network.rpc.RpcMetrics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dubbo 协议层服务端调用指标持有者。
 *
 * <p>由 {@link DubboRpcMetricsFilter} 在每次服务端调用前后更新计数，
 * {@link DubboRpcServer#getMetrics()} 从本类读取快照。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class DubboRpcMetricsHolder {

    /**
     * 最小耗时初始哨兵值
     */
    private static final long MIN_DURATION_INIT = Long.MAX_VALUE;

    /**
     * 累计调用总数
     */
    private static final AtomicLong TOTAL_CALLS = new AtomicLong();
    /**
     * 累计成功次数
     */
    private static final AtomicLong SUCCESS_CALLS = new AtomicLong();
    /**
     * 累计失败次数
     */
    private static final AtomicLong FAILURE_CALLS = new AtomicLong();
    /**
     * 当前在途调用数
     */
    private static final AtomicLong ACTIVE_CALLS = new AtomicLong();
    /**
     * 方法键 → 方法级统计
     */
    private static final ConcurrentHashMap<String, MethodStat> METHOD_STATS = new ConcurrentHashMap<>();

    /**
     * Dubborpc指标holder。
     */
    private DubboRpcMetricsHolder() {
    }

    /**
     * 记录一次调用开始。
     *
     * @param methodKey 方法键（接口全名 + 方法名）
     */
    public static void onStart(String methodKey) {
        TOTAL_CALLS.incrementAndGet();
        ACTIVE_CALLS.incrementAndGet();
        METHOD_STATS.computeIfAbsent(methodKey, k -> new MethodStat()).total.incrementAndGet();
    }

    /**
     * 记录一次调用成功。
     *
     * @param methodKey 方法键
     * @param duration  调用耗时（毫秒）
     */
    public static void onSuccess(String methodKey, long duration) {
        SUCCESS_CALLS.incrementAndGet();
        MethodStat stat = METHOD_STATS.get(methodKey);
        if (stat != null) {
            stat.update(true, duration, "");
        }
    }

    /**
     * 记录一次调用失败。
     *
     * @param methodKey 方法键
     * @param duration  调用耗时（毫秒）
     * @param error     错误信息
     */
    public static void onFailure(String methodKey, long duration, String error) {
        FAILURE_CALLS.incrementAndGet();
        MethodStat stat = METHOD_STATS.get(methodKey);
        if (stat != null) {
            stat.update(false, duration, error);
        }
    }

    /**
     * 记录一次调用结束（减少在途数）。
     */
    public static void onComplete() {
        ACTIVE_CALLS.decrementAndGet();
    }

    /**
     * 生成当前指标快照。
     *
     * @param protocol 协议名称
     * @return 指标快照
     */
    public static RpcMetrics snapshot(String protocol) {
        RpcMetrics metrics = new RpcMetrics(protocol);
        metrics.setTotalCalls(TOTAL_CALLS.get());
        metrics.setSuccessCalls(SUCCESS_CALLS.get());
        metrics.setFailureCalls(FAILURE_CALLS.get());
        metrics.setActiveCalls(ACTIVE_CALLS.get());
        List<RpcMetrics.MethodStat> stats = new ArrayList<>(METHOD_STATS.size());
        METHOD_STATS.forEach((key, stat) -> {
            long total = stat.total.get();
            long minDuration = stat.minDuration.get() == MIN_DURATION_INIT ? 0 : stat.minDuration.get();
            stats.add(new RpcMetrics.MethodStat(key,
                    total,
                    stat.success.get(),
                    stat.failure.get(),
                    total == 0 ? 0 : stat.totalDuration.get() / total,
                    stat.maxDuration.get(),
                    minDuration,
                    stat.lastDuration.get(),
                    stat.lastTime.get(),
                    stat.lastResult,
                    stat.lastError));
        });
        stats.sort((a, b) -> Long.compare(b.total(), a.total()));
        metrics.setMethodStats(stats);
        return metrics;
    }

    /**
     * 重置全部计数（供测试或运维使用）。
     */
    public static void reset() {
        TOTAL_CALLS.set(0);
        SUCCESS_CALLS.set(0);
        FAILURE_CALLS.set(0);
        ACTIVE_CALLS.set(0);
        METHOD_STATS.clear();
    }

    /**
     * 单方法统计。
     */
    private static final class MethodStat {

        /**
         * 调用总数
         */
        final AtomicLong total = new AtomicLong();
        /**
         * 成功次数
         */
        final AtomicLong success = new AtomicLong();
        /**
         * 失败次数
         */
        final AtomicLong failure = new AtomicLong();
        /**
         * 累计总耗时（毫秒）
         */
        final AtomicLong totalDuration = new AtomicLong();
        /**
         * 最大耗时（毫秒）
         */
        final AtomicLong maxDuration = new AtomicLong();
        /**
         * 最小耗时（毫秒）
         */
        final AtomicLong minDuration = new AtomicLong(MIN_DURATION_INIT);
        /**
         * 最近一次耗时（毫秒）
         */
        final AtomicLong lastDuration = new AtomicLong();
        /**
         * 最近一次调用时间戳（毫秒）
         */
        final AtomicLong lastTime = new AtomicLong();
        /**
          * 最近一次结果（成功 / 失败 / 无）
         */
        volatile String lastResult = "NONE";
        /**
         * 最近一次错误信息
         */
        volatile String lastError = "";

        /**
         * 更新一次调用统计。
         *
         * @param ok       是否成功
         * @param duration 耗时（毫秒）
         * @param error    错误信息
         */
        void update(boolean ok, long duration, String error) {
            if (ok) {
                success.incrementAndGet();
                lastResult = "SUCCESS";
            } else {
                failure.incrementAndGet();
                lastResult = "FAILURE";
                lastError = error;
            }
            long now = System.currentTimeMillis();
            totalDuration.addAndGet(duration);
            maxDuration.accumulateAndGet(duration, Math::max);
            minDuration.accumulateAndGet(duration, Math::min);
            lastDuration.set(duration);
            lastTime.set(now);
        }
    }
}