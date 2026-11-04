package com.chua.common.support.apm.model;

import com.chua.common.support.apm.OtnlTag;

import java.util.List;

/**
 * 链路中的一个 Span。
 * <p>
 * 字段与 SkyWalking {@code Span} 对齐：{@code parentSpanId} 为空表示根 Span，
 * 前端瀑布图靠 {@code parentSpanId} + {@code startTime} 重建层级。
 * </p>
 *
 * @param traceId      链路 ID
 * @param segmentId    段 ID（同一进程内的一段调用）
 * @param spanId       Span ID
 * @param parentSpanId 父 Span ID，根 Span 为 null/空
 * @param serviceName  所属服务
 * @param instanceName 所属实例
 * @param endpointName 端点名/操作名
 * @param layer        分层（HTTP / RPC_FRAMEWORK / DB / CACHE / MQ …）
 * @param type         类型（Entry / Exit / Local）
 * @param component    组件名（spring-mvc / mysql / redis …）
 * @param peer         对端地址（出口 Span 才有）
 * @param startTime    起始毫秒戳
 * @param endTime      结束毫秒戳
 * @param durationMs   耗时毫秒
 * @param error        是否错误
 * @param tags         标签
 * @param logs         日志事件（时间 + 标签）
 * @author CH
 * @since 4.0.0.51
 */
public record OtnlSpan(String traceId,
                       String segmentId,
                       String spanId,
                       String parentSpanId,
                       String serviceName,
                       String instanceName,
                       String endpointName,
                       String layer,
                       String type,
                       String component,
                       String peer,
                       long startTime,
                       long endTime,
                       long durationMs,
                       boolean error,
                       List<OtnlTag> tags,
                       List<OtnlSpanLog> logs) {

    /**
     * Span 日志事件
     *
     * @param time 毫秒戳
     * @param tags 标签（通常是 event=error / message=… / stack=…）
     * @author CH
     * @since 4.0.0.51
     */
    public record OtnlSpanLog(long time, List<OtnlTag> tags) {
    }
}
