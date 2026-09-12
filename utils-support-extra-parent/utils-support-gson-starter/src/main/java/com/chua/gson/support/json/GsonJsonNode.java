package com.chua.gson.support.json;

import com.chua.common.support.lang.json.JsonNode;

/**
 * Gson 实现的 {@link JsonNode} 节点子类。
 *
 * <p>由 {@link GsonJsonProvider} 的 SPI 节点工厂（{@link com.chua.common.support.lang.json.JsonProvider#createJsonNode(Object)}）
 * 返回，使 {@code Json.createJsonNode(value)} / {@code JsonNode.valueOf(value)} 等创建路径的
 * 节点类型随当前实现切换。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class GsonJsonNode extends JsonNode {

    /**
      * 使用原始值构造 gsonjson节点。
     *
     * @param value 原始 JSON 值
     */
    public GsonJsonNode(Object value) {
        super(value);
    }
}
