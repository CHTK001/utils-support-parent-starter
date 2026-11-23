// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.ServerMetrics;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * 异步基准报告过滤器：服务端侧直接产出 p50/p99/p999 延迟分位、QPS、RPM 与并发连接（ramp）等指标。
 *
 * <p>免去每次现搭 benchmark 客户端：开启后过滤器在服务进程内统计运行指标，两种取数方式：</p>
 * <ul>
 *   <li><b>Pull 端点</b>：GET {@code /benchmark-report} 返回 JSON 报告（含 p50/p99/p999、qps、rpm、连接数）；</li>
 *   <li><b>Push 周期输出</b>：守护线程每 {@code benchmarkIntervalSec} 秒打一条 {@code BENCHMARK {...}} 日志，
 *       供脚本 tail/grep 直接取数。</li>
 * </ul>
 *
 * <p><b>默认关闭</b>（不挂载、不生效，热路径零开销）。开启方式：</p>
 * <ul>
 *   <li>{@code -Dserver.benchmark.enabled=true} — 启动时自动挂载；</li>
 *   <li>或 {@code server.addFilter(new BenchmarkReportServerFilter(metrics, setting.getManagement()))} 手动注册；
 *       也可经 {@code ServiceProvider.of(ServerFilter.class)} SPI 发现注册。</li>
 * </ul>
 *
 * <p>分位数使用内置对数桶滑窗直方图（{@value #BUCKET_COUNT} 桶、相邻桶上界 ×{@value #BUCKET_STEP_RATIO}），
 * 无第三方依赖；滑窗 = 当前分片 + 上一分片（各 {@value #WINDOW_MILLIS}ms），提供约 2 × 分片时长的近窗统计。
 * 请求线程只做一次 {@code nanoTime} + 桶自增（实测每请求约 50~100ns），JSON 渲染与周期输出全部在
 * 独立守护线程完成，不拖慢请求路径。</p>
 *
 * @author CH
 * @since 4.0.0.55
 */
@Slf4j
public class BenchmarkReportServerFilter implements ServerFilter, ReactiveServerFilter {

    /**
     * 默认基准报告端点路径（精确匹配）
     */
    static final String DEFAULT_REPORT_PATH = "/benchmark-report";

    /**
     * 报告 JSON 的 Content-Type
     */
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    /**
     * HTTP 200 状态码
     */
    private static final int STATUS_OK = 200;

    /**
     * 分位桶数量：1µs × 1.02^i，i ∈ [0, 1023]，末桶上界约 5.9e8 µs（≈9.8 分钟）
     */
    private static final int BUCKET_COUNT = 1024;

    /**
     * 相邻桶上界倍率：分位误差约 ±2%，足够区分 p50/p99/p999 的工程判断
     */
    private static final double BUCKET_STEP_RATIO = 1.02;

    /**
     * {@link #BUCKET_STEP_RATIO} 的自然对数（预计算，避免每请求 Math.log 常量）
     */
    private static final double LOG_BUCKET_STEP = Math.log(BUCKET_STEP_RATIO);

    /**
     * 滑窗分片长度（毫秒）：报告合并「当前分片 + 上一分片」≈ 2 × 分片时长的近窗统计
     */
    private static final long WINDOW_MILLIS = 10_000;

    /**
     * 周期输出默认间隔（秒），{@code <=0} 关闭周期输出（仅保留 Pull 端点）
     */
    private static final int DEFAULT_INTERVAL_SEC = 10;

    /**
     * 共享的「已完成」信号：端点短路时直接返回，避免每请求分配
     */
    private static final CompletableFuture<Void> COMPLETED = CompletableFuture.completedFuture(null);

    /**
     * 服务器运行指标（连接数、错误数、累计请求数等快照数据源）
     */
    private final ServerMetrics metrics;

    /**
     * 管理端点配置引用（实时读取开关、路径与周期，与 MetricsEndpointServerFilter 同模式）
     */
    private final ServerSetting.ManagementConfig config;

    /**
     * 当前滑窗分片（原子引用：请求线程只读引用并自增，滚动由 {@link #rollIfNeeded(long)} 负责）
     */
    private final AtomicReference<Slice> currentSlice =
            new AtomicReference<>(new Slice(System.currentTimeMillis()));

    /**
     * 上一滑窗分片（滚动后由当前分片晋升而来，报告时与当前分片合并）
     */
    private volatile Slice previousSlice;

    /**
     * 周期输出调度器（守护线程，{@code init} 时惰性启动）
     */
    private volatile ScheduledExecutorService scheduler;

    /**
     * 创建基准报告过滤器。
     *
     * @param metrics 服务器运行指标，不允许为 null
     * @param config  管理端点配置，可为 null（视为默认开启、默认路径与间隔）
     */
    public BenchmarkReportServerFilter(ServerMetrics metrics, ServerSetting.ManagementConfig config) {
        this.metrics = metrics;
        this.config = config;
    }

    /**
     * 获取Order:紧随指标端点、先于路由执行；端点请求短路整条链，业务请求先计时再放行
     */
    @Override
    public int getOrder() {
        return Integer.MIN_VALUE + 25;
    }

    /**
     * SupportPath:Access Filter,每次请求都触发(显式覆写消除双接口默认方法冲突)
     */
    @Override
    public String supportPath() {
        return null;
    }

    /**
     * SupportProtocols
     */
    @Override
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[0];
    }

    /**
     * 初始化：读取周期间隔并启动守护线程（仅当间隔大于 0）。
     *
     * @param config 过滤器初始化配置
     */
    @Override
    public void init(ServerFilterConfig config) throws Exception {
        int intervalSec = DEFAULT_INTERVAL_SEC;
        if (config != null) {
            String param = config.getInitParameter("benchmarkIntervalSec");
            if (param != null && !param.isBlank()) {
                intervalSec = Integer.parseInt(param.trim());
            }
        }
        ensureScheduler(intervalSec);
    }

    /**
     * 销毁：关闭周期输出调度器。
     */
    @Override
    public void destroy() {
        ScheduledExecutorService scheduler = this.scheduler;
        if (scheduler != null) {
            scheduler.shutdownNow();
            this.scheduler = null;
        }
    }

    /**
     * Do过滤
     *
     * @param request request
     * @param response response
     * @param chain chain
     */
    @Override
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        if (isReportRequest(request)) {
            respondReport(response);
            return;
        }
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            record(start);
        }
    }

    /**
     * 响应式Do过滤：包住下游 stage，在完成回调里计时，保持全程非阻塞。
     *
     * @param request request
     * @param response response
     * @param chain chain
     */
    @Override
    public CompletionStage<Void> doFilter(ServerRequest request, ServerResponse response,
                                          ReactiveFilterChain chain) {
        if (isReportRequest(request)) {
            respondReport(response);
            return COMPLETED;
        }
        long start = System.nanoTime();
        CompletionStage<Void> stage = chain.doFilter(request, response);
        if (stage == null) {
            record(start);
            return COMPLETED;
        }
        return stage.whenComplete((v, t) -> record(start));
    }

    /**
     * 判断是否命中基准报告端点：开关开启 且 路径匹配 且 方法为 GET。
     *
     * @param request 请求对象
     * @return true 表示本过滤器应短路响应该请求
     */
    private boolean isReportRequest(ServerRequest request) {
        if (request == null || config == null) {
            return config != null;
        }
        if (!config.isBenchmarkEnabled()) {
            return false;
        }
        String path = config.getBenchmarkPath();
        if (path == null || path.isBlank()) {
            path = DEFAULT_REPORT_PATH;
        }
        return HttpMethod.GET == request.getMethod() && path.equals(request.getPath());
    }

    /**
     * 输出基准报告 JSON 快照。
     *
     * @param response 响应对象
     */
    private void respondReport(ServerResponse response) {
        if (response == null || response.isEnded()) {
            return;
        }
        response.setStatus(STATUS_OK);
        response.setContentType(JSON_CONTENT_TYPE);
        response.setBody(renderReportJson());
        response.end();
    }

    /**
     * 记录一次请求端到端耗时（请求线程热路径：一次 nanoTime + 桶自增）。
     *
     * @param startNanos 请求开始时的 {@code System.nanoTime()} 读数
     */
    private void record(long startNanos) {
        long nowNanos = System.nanoTime();
        long micros = Math.max(nowNanos - startNanos, 0) / 1000;
        Slice slice = currentSlice.get();
        slice.buckets[bucketIndex(micros)].increment();
        slice.total.increment();
        if (nowNanos / 1_000_000 - slice.startMillis >= WINDOW_MILLIS) {
            rollIfNeeded(System.currentTimeMillis());
        }
    }

    /**
     * 滚动滑窗分片：仅当跨分片边界时（约每 {@link #WINDOW_MILLIS}ms 一次）进入同步临界区，
     * 把当前分片晋升为上一分片并新建当前分片。
     *
     * @param nowMillis 当前时间戳（毫秒）
     */
    private synchronized void rollIfNeeded(long nowMillis) {
        Slice cur = currentSlice.get();
        if (nowMillis - cur.startMillis >= WINDOW_MILLIS) {
            previousSlice = cur;
            currentSlice.set(new Slice(nowMillis));
        }
    }

    /**
     * 计算耗时对应的桶下标：1µs × 1.02^i 的对数区间，超界 clamp 到末桶。
     *
     * @param micros 耗时（微秒）
     * @return 桶下标
     */
    private static int bucketIndex(long micros) {
        if (micros <= 1) {
            return 0;
        }
        double idx = Math.log(micros) / LOG_BUCKET_STEP;
        if (idx >= BUCKET_COUNT - 1) {
            return BUCKET_COUNT - 1;
        }
        return (int) idx;
    }

    /**
     * 取报告快照：合并「上一分片 + 当前分片」的桶计数与窗口时间范围。
     *
     * @return 快照（桶计数、请求总数、窗口起止毫秒）
     */
    private ReportSnapshot snapshot() {
        long[] buckets = new long[BUCKET_COUNT];
        long total = 0;
        long nowMillis = System.currentTimeMillis();
        long windowStart = nowMillis;
        long windowEnd = nowMillis;
        Slice previous = previousSlice;
        if (previous != null) {
            for (int i = 0; i < BUCKET_COUNT; i++) {
                buckets[i] = previous.buckets[i].sum();
            }
            total = previous.total.sum();
            windowStart = Math.min(windowStart, previous.startMillis);
        }
        Slice current = currentSlice.get();
        if (current != null) {
            for (int i = 0; i < BUCKET_COUNT; i++) {
                buckets[i] += current.buckets[i].sum();
            }
            total += current.total.sum();
            windowStart = Math.min(windowStart, current.startMillis);
        }
        return new ReportSnapshot(buckets, total, windowStart, windowEnd);
    }

    /**
     * 渲染 JSON 报告文本。
     *
     * @return JSON 字符串
     */
    private String renderReportJson() {
        ReportSnapshot snap = snapshot();
        StringBuilder sb = new StringBuilder(512);
        double windowSec = Math.max((snap.windowEnd - snap.windowStart) / 1000.0d, 0.001d);
        long requests = snap.total;
        double qps = requests / windowSec;
        sb.append('{');
        appendField(sb, "window_seconds", format(windowSec, 1));
        appendField(sb, "requests", requests);
        appendField(sb, "qps", format(qps, 1));
        appendField(sb, "rpm", format(qps * 60.0d, 1));
        appendField(sb, "p50_ms", format(quantileMillis(snap.buckets, requests, 0.50d), 3));
        appendField(sb, "p90_ms", format(quantileMillis(snap.buckets, requests, 0.90d), 3));
        appendField(sb, "p99_ms", format(quantileMillis(snap.buckets, requests, 0.99d), 3));
        appendField(sb, "p999_ms", format(quantileMillis(snap.buckets, requests, 0.999d), 3));
        appendField(sb, "max_ms", metrics != null ? format(metrics.getMaxLatencyMillis(), 3) : 0);
        appendField(sb, "active_connections", metrics != null ? metrics.getActiveConnections() : 0);
        appendField(sb, "peak_connections", metrics != null ? metrics.getPeakConnections() : 0);
        appendField(sb, "total_connections", metrics != null ? metrics.getTotalConnections() : 0);
        appendField(sb, "total_requests", metrics != null ? metrics.getTotalRequests() : requests);
        appendField(sb, "errors", metrics != null ? metrics.getErrorCount() : 0);
        appendLastField(sb, "uptime_seconds", format(metrics != null ? metrics.getUptime() / 1000.0d : 0.0d, 1));
        return sb.append('}').toString();
    }

    /**
     * 计算分位延迟（毫秒）：桶内点取该桶上界（保守估计，表示「该分位延迟不高于此值」）。
     *
     * @param buckets 合并后的桶计数
     * @param total   请求总数
     * @param quantile 分位（0~1）
     * @return 分位延迟（毫秒）
     */
    private static double quantileMillis(long[] buckets, long total, double quantile) {
        if (total <= 0) {
            return 0;
        }
        long rank = Math.max(1, (long) Math.ceil(quantile * total));
        long acc = 0;
        for (int i = 0; i < buckets.length; i++) {
            acc += buckets[i];
            if (acc >= rank) {
                double upperMicros = Math.pow(BUCKET_STEP_RATIO, i + 1);
                return upperMicros / 1000.0d;
            }
        }
        return Math.pow(BUCKET_STEP_RATIO, BUCKET_COUNT) / 1000.0d;
    }

    /**
     * 周期输出：每间隔打一条 {@code BENCHMARK {...}} 日志；开关关闭或异常时静默。
     */
    private void periodicReport() {
        try {
            if (config == null || config.isBenchmarkEnabled()) {
                log.info("BENCHMARK {}", renderReportJson());
            }
        } catch (Throwable ignored) {
            // 报告失败与请求无关，忽略并继续。
        }
    }

    /**
     * 惰性启动周期输出调度器（守护线程、固定延迟）。
     *
     * @param intervalSec 间隔（秒），{@code <=0} 不启动
     */
    private synchronized void ensureScheduler(int intervalSec) {
        if (intervalSec <= 0 || scheduler != null) {
            return;
        }
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "server-benchmark-report");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::periodicReport, intervalSec, intervalSec, TimeUnit.SECONDS);
        this.scheduler = executor;
    }

    /**
     * 追加 JSON 字段（数值）。
     *
     * @param sb    输出缓冲
     * @param name  字段名
     * @param value 数值（写入原始字面量）
     */
    private static void appendField(StringBuilder sb, String name, long value) {
        sb.append('"').append(name).append("\":").append(value).append(',');
    }

    /**
     * 追加 JSON 字段（格式化浮点）。
     *
     * @param sb    输出缓冲
     * @param name  字段名
     * @param value 格式化后的数值文本
     */
    private static void appendField(StringBuilder sb, String name, String value) {
        sb.append('"').append(name).append("\":").append(value).append(',');
    }

    /**
     * 追加最后一个 JSON 字段（无尾逗号）。
     *
     * @param sb    输出缓冲
     * @param name  字段名
     * @param value 格式化后的数值文本
     */
    private static void appendLastField(StringBuilder sb, String name, String value) {
        sb.append('"').append(name).append("\":").append(value);
    }

    /**
     * 格式化浮点为定点文本（{@code Locale.ROOT}，避免逗号千分位破坏 JSON）。
     *
     * @param value 数值
     * @param scale 小数位
     * @return 格式化文本
     */
    private static String format(double value, int scale) {
        return String.format(Locale.ROOT, "%." + scale + "f", value);
    }

    /**
     * 报告快照：合并后的桶计数、请求总数与窗口时间范围。
     */
    private static final class ReportSnapshot {

        /**
         * 合并后的桶计数
         */
        private final long[] buckets;

        /**
         * 请求总数
         */
        private final long total;

        /**
         * 窗口起始时间戳（毫秒）
         */
        private final long windowStart;

        /**
         * 窗口结束时间戳（毫秒）
         */
        private final long windowEnd;

        /**
         * 构造快照
         *
         * @param buckets 桶计数
         * @param total 请求总数
         * @param windowStart 窗口起始（毫秒）
         * @param windowEnd 窗口结束（毫秒）
         */
        ReportSnapshot(long[] buckets, long total, long windowStart, long windowEnd) {
            this.buckets = buckets;
            this.total = total;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
        }
    }

    /**
     * 滑窗分片：一个固定时间片的延迟直方图（桶为 {@link LongAdder}，请求线程无锁自增）。
     */
    private static final class Slice {

        /**
         * 分片起始时间戳（毫秒）
         */
        private final long startMillis;

        /**
         * 各延迟桶计数
         */
        private final LongAdder[] buckets;

        /**
         * 分片内请求总数
         */
        private final LongAdder total;

        /**
         * 构造分片
         *
         * @param startMillis 分片起始时间戳（毫秒）
         */
        Slice(long startMillis) {
            this.startMillis = startMillis;
            this.buckets = new LongAdder[BUCKET_COUNT];
            for (int i = 0; i < BUCKET_COUNT; i++) {
                this.buckets[i] = new LongAdder();
            }
            this.total = new LongAdder();
        }
    }
}