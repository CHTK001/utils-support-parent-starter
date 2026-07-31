package com.chua.common.support.function;

/**
 * 升级接口，用于对象配置的平滑升级
 *
 * @param <T> 升级后的类型
 * @author CH
 */
public interface Upgrade<T> {

    /**
     * 升级到新配置
     *
     * @param t 新配置
     */
    void upgrade(T t);
}
