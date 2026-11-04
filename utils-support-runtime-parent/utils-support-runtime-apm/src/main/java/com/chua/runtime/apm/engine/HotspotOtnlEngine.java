package com.chua.runtime.apm.engine;

import com.chua.common.support.apm.OtnlEngine;
import com.chua.common.support.apm.OtnlMetricQuery;
import com.chua.common.support.apm.OtnlQuery;
import com.chua.common.support.apm.OtnlScope;
import com.chua.common.support.apm.OtnlTag;
import com.chua.common.support.apm.OtnlTraceState;
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
import com.chua.runtime.apm.ApmBootstrap;
import com.chua.runtime.apm.handler.TraceHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自研 hotspot agent 的 {@link OtnlEngine} 实现（SPI 名 {@code hotspot}）。
 * <p>
 * 数据来源是进程内的 {@link TraceHandler}（{@code utils-support-runtime-apm} 里的字节码插桩
 * handler），因此：
 * </p>
 * <ul>
 *   <li>不需要任何外部后端，{@code endpoint} 固定为 {@code local}，模式为 {@code agent}；</li>
 *   <li>数据只覆盖「已经跑过的方法调用 Span」（环形缓冲，上限由 handler 控制）；</li>
 *   <li>指标时序 / 拓扑 / 告警 / 日志需要独立的采样与存储组件，本实现明确不支持。</li>
 * </ul>
 * <p>
 * 未挂载 agent 时 {@link #status()} 返回不可用，前端据此显示「本机无 APM」。
 * </p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Slf4j
@Spi("hotspot")
public class HotspotOtnlEngine implements OtnlEngine {

    /**
     * TraceHandler 的错误状态值
     */
    private static final String STATUS_ERROR = "ERROR";

    /**
     * 未知数值占位
     */
    private static final long UNKNOWN_LONG = -1L;

    @Override
    public String name() {
        return "hotspot";
    }

    @Override
    public List<String> capabilities() {
        return List.of(CAP_SERVICES, CAP_INSTANCES, CAP_ENDPOINTS, CAP_TRACES);
    }

    @Override
    public OtnlStatus status() {
        TraceHandler handler = handler();
        if (handler == null) {
            return OtnlStatus.unavailable(name(),
                    "未检测到 hotspot agent（utils-support-runtime-apm 未以 -javaagent 方式挂载）");
        }
        if (!handler.isRunning()) {
            return OtnlStatus.unavailable(name(), "hotspot agent 已挂载但未启动（TraceHandler 未运行）");
        }
        return OtnlStatus.available(name(), handler.version(), "local", "agent", capabilities());
    }

    @Override
    public List<OtnlService> services(OtnlQuery query) {
        List<TraceHandler.Span> spans = spans(query);
        if (spans.isEmpty() && handler() == null) {
            return List.of();
        }
        Statistics statistics = Statistics.of(spans);
        return List.of(new OtnlService(null, serviceName(), null, List.of("GENERAL"),
                1, statistics.endpoints, statistics.calls,
                statistics.avgMs(), statistics.p95(), statistics.p99(), statistics.errorRate(),
                List.of(OtnlTag.of("source", "hotspot-agent"))));
    }

    @Override
    public List<OtnlInstance> instances(String serviceName, OtnlQuery query) {
        if (handler() == null) {
            return List.of();
        }
        String name = serviceName();
        List<OtnlTag> attributes = new ArrayList<>();
        attributes.add(OtnlTag.of("host", hostName()));
        attributes.add(OtnlTag.of("pid", String.valueOf(ProcessHandle.current().pid())));
        attributes.add(OtnlTag.of("agent", "hotspot"));
        return List.of(new OtnlInstance(name, name, name, "JAVA", System.currentTimeMillis(), attributes));
    }

    @Override
    public List<OtnlEndpoint> endpoints(String serviceName, OtnlQuery query) {
        Map<String, Statistics> groups = new LinkedHashMap<>();
        String keyword = query == null || query.keyword() == null ? "" : query.keyword().toLowerCase();
        for (TraceHandler.Span span : spans(query)) {
            String name = endpoint(span);
            if (!keyword.isEmpty() && !name.toLowerCase().contains(keyword)) {
                continue;
            }
            groups.computeIfAbsent(name, key -> new Statistics()).accept(span);
        }
        List<OtnlEndpoint> result = new ArrayList<>(groups.size());
        groups.forEach((name, statistics) -> result.add(new OtnlEndpoint(null, serviceName(), name,
                statistics.calls, statistics.avgMs(), statistics.p95(), statistics.p99(),
                statistics.errorRate(), List.of())));
        result.sort(Comparator.comparingLong(OtnlEndpoint::calls).reversed());
        return result;
    }

    @Override
    public List<OtnlTrace> traces(OtnlQuery query) {
        Map<String, List<TraceHandler.Span>> grouped = new LinkedHashMap<>();
        for (TraceHandler.Span span : spans(query)) {
            if (span.getTraceId() == null || span.getTraceId().isBlank()) {
                continue;
            }
            grouped.computeIfAbsent(span.getTraceId(), key -> new ArrayList<>()).add(span);
        }
        String endpointFilter = query == null ? null : query.endpointName();
        boolean onlyError = query != null && query.stateOrAll() == OtnlTraceState.ERROR;
        List<OtnlTrace> result = new ArrayList<>(grouped.size());
        grouped.forEach((traceId, group) -> {
            TraceHandler.Span root = root(group);
            String endpoint = root == null ? endpoint(group.get(0)) : endpoint(root);
            if (endpointFilter != null && !endpointFilter.isBlank() && !endpointFilter.equals(endpoint)) {
                return;
            }
            boolean error = group.stream().anyMatch(HotspotOtnlEngine::isError);
            if (onlyError && !error) {
                return;
            }
            long start = group.stream().mapToLong(TraceHandler.Span::getStartTime).min().orElse(0L);
            long end = group.stream().mapToLong(TraceHandler.Span::getEndTime).max().orElse(start);
            result.add(OtnlTrace.summary(traceId, serviceName(), endpoint,
                    Math.max(0L, end - start), start, end, error, group.size()));
        });
        Comparator<OtnlTrace> comparator = query != null && query.orderByDuration()
                ? Comparator.comparingLong(OtnlTrace::durationMs).reversed()
                : Comparator.comparingLong(OtnlTrace::startTime).reversed();
        result.sort(comparator);
        return page(result, query);
    }

    @Override
    public OtnlTrace trace(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return null;
        }
        List<TraceHandler.Span> group = new ArrayList<>();
        for (TraceHandler.Span span : allSpans()) {
            if (traceId.equals(span.getTraceId())) {
                group.add(span);
            }
        }
        if (group.isEmpty()) {
            return null;
        }
        group.sort(Comparator.comparingLong(TraceHandler.Span::getStartTime));
        List<OtnlSpan> converted = new ArrayList<>(group.size());
        long start = group.get(0).getStartTime();
        long end = start;
        boolean error = false;
        String rootEndpoint = null;
        for (TraceHandler.Span span : group) {
            boolean spanError = isError(span);
            error = error || spanError;
            end = Math.max(end, span.getEndTime());
            if (isRoot(span)) {
                rootEndpoint = endpoint(span);
            }
            List<OtnlTag> tags = new ArrayList<>();
            if (span.getClassName() != null) {
                tags.add(OtnlTag.of("class", span.getClassName()));
            }
            if (span.getMethodName() != null) {
                tags.add(OtnlTag.of("method", span.getMethodName()));
            }
            if (span.getDescriptor() != null) {
                tags.add(OtnlTag.of("descriptor", span.getDescriptor()));
            }
            if (span.getRequestPath() != null && !span.getRequestPath().isBlank()) {
                tags.add(OtnlTag.of("http.path", span.getRequestPath()));
            }
            if (span.getTags() != null && !span.getTags().isBlank()) {
                tags.add(OtnlTag.of("tags", span.getTags()));
            }
            List<OtnlSpan.OtnlSpanLog> logs = new ArrayList<>();
            String errorMessage = span.getException() == null ? span.getErrorMessage() : span.getException();
            if (errorMessage != null && !errorMessage.isBlank()) {
                logs.add(new OtnlSpan.OtnlSpanLog(span.getEndTime(),
                        List.of(OtnlTag.of("event", "error"), OtnlTag.of("message", errorMessage))));
            }
            converted.add(new OtnlSpan(traceId, null, span.getSpanId(), span.getParentSpanId(),
                    serviceName(), serviceName(), endpoint(span),
                    "Unknown", isRoot(span) ? "Entry" : "Local", null, null,
                    span.getStartTime(), span.getEndTime(),
                    Math.max(0L, span.getEndTime() - span.getStartTime()), spanError, tags, logs));
        }
        return new OtnlTrace(traceId, serviceName(), rootEndpoint, Math.max(0L, end - start),
                start, end, error, converted.size(), converted);
    }

    @Override
    public OtnlTopology topology(OtnlQuery query) {
        // 单个进程内的 Span 无法还原服务间拓扑
        return OtnlTopology.empty();
    }

    @Override
    public OtnlMetricSeries metrics(OtnlMetricQuery query) {
        // 指标时序需要独立的采样存储，hotspot agent 不提供
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
        List<TraceHandler.Span> spans = spans(query);
        if (spans.isEmpty()) {
            return OtnlHealth.empty("hotspot agent 内暂无 Span（可能尚未有请求进入或插桩未命中）");
        }
        Statistics statistics = Statistics.of(spans);
        return new OtnlHealth(statistics.errorRate() > 1D ? "DEGRADED" : "HEALTHY",
                0D, statistics.errorRate(), statistics.avgMs(), statistics.p99(),
                1, statistics.endpoints, 0, "数据来源：hotspot agent 进程内 Span");
    }

    @Override
    public void close() {
        // agent 生命周期由 ApmBootstrap 管理，引擎不持有资源
    }

    /**
     * 取 TraceHandler
     *
     * @return handler，未挂载返回 null
     */
    private TraceHandler handler() {
        try {
            return ApmBootstrap.getGlobalHandler(TraceHandler.class);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 全部 Span
     *
     * @return Span 列表
     */
    private List<TraceHandler.Span> allSpans() {
        TraceHandler handler = handler();
        if (handler == null) {
            return List.of();
        }
        try {
            List<TraceHandler.Span> spans = handler.getSpans();
            return spans == null ? List.of() : spans;
        } catch (Throwable e) {
            log.debug("[hotspot] 读取 Span 失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 按时间窗口过滤 Span
     *
     * @param query 查询条件
     * @return Span 列表
     */
    private List<TraceHandler.Span> spans(OtnlQuery query) {
        long end = query == null || query.endTime() <= 0 ? System.currentTimeMillis() : query.endTime();
        long start = query == null || query.startTime() <= 0 ? end - 30 * 60_000L : query.startTime();
        List<TraceHandler.Span> result = new ArrayList<>();
        for (TraceHandler.Span span : allSpans()) {
            if (span.getStartTime() >= start && span.getStartTime() <= end) {
                result.add(span);
            }
        }
        return result;
    }

    /**
     * 服务名：优先取 Spring 应用名，其次进程命令，最后兜底
     *
     * @return 服务名
     */
    private static String serviceName() {
        String fromProperty = System.getProperty("spring.application.name");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty;
        }
        String fromEnv = System.getenv("SPRING_APPLICATION_NAME");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return "local-java-service";
    }

    /**
     * 端点名（类名#方法名）
     *
     * @param span Span
     * @return 端点名
     */
    private static String endpoint(TraceHandler.Span span) {
        if (span.getRequestPath() != null && !span.getRequestPath().isBlank()) {
            return span.getRequestPath();
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
    private static boolean isError(TraceHandler.Span span) {
        return STATUS_ERROR.equalsIgnoreCase(span.getStatus());
    }

    /**
     * 是否是根 Span
     *
     * @param span Span
     * @return 是否根
     */
    private static boolean isRoot(TraceHandler.Span span) {
        return span.getParentSpanId() == null || span.getParentSpanId().isBlank();
    }

    /**
     * 取根 Span
     *
     * @param group 同一链路的 Span
     * @return 根 Span，没有时返回 null
     */
    private static TraceHandler.Span root(List<TraceHandler.Span> group) {
        for (TraceHandler.Span span : group) {
            if (isRoot(span)) {
                return span;
            }
        }
        return null;
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
     * Span 聚合统计
     */
    private static final class Statistics {
        private long calls;
        private long errors;
        private long totalMs;
        private final List<Long> durations = new ArrayList<>();
        private int endpoints;

        private void accept(TraceHandler.Span span) {
            calls++;
            if (isError(span)) {
                errors++;
            }
            long duration = Math.max(0L, span.getEndTime() - span.getStartTime());
            totalMs += duration;
            durations.add(duration);
        }

        private static Statistics of(List<TraceHandler.Span> spans) {
            Statistics statistics = new Statistics();
            java.util.Set<String> names = new java.util.HashSet<>();
            for (TraceHandler.Span span : spans) {
                statistics.accept(span);
                names.add(endpoint(span));
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
