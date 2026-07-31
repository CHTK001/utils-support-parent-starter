package com.chua.filestorage.support.storage.lanzou;

/**
 * 蓝奏云操作异常。
 *
 * <p>当蓝奏云接口返回业务失败（{@code zt != 1}）、页面结构解析失败、
 * 或网络请求异常时抛出该异常。</p>
 *
 * @author CH
 * @since 1.0
 */
public class LanzouException extends RuntimeException {

    /**
     * 构造异常。
     *
     * @param message 异常信息
     */
    public LanzouException(String message) {
        super(message);
    }

    /**
     * 构造异常。
     *
     * @param message 异常信息
     * @param cause   根因
     */
    public LanzouException(String message, Throwable cause) {
        super(message, cause);
    }
}
