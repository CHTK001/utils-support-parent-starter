package com.chua.common.support.exception;


/**
* 运行时超时异常。
* <p>
* 当业务逻辑在规定的时间内未执行完成时抛出此异常，用于标识操作已超时。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public class RuntimeTimeoutException extends RuntimeException {

    /** 创建 RuntimeTimeoutException 实例 */
    public RuntimeTimeoutException() {
        super("请求处理超时");
    }

    /**
    * 创建 RuntimeTimeoutException 实例
    * @param message message
    */
    public RuntimeTimeoutException(String message) {
        super(message);
    }

    /**
    * 创建 RuntimeTimeoutException 实例
    * @param message message
    * @param Throwable Throwable
    * @param cause 方法入参 cause
    */
    public RuntimeTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
    * 创建 RuntimeTimeoutException 实例
    * @param cause cause
    */
    public RuntimeTimeoutException(Throwable cause) {
        super(cause);
    }
}
