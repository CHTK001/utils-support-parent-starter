package com.chua.common.support.concurrent.pool;

import org.jspecify.annotations.NullUnmarked;

/**
 * 对象池借出超时异常
 *
 * <p>当 borrow() 等待可用对象超过配置的超时时间时抛出。
 *
 * @author CH
 * @since 2026/07/16
 */
@NullUnmarked
public class PoolTimeoutException extends RuntimeException {

    public PoolTimeoutException(String message) {
        super(message);
    }

    public PoolTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
