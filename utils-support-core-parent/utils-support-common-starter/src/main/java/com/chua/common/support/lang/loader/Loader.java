package com.chua.common.support.lang.loader;

/**
 * 加载器
 *
 * @author CH
 */
public interface Loader<T> {
    /**
     * 获取加载的对象 <br>
     * 执行加载逻辑并返回目标实例
     *
     * @return 加载的对象
     */
    T get();
}
