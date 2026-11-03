package com.chua.runtime.protocol;

import com.chua.common.support.collection.wrap.Wraps;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * Span — 一次方法调用的执行单元。
 *
 * <p>注入到 Jaeger 的模型：</p>
 * <ul>
 *   <li>traceId 同一根调用链共享；spanId 单 Span 唯一；parentSpanId 嵌套关系</li>
 *   <li>name / className / methodName 描述本次调用</li>
 *   <li>startTime / endTime / duration 时间</li>
 *   <li>status / errorType / errorMessage 错误信息</li>
 *   <li>message 标准信息（如 "HTTP GET /api/order 200 OK 12ms"）</li>
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
    private StatusCode status = StatusCode.UNSET;

    /**
     * 标准描述信息（"HTTP 获取 /api/订单 200 OK 12ms"）。
     *
     * <p>用于传输链路 / 日志 / 调试输出，例如 Jaeger 的 span name。</p>
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
     * 生命周期事件
     */
    @Builder.Default
    private List<SpanEvent> events = Wraps.list();

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
     * 记录异常（标记为错误，并生成 exception 事件）。
     *
     * @param errorType 异常类名
     * @param errorMessage 异常消息
     */
    public void recordException(String errorType, String errorMessage) {
        this.status = StatusCode.ERROR;
        this.errorType = errorType;
        this.errorMessage = errorMessage;
        addEvent("exception");
    }
}