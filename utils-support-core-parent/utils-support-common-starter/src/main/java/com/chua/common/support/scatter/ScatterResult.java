package com.chua.common.support.scatter;

import lombok.Data;

/**
 * scatter 调用结果。
 *
 * @param <T> 载荷类型
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class ScatterResult<T> {

    /**
     * 是否成功
    */
    private final boolean success;
    /**
     * 来源节点标识
    */
    private final String fromNodeId;
    /**
     * 载荷
    */
    private final T data;
    /**
     * 错误信息
    */
    private final String error;

    /**
     * 构造方法，创建 Scatter结果 实例。
     *
     * @param success success（布尔开关）
     * @param fromNodeId 来自节点ID，不允许为 null
     * @param data 数据，不允许为 null
     * @param error 方法入参 error
     */
    private ScatterResult(boolean success, String fromNodeId, T data, String error) {
        this.success = success;
        this.fromNodeId = fromNodeId;
        this.data = data;
        this.error = error;
    }

    /**
     * success。
     *
     * @param fromNodeId 来自节点ID，不允许为 null
     * @param data 数据，不允许为 null
     * @return Scatter结果 对象
     */
    public static <T> ScatterResult<T> success(String fromNodeId, T data) {
        return new ScatterResult<>(true, fromNodeId, data, null);
    }

    /**
     * failure。
     *
     * @param fromNodeId 来自节点ID，不允许为 null
     * @param error 方法入参 error
     * @return Scatter结果 对象
     */
    public static <T> ScatterResult<T> failure(String fromNodeId, String error) {
        return new ScatterResult<>(false, fromNodeId, null, error);
    }

    /**
     * 超时时间。
     *
     * @param fromNodeId 来自节点ID，不允许为 null
     * @param error 方法入参 error
     * @return Scatter结果 对象
     */
    public static <T> ScatterResult<T> timeout(String fromNodeId, String error) {
        return new ScatterResult<>(false, fromNodeId, null, error);
    }

    /**
     * 是否Success。
     *
     * @return 是否成功（true 表示成功）
     */
    public boolean isSuccess() {
        return success;
    }
}
