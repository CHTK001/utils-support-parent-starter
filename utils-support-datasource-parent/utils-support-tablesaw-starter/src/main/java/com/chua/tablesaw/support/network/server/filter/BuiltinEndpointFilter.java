package com.chua.tablesaw.support.network.server.filter;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerMetrics;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.utils.BeanUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
* 内置端点过滤器。
*
* <p>拦截 /health 和 /metrics 请求路径，返回 JSON 格式的服务器运行时信息。
* 仅支持 HTTP 协议，非 HTTP 协议自动跳过。</p>
*
* <p>/health 返回：status、uptime、activeRequests、timestamp</p>
* <p>/metrics 返回：totalRequests、activeRequests、errorCount、uptime、startTime、lastErrorTime</p>
*
* @author CH
* @since 4.0.0.42
 */
public class BuiltinEndpointFilter implements com.chua.common.support.network.server.filter.ServerFilter {

    /**
    * 服务器指标收集器，用于获取运行时计数。
    */
    private final ServerMetrics metrics;

    /**
    * 健康检查端点路径。
    */
    private final String healthPath;

    /**
    * 指标查询端点路径。
    */
    private final String metricsPath;

    /**
    * 延迟直方图图表端点路径。
    */
    private final String latencyHistogramPath;

    /**
    * 构造内置端点过滤器，使用默认端点路径。
    *
    * @param metrics 服务器指标收集器
    */
    public BuiltinEndpointFilter(ServerMetrics metrics) {
        this(metrics, "/health", "/metrics", "/metrics/latency-histogram.svg");
    }

    /**
    * 构造内置端点过滤器，自定义端点路径。
    *
    * @param metrics     服务器指标收集器
    * @param healthPath  健康检查端点路径
    * @param metricsPath 指标查询端点路径
    */
    public BuiltinEndpointFilter(ServerMetrics metrics, String healthPath, String metricsPath) {
        this(metrics, healthPath, metricsPath, "/metrics/latency-histogram.svg");
    }

    /**
    * 构造内置端点过滤器，自定义全部端点路径。
    *
    * @param metrics              服务器指标收集器
    * @param healthPath           健康检查端点路径
    * @param metricsPath          指标查询端点路径
    * @param latencyHistogramPath 延迟直方图端点路径
    */
    public BuiltinEndpointFilter(ServerMetrics metrics, String healthPath, String metricsPath,
                                 String latencyHistogramPath) {
        this.metrics = metrics;
        this.healthPath = healthPath;
        this.metricsPath = metricsPath;
        this.latencyHistogramPath = latencyHistogramPath;
    }

    @Override
    /** 获取订单 */
    public int getOrder() {
        return Integer.MIN_VALUE + 100;
    }

