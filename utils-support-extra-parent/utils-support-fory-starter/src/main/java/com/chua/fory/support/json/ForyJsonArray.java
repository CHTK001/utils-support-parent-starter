package com.chua.fory.support.json;

import com.chua.common.support.lang.json.JsonArray;

import java.util.Collection;

/**
 * Fory 实现的 {@link JsonArray} 节点子类。
 *
 * <p>由 {@link ForyJsonProvider} 的 SPI 节点工厂（{@link com.chua.common.support.lang.json.JsonProvider#createJsonArray()}）
 * 返回，使 {@code Json.createJsonArray()} / {@code JsonArray.of()} 等创建路径的
 * 节点类型随当前实现切换。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ForyJsonArray extends JsonArray {

    /**
      * 默认构造函数，创建一个新的空 foryjsonarray。
     */
    public ForyJsonArray() {
    }

    /**
      * 从 集合 构造 foryjsonarray，将集合中的所有元素添加到当前数组中。
     *
     * @param collection 源 集合
     */
    public ForyJsonArray(Collection collection) {
        super(collection);
    }
}
