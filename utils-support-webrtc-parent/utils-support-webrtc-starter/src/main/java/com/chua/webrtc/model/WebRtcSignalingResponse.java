package com.chua.webrtc.model;

/**
 * 出站信令响应：Java 服务端发送到浏览器的 SDP / ICE 响应。
 *
 * @author CH
 * @since 4.0.0.50
 */
public interface WebRtcSignalingResponse {

    /**
     * 响应类型。
     *
     * @return 响应类型
     */
    String type();

    /**
     * 获取响应载荷。
     *
     * @return 载荷
     */
    String payload();

    /**
     * 将响应序列化为 JSON。
     *
     * @return JSON 字符串
     */
    String toJson();

    /**
     * 创建信令响应。
     *
     * @param type    类型
     * @param payload 载荷
     * @return 信令响应
     */
    static WebRtcSignalingResponse of(String type, String payload) {
        return new DefaultWebRtcSignalingResponse(type, payload);
    }

    /**
     * 响应类型常量。
     */
    interface Type {
        /** 信令确认 */
        String OK = "ok";
        String OFFER = "offer";
        String ANSWER = "answer";
        /** 友情提示：会话停止/连接失败 */
        String ERROR = "error";
    }
}
