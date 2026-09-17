package com.chua.common.support.exception;


/**
* 远程执行异常，用于封装 RPC/HTTP/远程调用失败时抛出的运行时异常。
*
* <p>常用于以下场景：</p>
* <ul>
*     <li>RPC 调用失败时抛出</li>
*     <li>HTTP 远程调用失败时抛出</li>
*     <li>其他远程调用场景的异常封装</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
 */
public class RemoteExecutionException extends RuntimeException {

    /**
    * 默认构造方法。
    */
    public RemoteExecutionException() {
        super();
    }

    /**
    * 构造方法。
    *
    * @param message 异常消息
    */
    public RemoteExecutionException(String message) {
        super(message);
    }

    /**
    * 构造方法。
    *
    * @param message 异常消息
    * @param cause   原始异常
    */
    public RemoteExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
    * 构造方法。
    *
    * @param cause 原始异常
    */
    public RemoteExecutionException(Throwable cause) {
        super(cause);
    }
}
