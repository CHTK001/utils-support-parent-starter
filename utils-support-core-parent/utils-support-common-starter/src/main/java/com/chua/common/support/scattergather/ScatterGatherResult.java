package com.chua.common.support.scattergather;

import java.util.Objects;
import org.jspecify.annotations.NullUnmarked;

/**
 * 节点查询结果。
 * <p>描述单节点查询结果，包含成功/失败、超时、降级等状态。</p>
 *
 * @param <T> 数据类型
 * @author CH
 */
@NullUnmarked
public final class ScatterGatherResult<T> {

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
     * @param nodeId     节点ID
     * @param success    是否成功
     * @param data       数据
     * @param errorMessage 错误信息
     * @param timeout    是否超时
     * @param fallback   是否降级
     */
    private ScatterGatherResult(String nodeId, boolean success, T data, String errorMessage, boolean timeout, boolean fallback) {
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
    public static <T> ScatterGatherResult<T> success(String nodeId, T data) {
        return new ScatterGatherResult<>(nodeId, true, data, null, false, false);
    }

    /**
     * 失败结果。
     *
     * @param nodeId      节点ID
     * @param errorMessage 错误信息
     * @param <T>         数据类型
     * @return 结果实例
     */
    public static <T> ScatterGatherResult<T> failure(String nodeId, String errorMessage) {
        return new ScatterGatherResult<>(nodeId, false, null, errorMessage, false, false);
    }

    /**
     * 超时结果。
     *
     * @param nodeId      节点ID
     * @param errorMessage 错误信息
     * @param <T>         数据类型
     * @return 结果实例
     */
    public static <T> ScatterGatherResult<T> timeout(String nodeId, String errorMessage) {
        return new ScatterGatherResult<>(nodeId, false, null, errorMessage, true, false);
    }

    /**
     * 降级结果。
     *
     * @param nodeId 节点ID
     * @param data   降级数据
     * @param <T>    数据类型
     * @return 结果实例
     */
    public static <T> ScatterGatherResult<T> fallback(String nodeId, T data) {
        return new ScatterGatherResult<>(nodeId, true, data, null, false, true);
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
     * 是否成功。
     *
     * @return true 成功
     */
    public boolean isSuccess() {
        return success;
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
     * 获取错误信息。
     *
     * @return errorMessage
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * 是否超时。
     *
     * @return true 超时
     */
    public boolean isTimeout() {
        return timeout;
    }

    /**
     * 是否降级结果。
     *
     * @return true 降级
     */
    public boolean isFallback() {
        return fallback;
    }
}
