package com.chua.common.support.scattergather;

/**
 * 带请求 ID 的响应包装。
 * <p>用于在远程调用中关联请求与响应。</p>
 *
 * @param requestId 请求 ID
 * @param result    执行结果
 * @author CH
 */
public record ScatterGatherResultWithRequestId(String requestId, ScatterGatherResult<Object> result) {
}