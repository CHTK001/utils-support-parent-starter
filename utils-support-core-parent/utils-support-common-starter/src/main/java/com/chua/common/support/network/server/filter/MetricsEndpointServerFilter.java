// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.ServerMetrics;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 内置 /metrics 指标端点过滤器（Prometheus 文本暴露格式）。
 *
 * <p>对指标路径（默认 {@code /metrics}）短路返回 {@link ServerMetrics} 的
 * Prometheus 文本格式快照，不进入路由匹配与业务 handler；其余请求原样放行。
 * 供 Prometheus / VictoriaMetrics 等 Pull 型采集器抓取。</p>
 *
 * <p>端点默认关闭（{@code management.metricsEnabled=false}）：指标会暴露
 * 运行时信息（QPS、延迟分布、连接数等），是否开放应由部署方显式决定。
 * 与 {@code SecurityHeadersServerFilter} 同理：内置过滤器在服务构造期挂载，
 * 而配置可能在构造后才回填，因此本过滤器持有配置引用、每次请求实时读取开关。</p>
 *
 * <p>同时实现同步({@link ServerFilter})与响应式({@link ReactiveServerFilter})
 * 两种链接口：阻塞传输走同步链，NIO/AIO 响应式传输走响应式链。</p>
 *
 * @author CH
 * @since 4.0.0.54
 */
public class MetricsEndpointServerFilter implements ServerFilter, ReactiveServerFilter {

    /**
     * 默认指标端点路径
     */
    private static final String DEFAULT_METRICS_PATH = "/metrics";

    /**
     * Prometheus 文本暴露格式 Content-Type
     */
    private static final String PROMETHEUS_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    /**
     * HTTP 200 状态码
     */
    private static final int STATUS_OK = 200;

    /**
     * 服务器运行指标（快照数据源）
     */
    private final ServerMetrics metrics;

    /**
     * 管理端点配置引用（实时读取开关与路径）
     */
    private final ServerSetting.ManagementConfig config;

    /**
     * 创建指标端点过滤器。
     *
     * @param metrics 服务器运行指标
     * @param config  管理端点配置，可为 null（视为未启用）
     */
    public MetricsEndpointServerFilter(ServerMetrics metrics, ServerSetting.ManagementConfig config) {
        this.metrics = metrics;
        this.config = config;
    }

    /**
     * 获取Order:紧随健康探针、先于路由执行，保证指标请求短路整条链
     */
    @Override
    public int getOrder() {
        return Integer.MIN_VALUE + 20;
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
     * Do过滤
     *
     * @param request request
     * @param response response
     * @param chain chain
     */
    @Override
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        if (isMetricsRequest(request)) {
            respondMetrics(response);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 响应式Do过滤
     *
     * @param request request
     * @param response response
     * @param chain chain
     */
    @Override
    public CompletionStage<Void> doFilter(ServerRequest request, ServerResponse response,
                                          ReactiveFilterChain chain) {
        if (isMetricsRequest(request)) {
            respondMetrics(response);
            return CompletableFuture.completedFuture(null);
        }
        return chain.doFilter(request, response);
    }

    /**
     * 判断是否命中指标端点：开关开启 且 路径匹配 且 方法为 GET。
     *
     * @param request 请求对象
     * @return true 表示本过滤器应短路响应该请求
     */
    private boolean isMetricsRequest(ServerRequest request) {
        if (config == null || !config.isMetricsEnabled() || metrics == null) {
            return false;
        }
        String path = config.getMetricsPath();
        if (path == null || path.isBlank()) {
            path = DEFAULT_METRICS_PATH;
        }
        return HttpMethod.GET == request.getMethod() && path.equals(request.getPath());
    }

    /**
     * 输出 Prometheus 文本格式的指标快照。
     *
     * @param response 响应对象
     */
    private void respondMetrics(ServerResponse response) {
        if (response.isEnded()) {
            return;
        }
        response.setStatus(STATUS_OK);
        response.setContentType(PROMETHEUS_CONTENT_TYPE);
        response.setBody(renderPrometheus());
        response.end();
    }

    /**
     * 渲染 Prometheus 文本暴露格式。
     *
     * @return 指标文本
     */
    private String renderPrometheus() {
        StringBuilder sb = new StringBuilder(1024);

        appendCounter(sb, "server_requests_total", "Total number of HTTP requests processed.",
                metrics.getTotalRequests());
        appendGauge(sb, "server_requests_active", "Currently in-flight requests.",
                metrics.getActiveRequests());
        appendGauge(sb, "server_requests_peak_active", "Peak in-flight requests.",
                metrics.getPeakActive());
        appendCounter(sb, "server_errors_total", "Total number of requests that ended with an error.",
                metrics.getErrorCount());

        appendGauge(sb, "server_connections_active", "Currently open connections.",
                metrics.getActiveConnections());
        appendGauge(sb, "server_connections_peak", "Peak concurrent connections.",
                metrics.getPeakConnections());
        appendCounter(sb, "server_connections_total", "Total number of accepted connections.",
                metrics.getTotalConnections());
        appendCounter(sb, "server_connections_rejected_total",
                "Total number of connections rejected due to the maxConnections limit.",
                metrics.getRejectedConnections());

        appendHistogram(sb);
        appendGauge(sb, "server_request_latency_max_milliseconds",
                "Maximum observed request latency in milliseconds.", metrics.getMaxLatencyMillis());

        appendGauge(sb, "server_uptime_seconds", "Server uptime in seconds.",
                metrics.getUptime() / 1000.0d);
        return sb.toString();
    }

    /**
     * 追加请求延迟直方图（桶边界与 {@link ServerMetrics#getLatencyHistogram()} 对齐）。
     *
     * @param sb 输出缓冲
     */
    private void appendHistogram(StringBuilder sb) {
        Map<String, Long> histogram = metrics.getLatencyHistogram();
        long count = histogram.getOrDefault("le_inf", 0L);
        double sumMillis = metrics.getAverageLatencyMillis() * count;

        sb.append("# HELP server_request_latency_milliseconds Request latency in milliseconds.\n");
        sb.append("# TYPE server_request_latency_milliseconds histogram\n");
        for (Map.Entry<String, Long> entry : histogram.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("le_") && key.endsWith("ms")) {
                String bound = key.substring(3, key.length() - 2);
                sb.append("server_request_latency_milliseconds_bucket{le=\"").append(bound)
                        .append("\"} ").append(entry.getValue()).append('\n');
            }
        }
        sb.append("server_request_latency_milliseconds_bucket{le=\"+Inf\"} ").append(count).append('\n');
        sb.append("server_request_latency_milliseconds_sum ").append(sumMillis).append('\n');
        sb.append("server_request_latency_milliseconds_count ").append(count).append('\n');
    }

    /**
     * 追加一个 Counter 类型指标。
     *
     * @param sb   输出缓冲
     * @param name 指标名
     * @param help 帮助文本
     * @param value 当前值
     */
    private static void appendCounter(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" counter\n");
        sb.append(name).append(' ').append(value).append('\n');
    }

    /**
     * 追加一个 Gauge 类型指标。
     *
     * @param sb   输出缓冲
     * @param name 指标名
     * @param help 帮助文本
     * @param value 当前值
     */
    private static void appendGauge(StringBuilder sb, String name, String help, double value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(' ').append(value).append('\n');
    }
}
