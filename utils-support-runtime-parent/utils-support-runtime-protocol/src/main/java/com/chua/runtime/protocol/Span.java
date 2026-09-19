package com.chua.runtime.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Span — 一次方法调用的执行单元。
 *
 * <p>OpenTelemetry Span 模型：</p>
 * <ul>
 *   <li>{@link SpanKind} 决定 Span 在依赖图中的位置</li>
 *   <li>traceId 同一根调用链共享；spanId 单 Span 唯一；parentSpanId 嵌套关系</li>
 *   <li>attributes 自定义标签（http.status_code、db.statement 等）</li>
 *   <li>events 生命周期事件（exception / message）</li>
 *   <li>links 跨 trace 关联（异步消息）</li>
 *   <li>message 标准信息（如 Tomcat 处理时渲染出 "HTTP GET /api/order 200 OK 12ms"）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Span {

    /**
     * 全局追踪 标识（同一根调用链共享）
     */
    private String traceId;

    /**
     * 当前 Span 标识
     */
    private String spanId;

    /**
     * 父 Span 标识（根调用为 空）
     */
    private String parentSpanId;

    /**
     * Span 类型
     */
    @Builder.Default
    /** 种类 */
    private SpanKind kind = SpanKind.INTERNAL;

    /**
     * Span 名（默认 {@code className.methodName}，也可填 SQL/HTTP 等）
     */
    private String name;

    /**
     * 类名（内部 时填）
     */
    private String className;

    /**
     * 方法名（内部 时填）
     */
    private String methodName;

    /**
     * 方法描述符
     */
    private String descriptor;

    /**
     * 开始时间（毫秒）
     */
    private long startTime;

    /**
     * 结束时间（毫秒）
     */
    private long endTime;

    /**
     * 耗时（毫秒）
     */
    private long duration;

    /**
     * 状态
     */
    @Builder.Default
    /** 状态 */
    private StatusCode status = StatusCode.UNSET;

    /**
     * 标准描述信息（"HTTP 获取 /api/订单 200 OK 12ms"）。
     *
     * <p>用于传输链路 / 日志 / 调试输出，对齐 SkyWalking / OpenTelemetry message 字段。</p>
     */
    private String message;

    /**
     * 异常类名（状态=错误 时填）
     */
    private String errorType;

    /**
     * 异常消息
     */
    private String errorMessage;

    /**
     * 自定义属性
     */
    @Builder.Default
    private Map<String, String> attributes = new HashMap<>(); // attributes

    /**
     * 生命周期事件
     */
    @Builder.Default
    /** 事件 */
    private List<SpanEvent> events = new ArrayList<>();

    /**
     * 跨 追踪 关联（异步消息、批处理）
     */
    @Builder.Default
    /** 链接 */
    private List<SpanLink> links = new ArrayList<>();

    /**
     * 添加事件。
     *
     * @param eventName 事件名
     */
    public void addEvent(String eventName) {
        events.add(SpanEvent.builder()
                .eventName(eventName)
                .timestamp(System.currentTimeMillis())
                .build());
    }

    /**
     * 添加带属性的事件。
     *
     * @param eventName 事件名
     * @param attributes 属性
     */
    public void addEvent(String eventName, Map<String, String> attributes) {
        events.add(SpanEvent.builder()
                .eventName(eventName)
                .timestamp(System.currentTimeMillis())
                .attributes(attributes != null ? attributes : new HashMap<>())
                .build());
    }

    /**
     * 设置异常事件（打开telemetry semantic conventions）。
     *
     * @param errorType 异常类名
     * @param errorMessage 异常消息
     */
    public void recordException(String errorType, String errorMessage) {
        this.status = StatusCode.ERROR;
        this.errorType = errorType;
        this.errorMessage = errorMessage;
        Map<String, String> attrs = new HashMap<>();
        attrs.put("exception.type", errorType);
        attrs.put("exception.message", errorMessage != null ? errorMessage : "");
        addEvent("exception", attrs);
    }
}