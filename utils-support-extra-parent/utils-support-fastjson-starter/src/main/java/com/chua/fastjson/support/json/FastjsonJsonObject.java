package com.chua.fastjson.support.json;

import com.chua.common.support.lang.json.JsonObject;

import java.util.Map;

/**
 * Fastjson 实现的 {@link JsonObject} 节点子类。
 *
 * <p>由 {@link FastjsonJsonProvider} 的 SPI 节点工厂（{@link com.chua.common.support.lang.json.JsonProvider#createJsonObject()}）
 * 返回，使 {@code Json.createJsonObject()} / {@code JsonObject.create()} 等创建路径的
 * 节点类型随当前实现切换。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FastjsonJsonObject extends JsonObject {

    /**
     * 构造一个空的 FastjsonJsonObject。
     */
    public FastjsonJsonObject() {
    }

    /**
     * 通过指定的 Map 构造 FastjsonJsonObject。
     *
     * @param m 包含初始数据的 Map，如果为 null 则不做任何操作。
     */
    public FastjsonJsonObject(Map m) {
        super(m);
    }
}
