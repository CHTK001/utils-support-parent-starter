package com.chua.common.support.scatter;

import java.util.Objects;

/**
 * 节点查询结果。
 * <p>描述单节点查询结果，包含成功/失败、超时、降级等状态。</p>
 *
 * @param <T> 数据类型
 * @author CH
 * @since 4.0.0.42
 */
public final class ScatterResult<T> {

    /**
     * 节点ID
     */
    private final String nodeId;

    /**
     * 是否成功
     */
    private final boolean success;

    /**
     * 结果数据
     */
    private final T data;

    /**
     * 错误信息
     */
    private final String errorMessage;

    /**
     * 是否超时
     */
    private final boolean timeout;

    /**
     * 是否降级结果
     */
    private final boolean fallback;

    /**
     * 构造结果。
     *
     * @param nodeId       节点ID
     * @param success      是否成功
     * @param data         数据
     * @param errorMessage 错误信息
     * @param timeout      是否超时
     * @param fallback     是否降级
     */
    private ScatterResult(String nodeId, boolean success, T data, String errorMessage, boolean timeout, boolean fallback) {
        this.nodeId = Objects.requireNonNull(nodeId, "节点ID不能为空");
        this.success = success;
        this.data = data;
        this.errorMessage = errorMessage;
        this.timeout = timeout;
        this.fallback = fallback;
    }

    /**
     * 成功结果。
     *
     * @param nodeId 节点ID
     * @param data   数据
     * @param <T>    数据类型
     * @return 结果实例
     */
    public static <T> ScatterResult<T> success(String nodeId, T data) {
        return new ScatterResult<>(nodeId, true, data, null, false, false);
    }

    /**
     * 失败结果。
     *
     * @param nodeId       节点ID
     * @param errorMessage 错误信息
     * @param <T>          数据类型
     * @return 结果实例
     */
    public static <T> ScatterResult<T> failure(String nodeId, String errorMessage) {
        return new ScatterResult<>(nodeId, false, null, errorMessage, false, false);
    }

    /**
     * 超时结果。
     *
     * @param nodeId       节点ID
     * @param errorMessage 错误信息
     * @param <T>          数据类型
     * @return 结果实例
     */
    public static <T> ScatterResult<T> timeout(String nodeId, String errorMessage) {
        return new ScatterResult<>(nodeId, false, null, errorMessage, true, false);
    }

    /**
     * 降级结果。
     *
     * @param nodeId 节点ID
     * @param data   降级数据
     * @param <T>    数据类型
     * @return 结果实例
     */
    public static <T> ScatterResult<T> fallback(String nodeId, T data) {
        return new ScatterResult<>(nodeId, true, data, null, false, true);
    }

    public String getNodeId() {
        return nodeId;
    }

    public boolean isSuccess() {
        return success;
    }

    public T getData() {
        return data;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isTimeout() {
        return timeout;
    }

    public boolean isFallback() {
        return fallback;
    }
}
