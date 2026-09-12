package com.chua.common.support.lang.loader;


/**
* 线程安全的懒加载器抽象类。
* <p>
* 基于双重检查锁定（Double-Checked Locking）机制实现，确保在多线程环境下
* 目标对象只会被初始化一次，且后续获取操作无锁，性能高效。
* </p>
*
* @param <T> 加载对象的类型
* @author CH
* @since 4.0.0.42
 */
public abstract class LazyLoader<T> implements InitLoader<T>, Loader<T> {

    /**
    * 缓存的目标对象实例。
    * <p>使用 volatile 关键字修饰，确保多线程环境下的可见性并防止指令重排序。</p>
     */
    private volatile T object;

    /**
    * 获取目标对象实例。
    * <p>
    * 采用双重检查锁定（DCL）模式：
    * 1. 第一次检查：如果对象已初始化，直接返回，避免不必要的同步开销。
    * 2. 同步块：确保同一时刻只有一个线程进入初始化逻辑。
    * 3. 第二次检查：防止多个线程同时通过第一次检查后重复初始化。
    * </p>
    *
    * @return 初始化后的目标对象实例
     */
    @Override
    public T get() {
        T result = object;
        if (result == null) {
            synchronized (this) {
                result = object;
                if (result == null) {
                    object = result = init();
                }
            }
        }
        return result;
    }
}
