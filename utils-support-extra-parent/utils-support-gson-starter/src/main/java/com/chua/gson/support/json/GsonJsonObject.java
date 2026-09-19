package com.chua.gson.support.json;

import com.chua.common.support.lang.json.JsonObject;

import java.util.Map;

/**
 * Gson 实现的 {@link JsonObject} 节点子类。
 *
 * <p>由 {@link GsonJsonProvider} 的 SPI 节点工厂（{@link com.chua.common.support.lang.json.JsonProvider#createJsonObject()}）
 * 返回，使 {@code Json.createJsonObject()} / {@code JsonObject.create()} 等创建路径的
 * 节点类型随当前实现切换。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class GsonJsonObject extends JsonObject {

    /**
     * 构造一个空的 gsonjson对象。
     */
    public GsonJsonObject() {
    }

    /**
     * 通过指定的 映射 构造 gsonjson对象。
     *
     * @param m 包含初始数据的 映射，如果为 空 则不做任何操作。
     */
    public GsonJsonObject(Map m) {
        super(m);
    }
}
