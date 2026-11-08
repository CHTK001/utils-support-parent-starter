package com.chua.webrtc.model;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonObject;

/**
 * 信令响应默认实现。
 *
 * @author CH
 * @since 4.0.0.50
 */
public class DefaultWebRtcSignalingResponse implements WebRtcSignalingResponse {

    /**
     * 默认无参数构造函数（反射友好）
     */
    public DefaultWebRtcSignalingResponse() {
        this(null, null);
    }

    /**
     * 参数化构造函数。
     *
     * @param type    类型
     * @param payload 载荷
     */
    public DefaultWebRtcSignalingResponse(String type, String payload) {
        this.type = type;
        this.payload = payload;
    }

    protected String type;

    protected String payload;

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