    @Override
    /** 支持协议 */
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.HTTP};
    }

    @Override
    /** 执行过滤 */
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        String path = request.getPath();
        if (path == null) {
            chain.doFilter(request, response);
            return;
        }

        if (healthPath.equals(path)) {
            handleHealth(response);
            return;
        }

        if (metricsPath.equals(path)) {
            handleMetrics(response);
            return;
        }

        if (latencyHistogramPath.equals(path)) {
            handleLatencyHistogram(response);
            return;
        }

        chain.doFilter(request, response);
    }

    /**
    * 处理健康检查请求，返回服务器运行状态。
    *
    * @param response 响应对象
    */
    private void handleHealth(com.chua.common.support.network.server.response.ServerResponse response) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(6);
        BeanUtils.copyProperties(metrics, body);
        body.put("status", "UP");
        body.put("timestamp", System.currentTimeMillis());
        String json = Json.toJson(body);
        response.setStatus(200);
        response.setContentType("application/json; charset=utf-8");
        response.setBody(json);
        response.end();
    }

    /**
    * 处理指标查询请求，返回运行时计数器。
    *
    * @param response 响应对象
    */
    private void handleMetrics(com.chua.common.support.network.server.response.ServerResponse response) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(12);
        BeanUtils.copyProperties(metrics, body);
        long lastErrorTime = metrics.getLastErrorTime();
        if (lastErrorTime > 0) {
            body.put("lastErrorTime", lastErrorTime);
        }
        String json = Json.toJson(body);
        response.setStatus(200);
        response.setContentType("application/json; charset=utf-8");
        response.setBody(json);
        response.end();
    }

    /**
    * 处理延迟直方图请求，按需生成 SVG 图像。
    *
    * <p>实时请求只更新 ServerMetrics 中的内存计数器；直方图数据与 SVG
    * 均在访问本端点时临时创建，不会进入请求处理热路径。</p>
    *
    * @param response 响应对象
    */
    private void handleLatencyHistogram(com.chua.common.support.network.server.response.ServerResponse response) {
        Map<String, Long> histogram = metrics.getLatencyHistogram();
        List<Map.Entry<String, Long>> entries = new ArrayList<>(histogram.entrySet());

        long max = 1;
        for (Map.Entry<String, Long> entry : entries) {
            max = Math.max(max, entry.getValue());
        }

        int width = 860;
        int height = 360;
        int chartLeft = 72;
        int chartBottom = 300;
        int chartWidth = 748;
        int chartHeight = 240;
        int barWidth = Math.max(24, chartWidth / Math.max(1, entries.size() * 2));
        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width)
                .append("\" height=\"").append(height).append("\" viewBox=\"0 0 ")
                .append(width).append(' ').append(height).append("\">")
                .append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>")
                .append("<text x=\"72\" y=\"34\" font-family=\"Arial,sans-serif\" font-size=\"20\"")
                .append(" fill=\"#1f2937\">Request latency histogram</text>")
                .append("<line x1=\"").append(chartLeft).append("\" y1=\"").append(chartBottom)
                .append("\" x2=\"").append(chartLeft + chartWidth).append("\" y2=\"")
                .append(chartBottom).append("\" stroke=\"#64748b\"/>")
                .append("<line x1=\"").append(chartLeft).append("\" y1=\"")
                .append(chartBottom - chartHeight).append("\" x2=\"").append(chartLeft)
                .append("\" y2=\"").append(chartBottom).append("\" stroke=\"#64748b\"/>");

        for (int row = 0; row < entries.size(); row++) {
            Map.Entry<String, Long> entry = entries.get(row);
            long count = entry.getValue();
            int barHeight = (int) (count * chartHeight / max);
            int center = chartLeft + chartWidth * (row * 2 + 1) / (entries.size() * 2);
            int x = center - barWidth / 2;
            int y = chartBottom - barHeight;
            String bucket = entry.getKey();
            svg.append("<rect x=\"").append(x).append("\" y=\"").append(y)
                    .append("\" width=\"").append(barWidth).append("\" height=\"").append(barHeight)
                    .append("\" rx=\"3\" fill=\"#2563eb\"/>")
                    .append("<text x=\"").append(center).append("\" y=\"").append(y - 6)
                    .append("\" text-anchor=\"middle\" font-family=\"Arial,sans-serif\" font-size=\"12\"")
                    .append(" fill=\"#334155\">").append(count).append("</text>")
                    .append("<text x=\"").append(center).append("\" y=\"").append(chartBottom + 22)
                    .append("\" text-anchor=\"middle\" font-family=\"Arial,sans-serif\" font-size=\"11\"")
                    .append(" fill=\"#475569\">").append(bucket).append("</text>");
        }
        svg.append("<text x=\"16\" y=\"72\" font-family=\"Arial,sans-serif\" font-size=\"12\"")
                .append(" fill=\"#475569\">requests</text>")
                .append("<text x=\"").append(chartLeft + chartWidth - 120).append("\" y=\"")
                .append(height - 14).append("\" font-family=\"Arial,sans-serif\" font-size=\"12\"")
                .append(" fill=\"#475569\">latency bucket</text></svg>");
        response.setStatus(200);
        response.setContentType("image/svg+xml; charset=utf-8");
        response.setBody(svg.toString());
        response.end();
    }
}
