package com.chua.common.support.scattergather;

import org.jspecify.annotations.NullUnmarked;

/**
 * TCP 节点查询响应。
 * <p>节点服务处理请求后返回的响应对象，可转换为通用结果。</p>
 *
 * @param <T> 数据类型
 * @author CH
 */
@NullUnmarked
public class ScatterGatherNodeResponse<T> {

    /**
     * 节点ID
     */
    private String nodeId;

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 响应数据
     */
    private T data;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 默认构造。
     */
    public ScatterGatherNodeResponse() {
    }

    /**
     * 构造响应。
     *
     * @param nodeId      节点ID
     * @param success     是否成功
     * @param data        数据
     * @param errorMessage 错误信息
     */
    public ScatterGatherNodeResponse(String nodeId, boolean success, T data, String errorMessage) {
        this.nodeId = nodeId;
        this.success = success;
        this.data = data;
        this.errorMessage = errorMessage;
    }

    /**
     * 成功响应。
     *
     * @param nodeId 节点ID
     * @param data   数据
     * @param <T>    数据类型
     * @return 响应对象
     */
    public static <T> ScatterGatherNodeResponse<T> success(String nodeId, T data) {
        return new ScatterGatherNodeResponse<>(nodeId, true, data, null);
    }

    /**
     * 失败响应。
     *
     * @param nodeId      节点ID
     * @param errorMessage 错误信息
     * @param <T>         数据类型
     * @return 响应对象
     */
    public static <T> ScatterGatherNodeResponse<T> failure(String nodeId, String errorMessage) {
        return new ScatterGatherNodeResponse<>(nodeId, false, null, errorMessage);
    }

    /**
     * 转换为通用结果。
     *
     * @return 通用结果
     */
    public ScatterGatherResult<T> toResult() {
        if (success) {
            return ScatterGatherResult.success(nodeId, data);
        }
        return ScatterGatherResult.failure(nodeId, errorMessage);
    }

    /**
     * 获取节点ID。
     *
     * @return nodeId
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * 设置节点ID。
     *
     * @param nodeId 节点ID
     */
    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    /**
     * 是否成功。
     *
     * @return true 成功
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * 设置成功标志。
     *
     * @param success 成功标志
     */
    public void setSuccess(boolean success) {
        this.success = success;
    }

    /**
     * 获取数据。
     *
     * @return data
     */
    public T getData() {
        return data;
    }

    /**
     * 设置数据。
     *
     * @param data 数据
     */
    public void setData(T data) {
        this.data = data;
    }

    /**
     * 获取错误信息。
     *
     * @return errorMessage
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * 设置错误信息。
     *
     * @param errorMessage 错误信息
     */
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
