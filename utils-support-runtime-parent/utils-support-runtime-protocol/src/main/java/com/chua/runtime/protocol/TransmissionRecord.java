package com.chua.runtime.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 传输链路事件 — 一次网络 / 进程内传输的记录。
 *
 * <p>同时为链路追踪（traceId/spanId）和传输链路（source/target）提供数据。
 * 应用层 处理器（Jedis/ZK/HTTP 客户端）显式声明 协议 + operation；
 * 套接字 层 处理器 通过端口推断 协议。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransmissionRecord {

    /**
     * 关联的追踪 标识
     */
    private String traceId;

    /**
     * 关联的 Span 标识
     */
    private String spanId;

    /**
     * 父 Span 标识（嵌套调用时填）
     */
    private String parentSpanId;

    /**
     * 源端点
     */
    private Endpoint source;

    /**
     * 目标端点
     */
    private Endpoint target;

    /**
     * 协议
     */
    private Protocol protocol;

    /**
     * 软件栈
     */
    private Software software;

    /**
     * 操作描述（"获取 /api/订单"、"设置 用户:1"、"创建 /znode/路径"）
     */
    private String operation;

    /**
     * 状态
     */
    @Builder.Default
    /** 状态 */
    private StatusCode status = StatusCode.UNSET;

    /**
     * 状态码（HTTP 状态 / ZK rc / Redis reply）
     */
    private int statusCode;

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
     * 发送字节数
     */
    private long bytesOut;

    /**
     * 接收字节数
     */
    private long bytesIn;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 错误类型
     */
    private String errorType;
}