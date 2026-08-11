package com.chua.common.support.taskdistribution.scattergather;

import com.chua.common.support.scattergather.ScatterGatherResult;

/**
 * 带请求 ID 的响应包装。
 *
 * <p>TCP / UDP 远程客户端共用，用于将响应与原始请求关联。</p>
 *
 * @param requestId 请求 ID
 * @param result    执行结果
 * @author CH
 * @since 4.0.0.42
 */
public record ScatterGatherResultWithRequestId(String requestId, ScatterGatherResult<Object> result) {
}