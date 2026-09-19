package com.chua.runtime.protocol;

/**
 * Span 类型（打开telemetry span种类 语义）。
 *
 * <p>SpanKind 决定 Span 在依赖图中的位置：</p>
 * <ul>
 *   <li>{@link #INTERNAL} — 进程内方法调用</li>
 *   <li>{@link #SERVER}   — 服务端处理请求</li>
 *   <li>{@link #CLIENT}   — 客户端发起请求</li>
 *   <li>{@link #PRODUCER} — 发送异步消息</li>
 *   <li>{@link #CONSUMER} — 消费异步消息</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum SpanKind {
    /**
     * 进程内方法调用
     */
    INTERNAL,

    /**
     * 服务端
     */
    SERVER,

    /**
     * 客户端
     */
    CLIENT,

    /**
     * 消息生产者
     */
    PRODUCER,

    /**
     * 消息消费者
     */
    CONSUMER
}