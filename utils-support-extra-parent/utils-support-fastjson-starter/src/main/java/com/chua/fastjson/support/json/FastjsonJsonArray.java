package com.chua.fastjson.support.json;

import com.chua.common.support.lang.json.JsonArray;

import java.util.Collection;

/**
 * Fastjson 实现的 {@link JsonArray} 节点子类。
 *
 * <p>由 {@link FastjsonJsonProvider} 的 SPI 节点工厂（{@link com.chua.common.support.lang.json.JsonProvider#createJsonArray()}）
 * 返回，使 {@code Json.createJsonArray()} / {@code JsonArray.of()} 等创建路径的
 * 节点类型随当前实现切换。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FastjsonJsonArray extends JsonArray {

    /**
      * 默认构造函数，创建一个新的空 fastjsonjsonarray。
     */
    public FastjsonJsonArray() {
    }

    /**
      * 从 集合 构造 fastjsonjsonarray，将集合中的所有元素添加到当前数组中。
     *
     * @param collection 源 集合
     */
    public FastjsonJsonArray(Collection collection) {
        super(collection);
    }
}
