package com.chua.common.support.scattergather;

import java.util.Map;
import java.util.Objects;

/**
 * 一次 Scatter-Gather 查询的上下文。
 * <p>封装单次查询的元数据，包括请求ID、路径、超时、最小成功数及属性。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ScatterGatherContext {

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
     * 是否仅Worker节点参与
     */
    private final boolean workerOnly;

    /**
     * 构造上下文。
     *
     * @param requestId       请求ID
     * @param path            路径
     * @param timeoutMillis   超时时间
     * @param minSuccessCount 最小成功数
     * @param attributes      属性
     */
    public ScatterGatherContext(String requestId, String path, long timeoutMillis, int minSuccessCount, Map<String, Object> attributes) {
        this(requestId, path, timeoutMillis, minSuccessCount, attributes, false);
    }

    /**
     * 构造上下文。
     *
     * @param requestId       请求ID
     * @param path            路径
     * @param timeoutMillis   超时时间
     * @param minSuccessCount 最小成功数
     * @param attributes      属性
     * @param workerOnly      是否仅Worker
     */
    public ScatterGatherContext(String requestId, String path, long timeoutMillis, int minSuccessCount, Map<String, Object> attributes, boolean workerOnly) {
        this.requestId = Objects.requireNonNull(requestId, "请求ID不能为空");
        this.path = Objects.requireNonNull(path, "路径不能为空");
        this.timeoutMillis = timeoutMillis;
        this.minSuccessCount = Math.max(1, minSuccessCount);
        this.attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        this.workerOnly = workerOnly;
    }

    /**
     * 返回仅Worker节点的上下文副本。
     *
     * @return 新上下文
     */
    public ScatterGatherContext workerOnly() {
        return new ScatterGatherContext(requestId, path, timeoutMillis, minSuccessCount, attributes, true);
    }

    /**
     * 获取请求ID。
     *
     * @return requestId
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * 获取路径。
     *
     * @return path
     */
    public String getPath() {
        return path;
    }

    /**
     * 获取超时时间。
     *
     * @return timeoutMillis
     */
    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    /**
     * 获取最小成功数。
     *
     * @return minSuccessCount
     */
    public int getMinSuccessCount() {
        return minSuccessCount;
    }

    /**
     * 获取属性。
     *
     * @return attributes
     */
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    /**
     * 是否仅Worker节点参与。
     *
     * @return true 仅Worker
     */
    public boolean isWorkerOnly() {
        return workerOnly;
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
}
