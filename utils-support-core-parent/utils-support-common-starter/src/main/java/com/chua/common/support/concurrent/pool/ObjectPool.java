package com.chua.common.support.concurrent.pool;

import org.jspecify.annotations.NullUnmarked;

/**
 * 对象池接口
 *
 * <p>定义对象池的标准操作：借出、归还、销毁和状态查询。
 * 默认实现为 {@link GenericObjectPool}。
 *
 * <p>典型使用流程：
 * <pre>{@code
 *   ObjectPool<Connection> pool = new GenericObjectPool<>(config, connectionFactory);
 *   Connection conn = pool.borrow();
 *   try {
 *       // 使用对象
 *       conn.query("SELECT ...");
 *   } finally {
 *       pool.returnObject(conn);  // 归还到池中
 *   }
 * }</pre>
 *
 * <p>也支持 try-with-resources 自动归还：
 * <pre>{@code
 *   try (var guard = pool.borrowGuard()) {
 *       Connection conn = guard.object();
 *       conn.query("SELECT ...");
 *   }  // 自动归还
 * }</pre>
 *
 * @param <T> 池化对象类型
 * @author CH
 * @since 2026/07/16
 */
@NullUnmarked
public interface ObjectPool<T> extends AutoCloseable {

    /**
     * 从池中借出一个对象
     *
     * <p>若池中有空闲对象且通过验证，直接返回；否则创建新对象。
     * 达到最大容量时阻塞等待，超时抛出 {@link PoolTimeoutException}。
     *
     * @return 可用的对象
     * @throws PoolTimeoutException  等待超时
     * @throws PoolExhaustedException 池已耗尽
     * @throws Exception              创建对象失败
     */
    T borrow() throws Exception;

    /**
     * 将对象归还到池中
     *
     * <p>归还前会验证对象有效性（取决于配置）。无效对象直接销毁。
     *
     * @param object 要归还的对象
     */
    void returnObject(T object);

    /**
     * 销毁对象（不归还池中）
     *
     * <p>当对象确认不可用时调用，释放资源后从池中移除。
     *
     * @param object 要销毁的对象
     */
    void invalidateObject(T object);

    /**
     * 获取池中空闲对象数
     *
     * @return 空闲对象数量
     */
    int getNumIdle();

    /**
     * 获取已借出对象数
     *
     * @return 已借出对象数量
     */
    int getNumActive();

    /**
     * 清空池中所有空闲对象
     */
    void clear();

    /**
     * 获取对象池守卫（支持 try-with-resources 自动归还）
     *
     * <p>使用示例：
     * <pre>{@code
     *   try (var guard = pool.guard()) {
     *       T obj = guard.get();
     *       // 使用对象...
     *   }  // 自动调用 returnObject
     * }</pre>
     *
     * @return 对象池守卫
     * @throws Exception 借出失败
     */
    default PoolGuard<T> guard() throws Exception {
        return new PoolGuard<>(this, borrow());
    }

    @Override
    default void close() {
        clear();
    }
}
