package com.chua.remote.core.codec;

import com.chua.common.support.lang.json.Json;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 帧编解码器。
 *
 * <p>负责 Frame 与传输载体之间的序列化/反序列化：
 * 信令载荷使用 JSON 编码，媒体数据使用二进制透传，
 * 跨进程线格式统一为 JSON（二进制载荷 Base64 承载）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class FrameCodec {

    /** 元数据键：载荷对象类型标识（用于网关信令分发） */
    public static final String METADATA_KIND = "kind";

    /** 线格式键：消息类型 */
    private static final String KEY_TYPE = "type";

    /** 线格式键：会话标识 */
    private static final String KEY_SESSION_ID = "sessionId";

    /** 线格式键：载荷（Base64） */
    private static final String KEY_PAYLOAD = "payload";

    /** 线格式键：附加元数据 */
    private static final String KEY_METADATA = "metadata";

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
        // 显式拷贝：数据传输全程不使用零拷贝共享缓冲（约束：所有数据不许 zero copy）
        byte[] payload = frame.getPayload();
        return java.util.Arrays.copyOf(payload, payload.length);
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
     * 将 Frame 编码为线格式字符串。
     *
     * <p>底层传输层以字符串为载体，二进制载荷若直接按字符集转换会损坏，
     * 因此线格式统一为 JSON，载荷以 Base64 承载。</p>
     *
     * @param frame 帧
     * @return 线格式字符串
     */
    public static String encodeWire(Frame frame) {
        Map<String, Object> wire = new LinkedHashMap<>(4);
        wire.put(KEY_TYPE, frame.getType() != null ? frame.getType().name() : MessageType.SIGNAL.name());
        wire.put(KEY_SESSION_ID, frame.getSessionId());
        wire.put(KEY_PAYLOAD, frame.getPayload() != null
                ? Base64.getEncoder().encodeToString(frame.getPayload()) : "");
        if (frame.getMetadata() != null && !frame.getMetadata().isEmpty()) {
            wire.put(KEY_METADATA, frame.getMetadata());
        }
        return Json.toJson(wire);
    }

    /**
     * 从线格式字符串解码 Frame。
     *
     * @param wire 线格式字符串
     * @return 帧，格式非法时返回 null
     */
    @SuppressWarnings("unchecked")
    public static Frame decodeWire(String wire) {
        if (wire == null || wire.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = Json.fromJson(wire, Map.class);
            if (map == null || map.get(KEY_TYPE) == null) {
                return null;
            }
            Object payloadText = map.get(KEY_PAYLOAD);
            byte[] payload = payloadText != null && !String.valueOf(payloadText).isEmpty()
                    ? Base64.getDecoder().decode(String.valueOf(payloadText)) : null;
            return Frame.builder()
                    .type(MessageType.valueOf(String.valueOf(map.get(KEY_TYPE))))
                    .sessionId((String) map.get(KEY_SESSION_ID))
                    .payload(payload)
                    .metadata((Map<String, String>) map.get(KEY_METADATA))
                    .build();
        } catch (Exception e) {
            log.debug("线格式解码失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将信令对象编码为帧（JSON 序列化）。
     *
     * <p>帧元数据携带载荷对象类型标识，供网关信令分发使用。</p>
     *
     * @param type   消息类型
     * @param sessionId 会话标识
     * @param object 信令对象
     * @return 帧
     */
    public static Frame encodeSignal(MessageType type, String sessionId, Serializable object) {
        String json = Json.toJson(object);
        Map<String, String> metadata = new LinkedHashMap<>(2);
        metadata.put(METADATA_KIND, object.getClass().getSimpleName());
        return Frame.builder()
                .type(type)
                .sessionId(sessionId)
                .payload(json.getBytes(StandardCharsets.UTF_8))
                .metadata(metadata)
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
        return Json.fromJson(json, clazz);
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
