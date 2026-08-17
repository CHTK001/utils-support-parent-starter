package com.chua.common.support.scatter;

/**
 * 带请求ID的结果包装，用于远程响应定位（requestId -> Future）。
 *
 * @param requestId 请求ID
 * @param result    查询结果
 * @param <T>       数据类型
 * @author CH
 * @since 4.0.0.42
 */
public record ScatterResultWithRequestId<T>(String requestId, ScatterResult<T> result) {
}
