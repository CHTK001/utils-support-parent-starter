package com.chua.common.support.concurrent.pool;


/**
 * 对象池守卫（支持 try-with-resources 自动归还）
 *
 * <p>包装借出的对象，在 close() 时自动归还到池中，
 * 避免忘记归还导致对象泄漏。
 *
 * <p>使用示例：
 * <pre>{@code
 *   try (var guard = pool.guard()) {
 *       Connection conn = guard.get();
 *       conn.query("SELECT ...");
 *   }  // 自动调用 pool.returnObject(conn)
 * }</pre>
 *
 * @param <T> 池化对象类型
 * @author CH
 * @since 2026/07/16
 */
public class PoolGuard<T> implements AutoCloseable {

    /**
     * 连接池
     */
    private final ObjectPool<T> pool;
    /** 返回对象 */
    private final T object;
    /** 是否已归还 */
    private boolean returned;

    /**
     * 构造方法，创建 PoolGuard 实例。
     *
     * @param pool 方法入参 pool
     * @param object 对象，不允许为 null
     */
    PoolGuard(ObjectPool<T> pool, T object) {
        this.pool = pool;
        this.object = object;
        this.returned = false;
    }

    /**
     * 获取借出的对象
     *
     * @return 池化对象
     */
    public T get() {
        return object;
    }

    /**
     * 手动归还对象
     *
     * <p>调用后 close() 不会再重复归还。
     */
    public void release() {
        if (!returned) {
            returned = true;
            pool.returnObject(object);
        }
    }

    /**
     * 自动归还对象
     *
     * <p>try-with-resources 块结束时自动调用。
     */
    @Override
    public void close() {
        release();
    }
}
