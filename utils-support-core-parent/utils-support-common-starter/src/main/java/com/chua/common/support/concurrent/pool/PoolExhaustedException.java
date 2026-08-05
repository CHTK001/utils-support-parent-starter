package com.chua.common.support.concurrent.pool;


/**
 * 对象池耗尽异常。
 * <p>当对象池中所有可用资源均被借出，且当前配置不允许创建新实例（或创建失败）时抛出此异常。
 * 通常发生在高并发场景下，请求获取资源的速率超过了资源释放或创建的速率。
 *
 * @author CH
 * @since 2026/07/16
 */
public class PoolExhaustedException extends RuntimeException {

    public PoolExhaustedException(String message) {
        super(message);
    }

    public PoolExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}
