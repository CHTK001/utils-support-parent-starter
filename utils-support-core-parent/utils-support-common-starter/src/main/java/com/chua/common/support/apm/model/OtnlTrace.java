package com.chua.common.support.apm.model;

import java.util.List;

/**
 * 链路（一次分布式调用的全部 Span）。
 *
 * @param traceId      链路 ID
 * @param serviceName  根 Span 所属服务（多服务链路时为起点服务）
 * @param endpointName 根 Span 端点名
 * @param durationMs   总耗时毫秒
 * @param startTime    起始毫秒戳
 * @param endTime      结束毫秒戳
 * @param error        是否含错误 Span
 * @param spanCount    Span 数
 * @param spans        详情 Span 列表（列表查询时为空，按 traceId 查详情才填充）
 * @author CH
 * @since 4.0.0.51
 */
public record OtnlTrace(String traceId,
                        String serviceName,
                        String endpointName,
                        long durationMs,
                        long startTime,
                        long endTime,
                        boolean error,
                        int spanCount,
                        List<OtnlSpan> spans) {

    /**
     * 列表用的链路摘要（不含 Span 明细）
     *
     * @param traceId      链路 ID
     * @param serviceName  服务名
     * @param endpointName 端点名
     * @param durationMs   耗时毫秒
     * @param startTime    起始毫秒
     * @param endTime      结束毫秒
     * @param error        是否错误
     * @param spanCount    Span 数
     * @return 链路
     */
    public static OtnlTrace summary(String traceId, String serviceName, String endpointName,
                                    long durationMs, long startTime, long endTime,
                                    boolean error, int spanCount) {
        return new OtnlTrace(traceId, serviceName, endpointName, durationMs, startTime, endTime,
                error, spanCount, List.of());
    }
}
