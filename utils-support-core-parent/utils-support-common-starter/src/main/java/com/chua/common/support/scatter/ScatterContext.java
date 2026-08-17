package com.chua.common.support.scatter;

import java.util.Map;
import java.util.Objects;

/**
 * 一次 Scatter 查询的上下文。
 * <p>封装单次查询的元数据，包括请求ID、路径、超时及属性。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ScatterContext {

    /**
     * 请求唯一标识
     */
    private final String requestId;

    /**
     * 服务路径
     */
    private final String path;

    /**
     * 超时时间（毫秒）
     */
    private final long timeoutMillis;

    /**
     * 最小成功次数
     */
    private final int minSuccessCount;

    /**
     * 扩展属性
     */
    private final Map<String, Object> attributes;

    /**
     * 构造上下文。
     *
     * @param requestId       请求ID
     * @param path            路径
     * @param timeoutMillis   超时时间
     * @param minSuccessCount 最小成功数
     * @param attributes      属性
     */
    public ScatterContext(String requestId, String path, long timeoutMillis, int minSuccessCount,
                          Map<String, Object> attributes) {
        this.requestId = Objects.requireNonNull(requestId, "请求ID不能为空");
        this.path = Objects.requireNonNull(path, "路径不能为空");
        this.timeoutMillis = timeoutMillis;
        this.minSuccessCount = Math.max(1, minSuccessCount);
        this.attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public String getRequestId() {
        return requestId;
    }

    public String getPath() {
        return path;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public int getMinSuccessCount() {
        return minSuccessCount;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    /**
     * 按名称获取属性。
     *
     * @param name 属性名
     * @return 属性值
     */
    public Object attribute(String name) {
        return attributes.get(name);
    }

    /**
     * 可解析的序列化形式(sync 文本协议经 topic:payload 传输,需 toString 携带关键信息)。
     *
     * @return JSON 风格字符串
     */
    @Override
    public String toString() {
        return "{\"requestId\":\"" + requestId + "\",\"path\":\"" + path
                + "\",\"timeoutMillis\":" + timeoutMillis
                + ",\"minSuccessCount\":" + minSuccessCount + "}";
    }
}
