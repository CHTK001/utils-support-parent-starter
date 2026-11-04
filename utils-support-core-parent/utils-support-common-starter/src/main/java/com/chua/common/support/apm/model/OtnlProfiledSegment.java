package com.chua.common.support.apm.model;

import java.util.List;

/**
 * 被测到的链路片段（对齐 SkyWalking {@code ProfiledTraceSegments}）。
 * <p>
 * 剖析任务运行期间，只有「耗时超过阈值」的请求才会被采样。一条被采样的链路
 * 对应一个 segment，前端据此列出「这次任务到底抓到了哪些慢请求」，
 * 再点进去看栈分析。
 * </p>
 *
 * @param traceId      链路 ID（可与链路页联动）
 * @param segmentId    片段 ID（取栈分析时要用）
 * @param instanceId   实例 ID
 * @param instanceName 实例名
 * @param endpointNames 端点名
 * @param durationMs   耗时毫秒
 * @param startTime    起始毫秒戳
 * @param spanCount    Span 数
 * @author CH
 * @since 4.0.0.51
 */
public record OtnlProfiledSegment(String traceId,
                                  String segmentId,
                                  String instanceId,
                                  String instanceName,
                                  List<String> endpointNames,
                                  long durationMs,
                                  long startTime,
                                  int spanCount) {
}
