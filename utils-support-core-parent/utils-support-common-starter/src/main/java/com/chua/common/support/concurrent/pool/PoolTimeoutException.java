package com.chua.common.support.concurrent.pool;


/**
* 对象池借出超时异常
*
* <p>当 borrow() 等待可用对象超过配置的超时时间时抛出。
*
* @author CH
* @since 2026/07/16
 */
public class PoolTimeoutException extends RuntimeException {

    /**
    * 创建 PoolTimeoutException 实例
    * @param message message
    */
    public PoolTimeoutException(String message) {
        super(message);
    }

    /**
    * 创建 PoolTimeoutException 实例
    * @param message message
    * @param Throwable Throwable
    */
    public PoolTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
