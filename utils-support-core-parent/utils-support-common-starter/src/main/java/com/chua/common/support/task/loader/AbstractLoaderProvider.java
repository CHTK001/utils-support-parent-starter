package com.chua.common.support.task.loader;


/**
 * 加载器抽象基类，提供线程安全的单例懒加载实现。
 *
 * <p>使用双重检查锁定（Double-Checked Locking）保证线程安全，
 * 子类只需实现 {@link #create()} 方法提供实例创建逻辑。
 * </p>
 *
 * @param <T> 被加载的对象类型
 * @author CH
 * @since 2026/07/18
 */
public abstract class AbstractLoaderProvider<T> implements Loader<T> {

    /** 缓存的实例，volatile 保证双重检查锁定的可见性 */
    private volatile T instance;

    /**
    * 获取实例：首次调用触发懒加载，后续调用直接返回缓存。
    */
    @Override
    public T get() {
        T result = instance;
        if (result == null) {
            synchronized (this) {
                result = instance;
                if (result == null) {
                    result = create();
                    instance = result;
                }
            }
        }
        return result;
    }

    /**
     * 重置缓存实例：下次 {@link #get()} 将重新创建。
     */
    @Override
    public void reset() {
        synchronized (this) {
            instance = null;
        }
    }

    /**
     * 判断实例是否已加载。
     */
    @Override
    public boolean isLoaded() {
        return instance != null;
    }

    /**
     * 创建要加载的实例。
     *
     * <p>由子类实现具体创建逻辑，仅在首次调用 {@link #get()} 时执行一次。</p>
     *
     * @return 新创建的实例
     */
    protected abstract T create();
}
