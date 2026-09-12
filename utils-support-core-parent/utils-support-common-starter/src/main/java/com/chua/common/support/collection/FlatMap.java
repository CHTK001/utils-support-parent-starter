package com.chua.common.support.collection;

import com.chua.common.support.utils.BeanUtils;
import com.chua.common.support.utils.ClassUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
* 扁平化 Map 接口，继承 {@link Map}，支持将 Java 实体对象的属性平铺为键值对存储，
* 并提供基于通配符（*、?）的键值查找功能。
* <p>
* 主要特性：
* <ul>
*   <li><b>实体对象展平</b>：通过 {@link #put(Object)} 方法将 Java 对象的属性自动展平存储</li>
*   <li><b>通配符查找</b>：支持 *（匹配任意字符序列）和 ?（匹配单个字符）通配符进行键匹配</li>
*   <li><b>类型转换</b>：支持将通配符匹配结果自动转换为指定类型</li>
* </ul>
* </p>
* <p>
* 通过 {@link #create()} / {@link #create(Object)} / {@link #create(Map)} 工厂方法创建实例。
* </p>
*
* @author CH
* @since 4.0.0.42
* @version 1.0.0
 */
public interface FlatMap extends Map<String, Object> {
    
    /**
    * 将实体对象的属性通过 {@link BeanUtils} 展平后放入当前 Map 中。
    * <p>实体对象的每个属性会被转换为一个键值对，键名为属性路径（如 "user.name"）。</p>
    *
    * @param entity 实体对象
     */
    void put(Object entity);

    /**
    * 根据通配符键获取匹配的值列表。
    * <p>支持以下通配符模式：</p>
    * <ul>
    *   <li><b>*</b> — 匹配任意字符序列（含空序列）</li>
    *   <li><b>?</b> — 匹配单个字符</li>
    * </ul>
    *
    * @param key 通配符键，如 "user.*"、"data?.name"
    * @return 匹配的值列表，如果没有匹配则返回空列表
     */
    List<Object> wildcard(String key);

    /**
    * 根据通配符键获取匹配的值列表，并将每个值转换为指定类型。
    * <p>
    * 使用 {@link ClassUtils#forObject(Class)} 创建目标类型实例后，通过 {@link BeanUtils#copyProperties(Object, Object)} 复制属性。
    * </p>
    *
    * @param key  通配符键
    * @param type 目标类型
    * @param <R>  泛型类型
    * @return 转换后的值列表
     */
    default <R> List<R> wildcard(String key, Class<R> type) {
        List<Object> read = wildcard(key);
        List<R> result = new ArrayList<>(read.size());
        for (Object o : read) {
            R forObject = ClassUtils.forObject(type);
            if (null == forObject) {
                break;
            }
            BeanUtils.copyProperties(o, forObject);
            result.add(forObject);
        }
        return result;
    }

    /**
    * 创建一个空的 FlatMap 实例。
    *
    * @return 空的 FlatMap 实例
     */
    static FlatMap create() {
        return new FlatHashMap();
    }

    /**
    * 根据实体对象创建 FlatMap 实例（自动将对象属性展平为键值对）。
    *
    * @param entity 实体对象
    * @return 包含实体对象属性的 FlatMap 实例
     */
    static FlatMap create(Object entity) {
        return new FlatHashMap(BeanUtils.objectToMap(entity));
    }

    /**
    * 根据现有 Map 创建 FlatMap 实例（自动展平嵌套结构）。
    *
    * @param map 现有 Map，可能包含嵌套结构
    * @return 展平后的 FlatMap 实例
     */
    static FlatMap create(Map<String, Object> map) {
        return new FlatHashMap(map);
    }
}
