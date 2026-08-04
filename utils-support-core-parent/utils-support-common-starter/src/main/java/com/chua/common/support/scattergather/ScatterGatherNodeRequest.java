package com.chua.common.support.scattergather;

import java.util.Map;
import org.jspecify.annotations.NullUnmarked;

/**
 * TCP 节点查询请求。
 * <p>用于节点间传输查询请求信息，支持与上下文互转。</p>
 *
 * @author CH
 */
@NullUnmarked
public class ScatterGatherNodeRequest {

    /**
     * 请求ID
     */
    private String requestId;

    /**
     * 服务路径
     */
    private String path;

    /**
     * 超时时间（毫秒）
     */
    private long timeoutMillis;

    /**
     * 最小成功次数
     */
    private int minSuccessCount;

    /**
     * 扩展属性
     */
    private Map<String, Object> attributes;

    /**
     * 默认构造。
     */
    public ScatterGatherNodeRequest() {
    }

    /**
     * 构造请求。
     *
     * @param requestId       请求ID
     * @param path            路径
     * @param timeoutMillis   超时时间
     * @param minSuccessCount 最小成功数
     * @param attributes      属性
     */
    public ScatterGatherNodeRequest(String requestId, String path, long timeoutMillis, int minSuccessCount, Map<String, Object> attributes) {
        this.requestId = requestId;
        this.path = path;
        this.timeoutMillis = timeoutMillis;
        this.minSuccessCount = minSuccessCount;
        this.attributes = attributes;
    }

    /**
     * 从上下文创建请求。
     *
     * @param context 上下文
     * @return 请求对象
     */
    public static ScatterGatherNodeRequest from(ScatterGatherContext context) {
        return new ScatterGatherNodeRequest(context.getRequestId(), context.getPath(), context.getTimeoutMillis(), context.getMinSuccessCount(), context.getAttributes());
    }

    /**
     * 转换为上下文。
     *
     * @return 上下文
     */
    public ScatterGatherContext toContext() {
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
     * 设置请求ID。
     *
     * @param requestId 请求ID
     */
    public void setRequestId(String requestId) {
        this.requestId = requestId;
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
     * 设置路径。
     *
     * @param path 路径
     */
    public void setPath(String path) {
        this.path = path;
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
     * 设置超时时间。
     *
     * @param timeoutMillis 超时时间
     */
    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
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
     * 设置最小成功数。
     *
     * @param minSuccessCount 最小成功数
     */
    public void setMinSuccessCount(int minSuccessCount) {
        this.minSuccessCount = minSuccessCount;
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
     * 设置属性。
     *
     * @param attributes 属性
     */
    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }
}
