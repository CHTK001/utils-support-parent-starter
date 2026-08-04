package com.chua.common.support.task.loader;

import org.jspecify.annotations.NullUnmarked;

/**
 * 加载器接口，提供单例懒加载对象的获取和管理能力。
 *
 * <p>通过 {@link #get()} 延迟初始化并缓存实例，后续调用返回同一实例。
 * 支持手动重置 {@link #reset()} 清除缓存，下次调用 {@link #get()} 时重新创建。
 * </p>
 *
 * <pre>{@code
 * // 使用示例
 * Loader<DataSource> loader = new SingletonLoader<>(() -> createDataSource());
 * DataSource ds = loader.get();  // 首次调用时创建
 * DataSource same = loader.get(); // 返回缓存实例
 * loader.reset();                 // 清除缓存
 * }</pre>
 *
 * @param <T> 被加载的对象类型
 * @author CH
 * @since 2026/07/18
 */
@NullUnmarked
public interface Loader<T> {

    /**
     * 获取加载的实例（懒加载，单例）。
     *
     * <p>首次调用时创建并缓存实例，后续调用直接返回缓存。</p>
     *
     * @return 加载的实例
     */
    T get();

    /**
     * 重置加载器，清除缓存的实例。
     *
     * <p>下次调用 {@link #get()} 时会重新创建。</p>
     */
    void reset();

    /**
     * 判断是否已加载（缓存中是否有实例）。
     *
     * @return true 表示已加载，false 表示尚未加载
     */
    boolean isLoaded();
}
