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
    /** 错误信息 */
    private final String error;

    private ScatterResult(boolean success, String fromNodeId, T data, String error) {
        this.success = success;
        this.fromNodeId = fromNodeId;
        this.data = data;
        this.error = error;
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
