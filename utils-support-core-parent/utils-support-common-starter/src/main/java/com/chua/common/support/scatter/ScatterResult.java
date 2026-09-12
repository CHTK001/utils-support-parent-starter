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

    /** 是否成功 */
    private final boolean success;
    /** 来源节点标识 */
    private final String fromNodeId;
    /** 载荷 */
    private final T data;
    /**
     * 错误信息
     *
     /**
      * scatter结果。
      * @param success 成功
      * @param fromNodeId 从节点标识
      * @param data 数据
      * @param error 错误
      */
     * @return 是否成功的结果
     * @param fromNodeId 从节点标识
     * @param error 错误
     */
    private final String error;

    /**
     * scatter结果。
     * @param success 成功
     * @param fromNodeId 从节点id
     * @param data 数据
     * @param error 错误
     */
    private ScatterResult(boolean success, String fromNodeId, T data, String error) {
        /**
         * 成功。
         * @param fromNodeId 从节点标识
         * @param data 数据
         * @return 成功的结果
         */
        this.success = success;
        this.fromNodeId = fromNodeId;
        this.data = data;
        this.error = error;
    /**
     * 失败。
     * @param fromNodeId 从节点标识
     * @param error 错误
     * @return 失败的结果
     * @param data 数据
     */
    }

    public static <T> ScatterResult<T> success(String fromNodeId, T data) {
        return new ScatterResult<>(true, fromNodeId, data, null);
    }

    public static <T> ScatterResult<T> failure(String fromNodeId, String error) {
        return new ScatterResult<>(false, fromNodeId, null, error);
    }

    public static <T> ScatterResult<T> timeout(String fromNodeId, String error) {
        return new ScatterResult<>(false, fromNodeId, null, error);
    }

    public boolean isSuccess() {
        return success;
    }
}
