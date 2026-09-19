package com.chua.common.support.exception;


/**
 * 启动失败异常。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class StartFailedException extends RuntimeException {

    /**
     * 构造方法。
     *
     * @param message 异常消息
     */
    public StartFailedException(String message) {
        super(message);
    }

    /**
     * 构造方法。
     *
     * @param message 异常消息
     * @param cause   原始异常
     */
    public StartFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
