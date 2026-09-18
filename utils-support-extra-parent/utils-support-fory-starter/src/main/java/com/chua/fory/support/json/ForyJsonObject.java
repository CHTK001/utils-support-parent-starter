package com.chua.fory.support.json;

import com.chua.common.support.lang.json.JsonObject;

import java.util.Map;

/**
* Fory 实现的 {@link JsonObject} 节点子类。
*
* <p>由 {@link ForyJsonProvider} 的 SPI 节点工厂（{@link com.chua.common.support.lang.json.JsonProvider#createJsonObject()}）
* 返回，使 {@code Json.createJsonObject()} / {@code JsonObject.create()} 等创建路径的
* 节点类型随当前实现切换。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class ForyJsonObject extends JsonObject {

    /**
    * 构造一个空的 foryjson对象。
    */
    public ForyJsonObject() {
    }

    /**
    * 通过指定的 映射 构造 foryjson对象。
    *
    * @param m 包含初始数据的 映射，如果为 空 则不做任何操作。
    */
    public ForyJsonObject(Map m) {
        super(m);
    }
}
