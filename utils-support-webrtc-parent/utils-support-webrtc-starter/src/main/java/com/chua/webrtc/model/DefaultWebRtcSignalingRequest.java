package com.chua.webrtc.model;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonObject;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 信令请求默认实现。
 *
 * <p>浏览器通过 WebSocket 发来的帧会被反序列化为本对象，
 * 因此字段使用 {@link JsonProperty} 显式绑定（本类只有接口式访问器，
 * 无 JavaBean 命名 getter/setter，Jackson 无法自动识别私有字段）。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public class DefaultWebRtcSignalingRequest implements WebRtcSignalingRequest {

    /**
     * 请求类型，对应 JSON 根节点 type
     */
    @JsonProperty("type")
    private String type;

    /**
     * 请求载荷，对应 JSON 根节点 payload
     */
    @JsonProperty("payload")
    private String payload;

    /**
     * 默认无参数构造函数（反射友好）
     */
    public DefaultWebRtcSignalingRequest() {
        this(null, null);
    }

    /**
     * 参数化构造函数。
     *
     * @param type    类型
     * @param payload 载荷
     */
    public DefaultWebRtcSignalingRequest(String type, String payload) {
        this.type = type;
        this.payload = payload;
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public String payload() {
        return payload;
    }

    @Override
    public String toJson() {
        JsonObject json = Json.createJsonObject();
        json.fluentPut("type", type);
        if (payload != null) {
            json.fluentPut("payload", payload);
        }
        return json.toJSONString();
    }
}
