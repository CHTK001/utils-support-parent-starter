package com.chua.runtime.protocol;

import lombok.Builder;

/**
 * * Span 跨 追踪 关联 — 用于异步消息 / 批处理场景。
 *
 * <p>例：Kafka Consumer 收到消息后开始新 trace，但通过 link 关联到 Producer 的 span。</p>
 *
 * @param traceId 关联的 追踪 标识
 * @param spanId  关联的 span 标识
 * @author CH
 * @since 4.0.0.42
 */
@Builder
public record SpanLink(
        String traceId,
        String spanId
) {
}