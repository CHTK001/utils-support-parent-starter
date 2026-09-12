package com.chua.common.support.objects.provider;

import java.util.function.Supplier;

/**
* 对象提供者接口。
*
* <p>类似于 Spring 的 ObjectProvider，提供延迟获取 Bean 的能力。
* 用于在注入点延迟获取依赖，或在依赖不存在时提供默认值。</p>
*
* <p>主要方法：
* <ul>
*   <li>{@link #getObject()} — 获取 Bean 实例，不存在时返回 null</li>
*   <li>{@link #getIfAvailable(Supplier)} — 获取 Bean，不存在时返回默认值</li>
*   <li>{@link #getIfUnique()} — 获取唯一的 Bean</li>
* </ul></p>
*
* @param <T> 对象类型泛型
* @author CH
* @since 2024/12/20
 */
public interface ObjectProvider<T> {

    /**
    * 获取对象实例。
    *
    * @return 对象实例，不存在时返回 空
     */
    T getObject();

    /**
    * 获取对象实例，不存在时返回默认值。
    *
    * @param def 默认值提供者
    * @return 对象实例或默认值
     */
    default T getIfAvailable(Supplier<T> def) {
        T o = getObject();
        return o != null ? o : (def != null ? def.get() : null);
    }

    /**
    * 获取唯一的对象实例。
    *
    * @return 唯一实例
     */
    default T getIfUnique() {
        return getObject();
    }

    /**
    * 获取唯一的对象实例，不存在时返回默认值。
    *
    * @param def 默认值提供者
    * @return 唯一实例或默认值
     */
    default T getIfUnique(Supplier<T> def) {
        T o = getIfUnique();
        return o != null ? o : (def != null ? def.get() : null);
    }
}