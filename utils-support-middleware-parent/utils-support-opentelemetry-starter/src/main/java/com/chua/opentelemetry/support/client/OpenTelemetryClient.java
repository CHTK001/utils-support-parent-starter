// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.opentelemetry.support.client;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.runtime.protocol.Span;
import com.chua.runtime.protocol.SpanEvent;
import com.chua.runtime.protocol.StatusCode;
import com.chua.runtime.protocol.Trace;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanLimits;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * OpenTelemetry 客户端
 * <p>
 * 封装 {@link io.opentelemetry.sdk.OpenTelemetrySdk} 的生命周期：
 * 未指定传输 {@link SpanExporter} 时，默认使用 {@link InMemorySpanExporter}
 * 把 Span 缓存到内存，再提供 {@link #traces()} / {@link #spans()} / {@link #spansByTraceId(String)} /
 * {@link #trace(String)} 作为轻量查询入口。
 * <p>
 * 关闭后的客户端拒绝再次创建 Span 或查询（查询返回空列表），关闭方法幂等。
 * </p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Slf4j
public class OpenTelemetryClient implements AutoCloseable {

    /**
     * 默认服务名
     */
    private static final String DEFAULT_SERVICE_NAME = "unknown-service";

    /**
     * 默认采样器：始终开启
     */
    private static final Sampler DEFAULT_SAMPLER = Sampler.alwaysOn();

    /**
     * 客户端创建器
     */
    private final String serviceName;

    /**
     * OTLP 地址（传输期有效, 默认内存导出器时不生效）
     */
    private final String endpoint;

    /**
     * OpenTelemetry SDK 实例
     */
    private final io.opentelemetry.api.OpenTelemetry openTelemetry;

    /**
     * 跟踪提供者, 关闭后无法创建 Span
     */
    private final SdkTracerProvider tracerProvider;

    /**
     * 内存导出器, 未指定传输导出器时具有值
     */
    private final InMemorySpanExporter spanExporter;

    /**
     * 跟踪器, 用于创建 Span 的快捷访问
     */
    private final Tracer tracer;

    /**
     * 关闭标记, 关闭后向外暴露的空列表与 {@link #ensureOpen()} 守卫共享
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 创建客户端的构建器
     *
     * @return 构建器
     */
    public static OpenTelemetryClientBuilder builder() {
        return new OpenTelemetryClientBuilder();
    }

    /**
     * 获取服务名称
     *
     * @return 服务名
     */
    public String getServiceName() {
        return serviceName;
    }

    /**
     * 获取 OTLP 地址
     *
     * @return 地址, 本地查询模式下为空
     */
    public String getEndpoint() {
        return endpoint;
    }

    /**
     * 获取 OpenTelemetry SDK 实例
     *
     * @return OpenTelemetry 实例
     */
    public io.opentelemetry.api.OpenTelemetry getOpenTelemetry() {
        return openTelemetry;
    }

    /**
     * 获取 SDK 跟踪提供者
     *
     * @return 跟踪提供者
     */
    public SdkTracerProvider tracerProvider() {
        ensureOpen();
        return tracerProvider;
    }

    /**
     * 获取 Tracer, 用于 {@link io.opentelemetry.api.trace.Span} 级别的使用
     *
     * @return Tracer
     */
    public Tracer tracer() {
        ensureOpen();
        return tracer;
    }

    /**
     * 查询全部 Trace
     *
     * @return Trace 列表
     */
    public List<Trace> traces() {
        if (closed.get()) {
            return List.of();
        }
        List<SpanData> data = finishedSpans();
        if (data.isEmpty()) {
            return List.of();
        }
        return toTraces(data);
    }

    /**
     * 查询全部 Span
     *
     * @return Span 列表
     */
    public List<Span> spans() {
        if (closed.get()) {
            return List.of();
        }
        return toSpans(finishedSpans());
    }

    /**
     * 查询指定名称的 Span
     *
     * @param name Span 名称
     * @return Span 列表
     */
    public List<Span> spans(String name) {
        if (closed.get()) {
            return List.of();
        }
        return spans().stream()
                .filter(span -> span.getName().equals(name))
                .collect(Collectors.toList());
    }

    /**
     * 查询指定 Trace 的 Span
     *
     * @param traceId traceId
     * @return Span 列表
     */
    public List<Span> spansByTraceId(String traceId) {
        if (closed.get()) {
            return List.of();
        }
        return spans().stream()
                .filter(span -> span.getTraceId().equals(traceId))
                .collect(Collectors.toList());
    }

    /**
     * 查询指定 Trace
     *
     * @param traceId traceId
     * @return Trace
     */
    public Trace trace(String traceId) {
        if (closed.get()) {
            return null;
        }
        return traces().stream()
                .filter(t -> t.getTraceId().equals(traceId))
                .findFirst()
                .orElse(null);
    }

    /**
     * 是否已关闭
     *
     * @return 已关闭返回 true
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 创建 Span, 仅用于快速附属接入, 复杂使用请使用 {@link #tracer()}
     *
     * @param name Span 名称
     * @return Span 实例
     */
    public io.opentelemetry.api.trace.Span startSpan(String name) {
        ensureOpen();
        return spanBuilder(name).startSpan();
    }

    /**
     * 创建 Span, 仅用于快速附属接入, 复杂使用请使用 {@link #tracer()}
     *
     * @param name Span 名称
     * @param kind 跨度类型
     * @return Span 实例
     */
    public io.opentelemetry.api.trace.Span startSpan(String name, SpanKind kind) {
        ensureOpen();
        return spanBuilder(name).setSpanKind(kind).startSpan();
    }

    /**
     * 关闭客户端, 释放内部 {@link SdkTracerProvider} 与 {@link InMemorySpanExporter}
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            tracerProvider.shutdown().join(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("OpenTelemetryClient 关闭 tracerProvider 失败: {}", e.getMessage());
        }
        if (spanExporter != null) {
            try {
                spanExporter.shutdown().join(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("OpenTelemetryClient 关闭 spanExporter 失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 关闭后调用守卫
     */
    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("OpenTelemetryClient 已关闭, 不可再创建 Span 或查询");
        }
    }

    /**
     * 下发全部已结束的 Span 到内存导出器并返回快照
     *
     * @return SpanData 快照
     */
    private List<SpanData> finishedSpans() {
        if (spanExporter == null) {
            return List.of();
        }
        CompletableResultCode flush = tracerProvider.forceFlush();
        if (flush != null) {
            try {
                flush.join(5, TimeUnit.SECONDS);
            } catch (Throwable ignored) {
                // 超时或内部异常不阻断查询
            }
        }
        return Wraps.list(spanExporter.getFinishedSpanItems());
    }

    /**
     * 按 traceId 分组拼装 Trace
     *
     * @param data SpanData 集
     * @return Trace 列表
     */
    private List<Trace> toTraces(List<SpanData> data) {
        Map<String, List<Span>> grouped = Wraps.<String, List<Span>>map();
        for (SpanData datum : data) {
            grouped.computeIfAbsent(datum.getTraceId(), k -> Wraps.list()).add(toSpan(datum));
        }
        List<Trace> traces = new ArrayList<>();
        for (Map.Entry<String, List<Span>> entry : grouped.entrySet()) {
            traces.add(toTrace(entry.getKey(), entry.getValue()));
        }
        return traces;
    }

    /**
     * 全部 SpanData 转换
     *
     * @param data SpanData 集
     * @return Span 列表
     */
    private List<Span> toSpans(List<SpanData> data) {
        if (data == null) {
            return List.of();
        }
        return data.stream().map(this::toSpan).collect(Collectors.toList());
    }

    /**
     * SpanData 转协议 Span
     *
     * @param data SpanData
     * @return 协议 Span
     */
    private Span toSpan(SpanData data) {
        Span.SpanBuilder builder = Span.builder();
        builder.traceId(data.getTraceId());
        builder.spanId(data.getSpanId());
        builder.parentSpanId(data.getParentSpanId());
        builder.name(data.getName());
        attributes(data.getAttributes(), (key, value) -> {
            switch (key) {
                case "class":
                    builder.className(String.valueOf(value));
                    break;
                case "method":
                    builder.methodName(String.valueOf(value));
                    break;
                case "descriptor":
                    builder.descriptor(String.valueOf(value));
                    break;
                case "message":
                    builder.message(String.valueOf(value));
                    break;
                case "error.type":
                    builder.errorType(String.valueOf(value));
                    break;
                case "error.message":
                    builder.errorMessage(String.valueOf(value));
                    break;
                default:
                    // 未使用的属性不参与协议模型
                    break;
            }
        });
        builder.startTime(data.getStartEpochNanos() / 1_000_000L);
        builder.endTime(data.getEndEpochNanos() / 1_000_000L);
        builder.duration(data.getEndEpochNanos() - data.getStartEpochNanos());
        StatusData status = data.getStatus();
        if (status != null) {
            builder.status(statusToCode(status));
        }
        List<SpanEvent> events = toEvents(data);
        if (!events.isEmpty()) {
            builder.events(events);
        }
        return builder.build();
    }

    /**
     * 属性集合映射
     *
     * @param attributes 属性
     * @param visitor    遍历时处理
     */
    private static void attributes(Attributes attributes, BiConsumer<String, Object> visitor) {
        if (attributes == null || attributes.isEmpty()) {
            return;
        }
        attributes.forEach((key, value) -> visitor.accept(key.getKey(), value));
    }

    /**
     * 状态码转换
     *
     * @param status OTel 状态
     * @return 协议 StatusCode
     */
    private static StatusCode statusToCode(StatusData status) {
        io.opentelemetry.api.trace.StatusCode code = status.getStatusCode();
        switch (code) {
            case OK:
                return StatusCode.OK;
            case ERROR:
                return StatusCode.ERROR;
            default:
                return StatusCode.UNSET;
        }
    }

    /**
     * 事件转换
     *
     * @param data SpanData
     * @return 协议事件
     */
    private static List<SpanEvent> toEvents(SpanData data) {
        List<SpanEvent> events = Wraps.list();
        List<EventData> otelEvents = data.getEvents();
        if (otelEvents == null) {
            return events;
        }
        for (EventData otelEvent : otelEvents) {
            events.add(SpanEvent.builder()
                    .eventName(otelEvent.getName())
                    .timestamp(otelEvent.getEpochNanos() / 1_000_000L)
                    .attributes(toMap(otelEvent.getAttributes()))
                    .build());
        }
        return events;
    }

    /**
     * 属性映射转换
     *
     * @param attributes 属性
     * @return Map 地图
     */
    private static Map<String, String> toMap(Attributes attributes) {
        Map<String, String> map = Wraps.map();
        if (attributes == null || attributes.isEmpty()) {
            return map;
        }
        attributes.forEach((key, value) -> map.put(key.getKey(), String.valueOf(value)));
        return map;
    }

    /**
     * Trace 拼装
     *
     * @param traceId traceId
     * @param spans   Span 列表
     * @return Trace
     */
    private static Trace toTrace(String traceId, List<Span> spans) {
        Trace.TraceBuilder builder = Trace.builder();
        builder.traceId(traceId);
        if (spans.isEmpty()) {
            builder.finished(true);
            return builder.build();
        }
        List<Span> sorted = spans.stream()
                .sorted(java.util.Comparator.comparingLong(Span::getStartTime))
                .collect(Collectors.toList());
        builder.spans(sorted);
        Span root = null;
        for (Span span : sorted) {
            if (span.getParentSpanId() == null || span.getParentSpanId().isEmpty()) {
                root = span;
                break;
            }
        }
        builder.root(root);
        long start = Long.MAX_VALUE;
        long end = Long.MIN_VALUE;
        for (Span span : sorted) {
            start = Math.min(start, span.getStartTime());
            end = Math.max(end, span.getEndTime());
        }
        builder.startTime(start);
        builder.endTime(end);
        builder.finished(true);
        return builder.build();
    }

    /**
     * 创建 Span 的快捷包装
     *
     * @param name Span 名称
     * @return SpanBuilder
     */
    private SpanBuilder spanBuilder(String name) {
        return tracer.spanBuilder(name);
    }

    /**
     * 停止 Span
     *
     * @param span Span 实例
     */
    protected void endSpan(io.opentelemetry.api.trace.Span span) {
        try {
            span.end();
        } catch (Exception e) {
            log.warn("Span 停止失败: {}", e.getMessage());
        }
    }

    /**
     * 客户端构建器
     */
    public static class OpenTelemetryClientBuilder {

        private String serviceName = DEFAULT_SERVICE_NAME;
        private String endpoint;
        private Attributes attributes;
        private AttributesBuilder attributeBuilder;
        private Resource resource = Resource.getDefault();
        private Sampler sampler = DEFAULT_SAMPLER;
        private SpanLimits spanLimits = SpanLimits.getDefault();
        private SpanExporter spanExporter;

        /**
         * 设置服务名
         *
         * @param serviceName 服务名
         * @return this
         */
        public OpenTelemetryClientBuilder serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }

        /**
         * 设置 OTLP 接收机地址
         *
         * @param endpoint 地址
         * @return this
         */
        public OpenTelemetryClientBuilder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /**
         * 设置资源, 优先于 {@link #resource(Resource)}
         *
         * @param attributes 属性
         * @return this
         */
        public OpenTelemetryClientBuilder attributes(Attributes attributes) {
            this.attributes = attributes;
            return this;
        }

        /**
         * 设置单条属性
         *
         * @param key   属性键
         * @param value 属性值
         * @return this
         */
        public OpenTelemetryClientBuilder attribute(String key, String value) {
            if (attributeBuilder == null) {
                attributeBuilder = Attributes.builder();
            }
            attributeBuilder.put(key, value);
            return this;
        }

        /**
         * 设置资源
         *
         * @param resource 资源
         * @return this
         */
        public OpenTelemetryClientBuilder resource(Resource resource) {
            this.resource = resource;
            return this;
        }

        /**
         * 设置采样器
         *
         * @param sampler 采样器
         * @return this
         */
        public OpenTelemetryClientBuilder sampler(Sampler sampler) {
            this.sampler = sampler;
            return this;
        }

        /**
         * 设置传输导出器
         *
         * @param spanExporter 导出器
         * @return this
         */
        public OpenTelemetryClientBuilder spanExporter(SpanExporter spanExporter) {
            this.spanExporter = spanExporter;
            return this;
        }

        /**
         * 设置 Span 限制
         *
         * @param spanLimits Span 限制
         * @return this
         */
        public OpenTelemetryClientBuilder spanLimits(SpanLimits spanLimits) {
            this.spanLimits = spanLimits;
            return this;
        }

        /**
         * 构建客户端
         *
         * @return OpenTelemetryClient
         */
        public OpenTelemetryClient build() {
            return new OpenTelemetryClient(this);
        }
    }

    private OpenTelemetryClient(OpenTelemetryClientBuilder builder) {
        this.serviceName = builder.serviceName;
        this.endpoint = builder.endpoint;
        SdkTracerProviderBuilder providerBuilder = SdkTracerProvider.builder()
                .setResource(builder.resource)
                .setSampler(builder.sampler)
                .setSpanLimits(builder.spanLimits);
        if (builder.attributes != null) {
            providerBuilder.setResource(builder.resource.toBuilder().putAll(builder.attributes).build());
        }
        if (builder.spanExporter != null) {
            // 传输模式: 使用外部导出器
            this.spanExporter = null;
            providerBuilder.addSpanProcessor(SimpleSpanProcessor.create(builder.spanExporter));
        } else {
            // 本地查询模式: 使用内存导出器
            this.spanExporter = InMemorySpanExporter.create();
            providerBuilder.addSpanProcessor(SimpleSpanProcessor.create(this.spanExporter));
        }
        this.tracerProvider = providerBuilder.build();
        this.openTelemetry = io.opentelemetry.sdk.OpenTelemetrySdk.builder()
                .setTracerProvider(this.tracerProvider)
                .build();
        this.tracer = this.openTelemetry.getTracer(this.serviceName);
    }
}
