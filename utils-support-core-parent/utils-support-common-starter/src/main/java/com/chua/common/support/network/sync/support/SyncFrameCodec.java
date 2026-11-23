package com.chua.common.support.network.sync.support;

/**
 * 同步协议帧编解码工具。
 *
 * <p>同步协议在线路上统一使用 {@code topic:payload} 文本帧（首个冒号切分）。
 * 各传输实现（UDP 数据报 / TCP 行帧等）共用同一切分规则，集中在此避免
 * 每个实现各写一份「indexOf + substring」。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public final class SyncFrameCodec {

    /**
     * 注册帧主题。
     */
    public static final String TOPIC_REGISTER = "register";

    /**
     * 注册确认帧主题。
     */
    public static final String TOPIC_REGISTERED = "registered";

    private SyncFrameCodec() {
    }

    /**
     * 编码一帧消息。
     *
     * @param topic   主题
     * @param message 消息内容
     * @return 线路帧文本
     */
    public static String encode(String topic, Object message) {
        return topic + ":" + message;
    }

    /**
     * 解码帧主题（首个冒号之前的部分）。
     *
     * @param frame        线路帧文本
     * @param noColonValue 无冒号时的主题取值（各实现约定不同，由调用方传入）
     * @return 主题
     */
    public static String topicOf(String frame, String noColonValue) {
        int colon = frame.indexOf(':');
        return colon > 0 ? frame.substring(0, colon) : noColonValue;
    }

    /**
     * 解码帧载荷（首个冒号之后的部分，无冒号时为整帧）。
     *
     * @param frame 线路帧文本
     * @return 载荷
     */
    public static String payloadOf(String frame) {
        int colon = frame.indexOf(':');
        return colon > 0 ? frame.substring(colon + 1) : frame;
    }
}
