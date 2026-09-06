package com.chua.remote.core.codec;

import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import com.chua.remote.protocol.model.Session;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;

/**
 * 帧编解码器。
 *
 * <p>负责 Frame 与 bytes 之间的序列化/反序列化。
 * 信令消息使用 JSON 编码，媒体数据使用二进制透传。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class FrameCodec {

    /**
     * 序列化 Frame 为字节数组。
     *
     * @param frame 帧
     * @return 字节数组
     */
    public static byte[] encode(Frame frame) {
        if (frame.getPayload() == null) {
            return new byte[0];
        }
        return frame.getPayload();
    }

    /**
     * 从字节数组反序列化 Frame。
     *
     * @param type    消息类型
     * @param sessionId 会话标识
     * @param payload 载荷
     * @return 帧
     */
    public static Frame decode(MessageType type, String sessionId, byte[] payload) {
        return Frame.builder()
                .type(type)
                .sessionId(sessionId)
                .payload(payload)
                .build();
    }

    /**
     * 将信令对象编码为帧（JSON 序列化）。
     *
     * @param type   消息类型
     * @param sessionId 会话标识
     * @param object 信令对象
     * @return 帧
     */
    public static Frame encodeSignal(MessageType type, String sessionId, Serializable object) {
        String json = com.chua.common.support.lang.json.Json.toJson(object);
        return Frame.builder()
                .type(type)
                .sessionId(sessionId)
                .payload(json.getBytes(StandardCharsets.UTF_8))
                .build();
    }

    /**
     * 解码 JSON 信令帧为指定类型对象。
     *
     * @param frame   帧
     * @param clazz   目标类型
     * @param <T>     泛型
     * @return 反序列化对象
     */
    public static <T> T decodeSignal(Frame frame, Class<T> clazz) {
        if (frame.getPayload() == null || frame.getPayload().length == 0) {
            return null;
        }
        String json = new String(frame.getPayload(), StandardCharsets.UTF_8);
        return com.chua.common.support.lang.json.Json.fromJson(json, clazz);
    }

    /**
     * 创建注册帧。
     *
     * @param sessionId 会话标识
     * @param payload   注册信息 JSON
     * @return 帧
     */
    public static Frame registerFrame(String sessionId, byte[] payload) {
        return Frame.builder()
                .type(MessageType.SIGNAL)
                .sessionId(sessionId)
                .payload(payload)
                .build();
    }

    /**
     * 创建数据帧（媒体数据）。
     *
     * @param sessionId 会话标识
     * @param payload   媒体数据
     * @return 帧
     */
    public static Frame dataFrame(String sessionId, byte[] payload) {
        return Frame.builder()
                .type(MessageType.DATA)
                .sessionId(sessionId)
                .payload(payload)
                .build();
    }

    /**
     * 创建控制帧。
     *
     * @param sessionId 会话标识
     * @param payload   控制数据
     * @return 帧
     */
    public static Frame controlFrame(String sessionId, byte[] payload) {
        return Frame.builder()
                .type(MessageType.CTRL)
                .sessionId(sessionId)
                .payload(payload)
                .build();
    }
}
