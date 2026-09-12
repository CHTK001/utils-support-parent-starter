package com.chua.common.support.scatter;

import lombok.Data;

/**
* scatter 请求上下文（单次同步/拉取的元数据）。
*
* @author CH
* @since 4.0.0.42
 */
@Data
public class ScatterContext {

    /** 请求标识 */
    private final String requestId;
    /** 服务路径 */
    private final String path;
    /** 超时毫秒 */
    private final long timeoutMillis;

    /**
    * scatter上下文。
    * @param requestId 请求标识
    * @param path 路径
    * @param timeoutMillis 超时millis
     */
    public ScatterContext(String requestId, String path, long timeoutMillis) {
        this.requestId = requestId;
        this.path = path;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public String toString() {
        return "ScatterContext{requestId='" + requestId + "', path='" + path
                + "', timeoutMillis=" + timeoutMillis + '}';
    }
}
