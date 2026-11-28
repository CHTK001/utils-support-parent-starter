package com.chua.datasource.support.middleware.mongodb;

/**
 * MongoDB 连通性探测结果。
 *
 * @param connected 是否连通
 * @param version   服务端版本号，取自 {@code buildInfo}，取不到时为空串
 * @param message   结果说明；失败时为可读原因
 * @author CH
 * @since 4.0.0.51
 */
public record MongoTestResult(Boolean connected, String version, String message) {
}
