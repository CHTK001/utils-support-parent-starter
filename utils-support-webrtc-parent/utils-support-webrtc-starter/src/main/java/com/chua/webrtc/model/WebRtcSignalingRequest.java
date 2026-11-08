package com.chua.webrtc.model;

/**
 * 入站信令请求：浏览器通过 WebSocket 发送给 Java 服务端。
 *
 * <p>信令类型：offer / answer / ice-candidate。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public interface WebRtcSignalingRequest {

    /**
     * 请求类型：offer / answer / ice-candidate。
     *
     * @return 请求类型
     */
    String type();

    /**
     * 获取请求载荷（SDP 描述 / ICE 候选）。
     *
     * @return 载荷
     */
    String payload();

    /**
     * 将请求序列化为 JSON。
     *
     * @return JSON 字符串
     */
    String toJson();

    /**
     * 将 JSON 文本反序列化为信令请求。
     *
     * @param json JSON 文本
     * @return 信令请求
     */
    static WebRtcSignalingRequest of(String json) {
        return com.chua.common.support.lang.json.Json.fromJson(json, com.chua.webrtc.model.DefaultWebRtcSignalingRequest.class);
    }

    /**
     * 请求类型常量。
     */
    interface Type {
        public static final String OFFER = "offer";
        public static final String ANSWER = "answer";
        public static final String CANDIDATE = "ice-candidate";
    }
}
