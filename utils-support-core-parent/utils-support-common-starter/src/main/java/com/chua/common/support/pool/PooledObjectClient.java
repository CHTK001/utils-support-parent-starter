package com.chua.common.support.pool;

import com.chua.common.support.concurrent.pool.ObjectPool;

/**
 * 支持对象池化的客户端接口
 *
 * <p>为 {@code XxClient} 提供统一的池化能力, 调用方可通过
 * {@link #pool(Number)} 调整池大小, 内部维护单例或 {@link ObjectPool} 实例。
 *
 * <p>数量语义:
 * <ul>
 *   <li>{@code null} / {@code <= 1}: 单例模式, 所有调用共享同一实例 (默认)</li>
 *   <li>{@code > 1}: 池化模式, 创建指定大小的对象池, 通过 borrow/return 借还</li>
 *   <li>{@code 0}: 显式关闭池化, 每次访问创建新实例</li>
 * </ul>
 *
 * <p>典型用法:
 * <pre>{@code
 *   ImageClient client = ImageClient.create("openai", "sk-xxx");
 *
 *   // 单例模式 (默认)
 *   client.pool(null);
 *
 *   // 池化 4 个实例
 *   client.pool(4);
 *
 *   // 关闭池化
 *   client.pool(0);
 * }</pre>
 *
 * @param <T> 客户端自身类型, 用于链式调用
 * @author CH
 * @since 4.0.0.42
 */
public interface PooledObjectClient<T> {

    /**
     * 设置对象池大小
     *
     * <p>数量语义:
     * <ul>
     *   <li>{@code null} 或 {@code <= 1}: 单例模式 (默认)</li>
     *   <li>{@code > 1}: 池化模式, 创建指定大小的对象池</li>
     *   <li>{@code 0}: 关闭池化, 每次访问创建新实例</li>
     * </ul>
     *
     * @param size 池大小, 传 null 视为 1
     * @return 当前客户端实例, 支持链式调用
     */
    @SuppressWarnings("unchecked")
    default T pool(Number size) {
        configurePool(size);
        return (T) this;
    }

    /**
     * 由实现类配置池的实际行为
     *
     * <p>默认实现关闭池化 (每次访问创建新实例), 单例/池化由实现类重写此方法。
     *
     * @param size 池大小
     */
    default void configurePool(Number size) {
    }

    /**
     * 获取当前底层对象池
     *
     * <p>单例模式下返回 null (因为没有池, 只有共享实例), 池化模式下返回实际对象池。
     *
     * @return 对象池, 单例或无池化时返回 null
     */
    default ObjectPool<?> getPool() {
        return null;
    }

    /**
     * 是否启用了对象池
     *
     * <p>仅当 {@link #getPool()} 返回非 null 时返回 true。
     *
     * @return true 表示池化模式
     */
    default boolean isPooled() {
        return getPool() != null;
    }
}
