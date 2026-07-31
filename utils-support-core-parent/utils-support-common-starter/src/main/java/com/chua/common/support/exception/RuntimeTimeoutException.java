package com.chua.common.support.exception;

/**
 * 运行时超时异常。
 * <p>
 * 当业务逻辑在规定的时间内未执行完成时抛出此异常，用于标识操作已超时。
 * </p>
 *
 * @author CH
 */
public class RuntimeTimeoutException extends RuntimeException {

    public RuntimeTimeoutException() {
        super("请求处理超时");
    }

    public RuntimeTimeoutException(String message) {
        super(message);
    }

    public RuntimeTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }

    public RuntimeTimeoutException(Throwable cause) {
        super(cause);
    }
}
