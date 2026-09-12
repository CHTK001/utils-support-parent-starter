package com.chua.common.support.storage.result;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * 对象操作结果基类。
 *
 * <p>包含所有操作结果共有的属性：请求标记、状态码和消息。</p>
 *
 * @author CH
 * @since 1.0
 */
@Getter
@Setter
@SuperBuilder
public class ObjectResult {

    /**
      * 请求标记（记号笔），用于分页或追踪。
     */
    private String marker;

    /**
     * 操作结果状态码。
     */
    private ResultCode resultCode;

    /**
     * 操作结果消息。
     */
    private String message;

    /**
     * 操作结果状态码枚举。
     * @author CH
     * @since 4.0.0
     */
    public enum ResultCode {
        /**
         * 成功
         */
        SUCCESS,
        /**
         * 失败
         */
        FAILURE,
        /**
         * 部分成功
         */
        PARTIAL
    }
}
