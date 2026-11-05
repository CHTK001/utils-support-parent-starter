// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.opentelemetry.support.engine;

import com.chua.common.support.apm.OtnlEngine;
import com.chua.common.support.apm.OtnlMetricQuery;
import com.chua.common.support.apm.OtnlQuery;
import com.chua.common.support.apm.OtnlScope;
import com.chua.common.support.apm.OtnlTag;
import com.chua.common.support.apm.model.OtnlAlarm;
import com.chua.common.support.apm.model.OtnlEndpoint;
import com.chua.common.support.apm.model.OtnlHealth;
import com.chua.common.support.apm.model.OtnlInstance;
import com.chua.common.support.apm.model.OtnlLogRecord;
import com.chua.common.support.apm.model.OtnlMetricSeries;
import com.chua.common.support.apm.model.OtnlService;
import com.chua.common.support.apm.model.OtnlSpan;
import com.chua.common.support.apm.model.OtnlStatus;
import com.chua.common.support.apm.model.OtnlTopology;
import com.chua.common.support.apm.model.OtnlTrace;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.opentelemetry.support.client.OpenTelemetryClient;
import com.chua.opentelemetry.support.client.OpenTelemetryClientHolder;
import com.chua.runtime.protocol.Span;
import com.chua.runtime.protocol.StatusCode;
import com.chua.runtime.protocol.Trace;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenTelemetry 的 {@link OtnlEngine} 实现（SPI 名 {@code opentelemetry}）。
 * <p>
 * OpenTelemetry 是「进程内 SDK」而非「远端后端」：数据来自应用启动时装配的
 * {@link OpenTelemetryClient}（默认用 {@code InMemorySpanExporter} 缓存 Span）。
 * 因此本实现的能力边界就是「SDK 里能看到的 Span」：
 * </p>
 * <ul>
 *   <li>支持：服务、实例、端点、链路（均由 Span 反推）；</li>
 *   <li>不支持：指标时序、拓扑、告警、日志 —— 这些需要 Collector/后端，SDK 侧没有；</li>
 *   <li>未装配客户端（{@code opentelemetry.enabled=false} 或模块未引入）时，
 *       {@link #status()} 明确报告不可用，前端据此降级。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Slf4j
@Spi("opentelemetry")
public class OpenTelemetryOtnlEngine implements OtnlEngine {

    /**
     * 未知值占位
     */
    private static final long UNKNOWN_LONG = -1L;

    @Override
    public String name() {
        return "opentelemetry";
    }

    @Override
    public List<String> capabilities() {
        return List.of(CAP_SERVICES, CAP_INSTANCES, CAP_ENDPOINTS, CAP_TRACES);
    }

    @Override
    public OtnlStatus status() {
        OpenTelemetryClient client = OpenTelemetryClientHolder.get();
        if (client == null) {
            return OtnlStatus.unavailable(name(),
                    "未装配 OpenTelemetry 客户端（opentelemetry.enabled=false 或模块未引入）");
        }
        if (client.isClosed()) {
            return OtnlStatus.unavailable(name(), "OpenTelemetry 客户端已关闭");
        }
        return OtnlStatus.available(name(), version(), "in-memory", "agent", capabilities());
    }

    @Override
    public List<OtnlService> services(OtnlQuery query) {
        List<Span> spans = spans();
        if (spans.isEmpty()) {
            return List.of();
        }
        Window window = Window.of(query);
        List<Span> filtered = filter(spans, window);
        if (filtered.isEmpty()) {
            return List.of();
        }
        String serviceName = serviceName();
        Statistics statistics = Statistics.of(filtered);
        return List.of(new OtnlService(null, serviceName, null, List.of("GENERAL"),
                1, statistics.endpoints, statistics.calls,
                statistics.avgMs(), statistics.p95(), statistics.p99(), statistics.errorRate(),
                List.of(OtnlTag.of("source", "opentelemetry-sdk"))));
    }

    @Override
    public List<OtnlInstance> instances(String serviceName, OtnlQuery query) {
        if (OpenTelemetryClientHolder.get() == null) {
            return List.of();
        }
        String name = serviceName();
        List<OtnlTag> attributes = new ArrayList<>();
        attributes.add(OtnlTag.of("host", hostName()));
        attributes.add(OtnlTag.of("pid", String.valueOf(ProcessHandle.current().pid())));
        return List.of(new OtnlInstance(name, name, name, "JAVA", System.currentTimeMillis(), attributes));
    }

    @Override
    public List<OtnlEndpoint> endpoints(String serviceName, OtnlQuery query) {
        Window window = Window.of(query);
        Map<String, Statistics> groups = new LinkedHashMap<>();
        String keyword = query == null || query.keyword() == null ? "" : query.keyword().toLowerCase();
        for (Span span : filter(spans(), window)) {
            String name = spanEndpoint(span);
            if (!keyword.isEmpty() && !name.toLowerCase().contains(keyword)) {
                continue;
            }
            groups.computeIfAbsent(name, key -> new Statistics()).accept(span);
        }
        List<OtnlEndpoint> result = new ArrayList<>(groups.size());
        groups.forEach((name, statistics) -> result.add(new OtnlEndpoint(null, serviceName(), name,
                statistics.calls, statistics.avgMs(), statistics.p95(), statistics.p99(),
                statistics.errorRate(), List.of())));
        return result;
    }

    @Override
    public List<OtnlTrace> traces(OtnlQuery query) {
        Window window = Window.of(query);
        List<Trace> traces = new ArrayList<>();
        OpenTelemetryClient client = OpenTelemetryClientHolder.get();
        if (client == null) {
            return List.of();
        }
        try {
            traces.addAll(client.traces());
        } catch (Exception e) {
            log.debug("[OpenTelemetry] 读取链路失败: {}", e.getMessage());
            return List.of();
        }
        String serviceFilter = query == null ? null : query.serviceName();
        String endpointFilter = query == null ? null : query.endpointName();
        boolean onlyError = query != null && query.stateOrAll() == com.chua.common.support.apm.OtnlTraceState.ERROR;
        List<OtnlTrace> result = new ArrayList<>();
        for (Trace trace : traces) {
            if (trace.getStartTime() > 0 && (trace.getStartTime() < window.start || trace.getStartTime() > window.end)) {
                continue;
            }
            Span root = trace.getRoot();
            String endpoint = root == null ? null : spanEndpoint(root);
            boolean error = trace.getSpans().stream().anyMatch(OpenTelemetryOtnlEngine::isError);
            if (onlyError && !error) {
                continue;
            }
            if (endpointFilter != null && !endpointFilter.isBlank() && !endpointFilter.equals(endpoint)) {
                continue;
            }
            if (serviceFilter != null && !serviceFilter.isBlank() && !serviceFilter.equals(serviceName())) {
                continue;
            }
            long start = trace.getStartTime() > 0 ? trace.getStartTime()
                    : trace.getSpans().stream().mapToLong(Span::getStartTime).min().orElse(0L);
            long end = trace.getEndTime() > 0 ? trace.getEndTime()
                    : trace.getSpans().stream().mapToLong(Span::getEndTime).max().orElse(start);
            result.add(OtnlTrace.summary(trace.getTraceId(), serviceName(), endpoint,
                    Math.max(0L, end - start), start, end, error, trace.getSpans().size()));
        }
        Comparator<OtnlTrace> comparator = query != null && query.orderByDuration()
                ? Comparator.comparingLong(OtnlTrace::durationMs).reversed()
                : Comparator.comparingLong(OtnlTrace::startTime).reversed();
        result.sort(comparator);
        return page(result, query);
    }

    @Override
    public OtnlTrace trace(String traceId) {
        OpenTelemetryClient client = OpenTelemetryClientHolder.get();
        if (client == null || traceId == null || traceId.isBlank()) {
            return null;
        }
        List<Span> spans;
        try {
            spans = client.spansByTraceId(traceId);
        } catch (Exception e) {
            return null;
        }
        if (spans == null || spans.isEmpty()) {
            return null;
        }
        List<OtnlSpan> converted = new ArrayList<>(spans.size());
        long start = Long.MAX_VALUE;
        long end = 0L;
        boolean error = false;
        String rootEndpoint = null;
        for (Span span : spans) {
            boolean spanError = isError(span);
            error = error || spanError;
            start = Math.min(start, span.getStartTime());
            end = Math.max(end, span.getEndTime());
            if (isRoot(span)) {
                rootEndpoint = spanEndpoint(span);
            }
            List<OtnlTag> tags = new ArrayList<>();
            if (span.getClassName() != null) {
                tags.add(OtnlTag.of("class", span.getClassName()));
            }
            if (span.getMethodName() != null) {
                tags.add(OtnlTag.of("method", span.getMethodName()));
            }
            if (span.getErrorType() != null) {
                tags.add(OtnlTag.of("error.type", span.getErrorType()));
            }
            List<OtnlSpan.OtnlSpanLog> logs = new ArrayList<>();
            if (span.getErrorMessage() != null && !span.getErrorMessage().isBlank()) {
                logs.add(new OtnlSpan.OtnlSpanLog(span.getEndTime(),
                        List.of(OtnlTag.of("event", "error"), OtnlTag.of("message", span.getErrorMessage()))));
            }
            converted.add(new OtnlSpan(traceId, null, span.getSpanId(), span.getParentSpanId(),
                    serviceName(), serviceName(), spanEndpoint(span), "Unknown",
                    isRoot(span) ? "Entry" : "Local", null, null,
                    span.getStartTime(), span.getEndTime(),
                    Math.max(0L, span.getEndTime() - span.getStartTime()), spanError, tags, logs));
        }
        if (start == Long.MAX_VALUE) {
            start = 0L;
        }
        converted.sort(Comparator.comparingLong(OtnlSpan::startTime));
        return new OtnlTrace(traceId, serviceName(), rootEndpoint, Math.max(0L, end - start),
                start, end, error, converted.size(), converted);
    }

    @Override
    public OtnlTopology topology(OtnlQuery query) {
        // OpenTelemetry SDK 侧没有拓扑数据，明确不支持
        return OtnlTopology.empty();
    }

    @Override
    public OtnlMetricSeries metrics(OtnlMetricQuery query) {
        // 指标由 Collector/后端提供，SDK 侧没有时序存储
        return OtnlMetricSeries.empty(query == null ? null : query.name(),
                query == null ? OtnlScope.SERVICE : query.scopeOrService(),
                query == null ? null : query.serviceName());
    }

    @Override
    public List<OtnlAlarm> alarms(OtnlQuery query) {
        return List.of();
    }

    @Override
    public List<OtnlLogRecord> logs(OtnlQuery query) {
        return List.of();
    }

    @Override
    public OtnlHealth health(OtnlQuery query) {
        List<Span> spans = filter(spans(), Window.of(query));
        if (spans.isEmpty()) {
            return OtnlHealth.empty("OpenTelemetry SDK 内存中暂无 Span");
        }
        Statistics statistics = Statistics.of(spans);
        return new OtnlHealth(statistics.errorRate() > 1D ? "DEGRADED" : "HEALTHY",
                0D, statistics.errorRate(), statistics.avgMs(), statistics.p99(),
                1, statistics.endpoints, 0, "数据来源：OpenTelemetry SDK 内存 Span");
    }

    @Override
    public void close() {
        // 客户端由 Spring 容器管理，引擎不持有资源
    }

    /**
     * 当前 SDK 客户端里的全部 Span
     *
     * @return Span 列表
     */
    private List<Span> spans() {
        OpenTelemetryClient client = OpenTelemetryClientHolder.get();
        if (client == null || client.isClosed()) {
            return List.of();
        }
        try {
            List<Span> spans = client.spans();
            return spans == null ? List.of() : spans;
        } catch (Exception e) {
            log.debug("[OpenTelemetry] 读取 Span 失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 服务名（SDK 侧一个客户端就是一个服务）
     *
     * @return 服务名
     */
    private String serviceName() {
        OpenTelemetryClient client = OpenTelemetryClientHolder.get();
        if (client == null || client.getServiceName() == null || client.getServiceName().isBlank()) {
            return "unknown-service";
        }
        return client.getServiceName();
    }

    /**
     * SDK 版本
     *
     * @return 版本，未知返回 unknown
     */
    private String version() {
        try {
            Package pkg = Class.forName("io.opentelemetry.sdk.OpenTelemetrySdk").getPackage();
            String version = pkg == null ? null : pkg.getImplementationVersion();
            return version == null || version.isBlank() ? "unknown" : version;
        } catch (Throwable ignore) {
            return "unknown";
        }
    }

    /**
     * 主机名
     *
     * @return 主机名
     */
    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "localhost";
        }
    }

    /**
     * Span 名（作为端点名）
     *
     * @param span Span
     * @return 端点名
     */
    private static String spanEndpoint(Span span) {
        if (span.getName() != null && !span.getName().isBlank()) {
            return span.getName();
        }
        String className = span.getClassName() == null ? "" : span.getClassName();
        String methodName = span.getMethodName() == null ? "" : span.getMethodName();
        return (className + "#" + methodName).trim();
    }

    /**
     * 是否是错误 Span
     *
     * @param span Span
     * @return 是否错误
     */
    private static boolean isError(Span span) {
        return span.getStatus() == StatusCode.ERROR;
    }

    /**
     * 是否是根 Span
     *
     * @param span Span
     * @return 是否根
     */
    private static boolean isRoot(Span span) {
        return span.getParentSpanId() == null || span.getParentSpanId().isBlank();
    }

    /**
     * 按时间窗口过滤
     *
     * @param spans  Span 列表
     * @param window 窗口
     * @return 过滤后的列表
     */
    private static List<Span> filter(List<Span> spans, Window window) {
        List<Span> result = new ArrayList<>(spans.size());
        for (Span span : spans) {
            if (span.getStartTime() >= window.start && span.getStartTime() <= window.end) {
                result.add(span);
            }
        }
        return result;
    }

    /**
     * 分页
     *
     * @param list  列表
     * @param query 查询条件
     * @param <T>   元素类型
     * @return 分页后的列表
     */
    private static <T> List<T> page(List<T> list, OtnlQuery query) {
        if (query == null) {
            return list;
        }
        int pageNum = query.pageNumOrOne();
        int pageSize = query.pageSizeOrTwenty();
        int from = Math.min(list.size(), (pageNum - 1) * pageSize);
        int to = Math.min(list.size(), from + pageSize);
        return list.subList(from, to);
    }

    /**
     * 时间窗口
     */
    private static final class Window {
        private final long start;
        private final long end;

        private Window(long start, long end) {
            this.start = start;
            this.end = end;
        }

        private static Window of(OtnlQuery query) {
            long end = query == null || query.endTime() <= 0 ? System.currentTimeMillis() : query.endTime();
            long start = query == null || query.startTime() <= 0 ? end - 30 * 60_000L : query.startTime();
            return new Window(start, end);
        }
    }

    /**
     * Span 聚合统计
     */
    private static final class Statistics {
        private long calls;
        private long errors;
        private long totalMs;
        private final List<Long> durations = new ArrayList<>();
        private int endpoints;

        private void accept(Span span) {
            calls++;
            if (isError(span)) {
                errors++;
            }
            long duration = Math.max(0L, span.getEndTime() - span.getStartTime());
            totalMs += duration;
            durations.add(duration);
        }

        private static Statistics of(List<Span> spans) {
            Statistics statistics = new Statistics();
            java.util.Set<String> names = new java.util.HashSet<>();
            for (Span span : spans) {
                statistics.accept(span);
                names.add(spanEndpoint(span));
            }
            statistics.endpoints = names.size();
            return statistics;
        }

        private double avgMs() {
            return calls == 0 ? 0D : (double) totalMs / calls;
        }

        private double errorRate() {
            return calls == 0 ? 0D : (double) errors / calls * 100D;
        }

        private double percentile(double percentile) {
            if (durations.isEmpty()) {
                return 0D;
            }
            List<Long> sorted = new ArrayList<>(durations);
            sorted.sort(Comparator.naturalOrder());
            int index = (int) Math.ceil(percentile / 100D * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
        }

        private double p95() {
            return percentile(95);
        }

        private double p99() {
            return percentile(99);
        }
    }
}