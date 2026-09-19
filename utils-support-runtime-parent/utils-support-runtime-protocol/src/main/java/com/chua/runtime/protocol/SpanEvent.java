package com.chua.runtime.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Span 事件 — Span 生命周期内的标记点（异常、日志消息）。
 *
 * <p>类比 OpenTelemetry SpanEvent：每个事件有时间戳和属性。
 * 例：捕获到异常时记录 事件(事件名称="异常", attributes={异常.类型, 异常.消息, stack})。</p>
 *
 * @param eventName 事件名
 * @param timestamp 事件时间戳（毫秒）
 * @param attributes 事件属性
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpanEvent {

    /**
     * 事件名（如 {@code "exception"} / {@code "log"} / {@code "checkpoint"}）
     */
    private String eventName;

    /**
     * 事件时间戳（毫秒）
     */
    private long timestamp;

    /**
     * 事件属性
     */
    @Builder.Default
    private Map<String, String> attributes = new HashMap<>(); // attributes
}