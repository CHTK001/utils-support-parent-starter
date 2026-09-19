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
     * 构造方法，创建 Scatter上下文 实例。
     *
     * @param requestId 请求ID，不允许为 null
     * @param path 路径，不允许为 null
     * @param timeoutMillis 超时时间毫秒数，不允许为 null
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
