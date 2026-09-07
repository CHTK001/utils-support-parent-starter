package com.chua.remote.core.codec;

import com.chua.common.support.lang.json.Json;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 帧编解码器。
 *
 * <p>负责 Frame 与传输载体之间的序列化/反序列化。
 * 跨进程链路使用二进制定长前缀帧（{@link #writeBinary}/{@link #readBinary}）：
 * 无缓冲流、无 Base64、无字符串中转，载荷零复制；
 * 信令载荷内部仍为 JSON（小对象，仅信令使用），媒体数据全程二进制透传。</p>
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

    /** 二进制帧头固定长度：type(1) + sidLen(2) + metaLen(4) + payloadLen(4) */
    private static final int BINARY_HEADER_SIZE = 15;

    /** 二进制帧长度字段安全上限（防损坏/恶意长度） */
    private static final int MAX_BINARY_FRAME = 64 * 1024 * 1024;

    /** 二进制帧类型：信令 */
    private static final byte TYPE_SIGNAL = 0;

    /** 二进制帧类型：数据 */
    private static final byte TYPE_DATA = 1;

    /** 二进制帧类型：控制 */
    private static final byte TYPE_CTRL = 2;

    /** 空缓冲（无元数据/无载荷时复用） */
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

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

    /**
     * 将 Frame 编码为二进制帧（零拷贝）。
     *
     * <p>格式（长度前缀定界，无文本转换、无 Base64）：</p>
     * <pre>totalLen(int32) | type(1) | sidLen(int16) | metaLen(int32) | payloadLen(int32)
     * | sessionId(utf8) | meta(utf8 json) | payload(原样)</pre>
     *
     * <p>载荷以 {@link ByteBuffer#wrap} 直接包装，不复制；
     * 返回的缓冲数组可一次性 gather 写出（头部小缓冲 + 载荷缓冲）。</p>
     *
     * @param frame 帧
     * @return 可用于 scatter/gather 写的缓冲数组
     */
    public static ByteBuffer[] encodeBinary(Frame frame) {
        byte[] sessionId = frame.getSessionId() != null
                ? frame.getSessionId().getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] meta = frame.getMetadata() != null && !frame.getMetadata().isEmpty()
                ? Json.toJson(frame.getMetadata()).getBytes(StandardCharsets.UTF_8) : new byte[0];
        int payloadLen = frame.getPayload() != null ? frame.getPayload().length : 0;
        ByteBuffer head = ByteBuffer.allocate(BINARY_HEADER_SIZE + sessionId.length + meta.length);
        head.putInt(BINARY_HEADER_SIZE - 4 + sessionId.length + meta.length + payloadLen)
                .put(typeByte(frame.getType()))
                .putShort((short) sessionId.length)
                .putInt(meta.length)
                .putInt(payloadLen)
                .put(sessionId)
                .put(meta)
                .flip();
        ByteBuffer payload = payloadLen > 0 ? ByteBuffer.wrap(frame.getPayload()) : EMPTY_BUFFER;
        return new ByteBuffer[]{head, payload};
    }

    /**
     * 将二进制帧写出到底层通道。
     *
     * <p>使用 gather 写：头缓冲与载荷缓冲一次性提交，载荷不合并复制。</p>
     *
     * @param channel 输出通道
     * @param frame   帧
     * @throws IOException 写失败
     */
     public static void writeBinary(GatheringByteChannel channel, Frame frame) throws IOException {
         ByteBuffer[] buffers = encodeBinary(frame);
         long remaining = buffers[0].remaining() + buffers[1].remaining();
         while (remaining > 0) {
             long written = channel.write(buffers);
             if (written <= 0) {
                 throw new IOException("二进制帧写出停滞");
             }
             remaining -= written;
         }
     }

    /**
     * 从底层通道读取一帧二进制数据。
     *
     * <p>载荷直接读入目标字节数组，全程无 Base64/字符串/缓冲流中转。</p>
     *
     * @param channel 输入通道
     * @return 帧
     * @throws IOException 读失败或对端关闭
     */
    public static Frame readBinary(ReadableByteChannel channel) throws IOException {
        ByteBuffer head = ByteBuffer.allocate(BINARY_HEADER_SIZE);
        readFully(channel, head);
        head.flip();
        head.get();
        byte type = head.get();
        int sidLen = head.getShort() & 0xFFFF;
        int metaLen = head.getInt();
        int payloadLen = head.getInt();
        requireRange(sidLen, metaLen, payloadLen);

        byte[] sessionId = new byte[sidLen];
        readFully(channel, ByteBuffer.wrap(sessionId));
        Map<String, String> metadata = null;
        if (metaLen > 0) {
            byte[] meta = new byte[metaLen];
            readFully(channel, ByteBuffer.wrap(meta));
            metadata = Json.fromJson(new String(meta, StandardCharsets.UTF_8), Map.class);
        }
        byte[] payload = null;
        if (payloadLen > 0) {
            payload = new byte[payloadLen];
            readFully(channel, ByteBuffer.wrap(payload));
        }
        return Frame.builder()
                .type(typeOf(type))
                .sessionId(new String(sessionId, StandardCharsets.UTF_8))
                .metadata(metadata)
                .payload(payload)
                .build();
    }

    /**
     * 从完整缓冲解析一帧二进制数据（缓冲位置需位于帧头）。
     *
     * @param buffer 含完整一帧的缓冲
     * @return 帧
     */
    public static Frame parseBinary(ByteBuffer buffer) {
        buffer.getInt();
        byte type = buffer.get();
        int sidLen = buffer.getShort() & 0xFFFF;
        int metaLen = buffer.getInt();
        int payloadLen = buffer.getInt();
        requireRange(sidLen, metaLen, payloadLen);
        byte[] sessionId = new byte[sidLen];
        buffer.get(sessionId);
        Map<String, String> metadata = null;
        if (metaLen > 0) {
            byte[] meta = new byte[metaLen];
            buffer.get(meta);
            metadata = Json.fromJson(new String(meta, StandardCharsets.UTF_8), Map.class);
        }
        byte[] payload = null;
        if (payloadLen > 0) {
            payload = new byte[payloadLen];
            buffer.get(payload);
        }
        return Frame.builder()
                .type(typeOf(type))
                .sessionId(new String(sessionId, StandardCharsets.UTF_8))
                .metadata(metadata)
                .payload(payload)
                .build();
    }

    /**
     * 将缓冲内剩余数据完整读满（阻塞直至满足或对端关闭）。
     *
     * @param channel 输入通道
     * @param buffer  目标缓冲
     * @throws IOException 读失败或对端关闭
     */
    private static void readFully(ReadableByteChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0) {
                throw new IOException("对端已关闭");
            }
        }
    }

    /**
     * 校验各长度字段在安全范围内。
     *
     * @param sidLen     会话标识长度
     * @param metaLen    元数据长度
     * @param payloadLen 载荷长度
     */
    private static void requireRange(int sidLen, int metaLen, int payloadLen) {
        if (sidLen < 0 || metaLen < 0 || payloadLen < 0
                || sidLen > MAX_BINARY_FRAME || metaLen > MAX_BINARY_FRAME
                || payloadLen > MAX_BINARY_FRAME) {
            throw new IllegalArgumentException("二进制帧长度字段非法: sid=" + sidLen
                    + ", meta=" + metaLen + ", payload=" + payloadLen);
        }
    }

    /**
     * 消息类型转二进制类型字节。
     *
     * @param type 消息类型
     * @return 类型字节
     */
    private static byte typeByte(MessageType type) {
        if (type == MessageType.DATA) {
            return TYPE_DATA;
        }
        if (type == MessageType.CTRL) {
            return TYPE_CTRL;
        }
        return TYPE_SIGNAL;
    }

    /**
     * 二进制类型字节转消息类型。
     *
     * @param type 类型字节
     * @return 消息类型
     */
    private static MessageType typeOf(byte type) {
        if (type == TYPE_DATA) {
            return MessageType.DATA;
        }
        if (type == TYPE_CTRL) {
            return MessageType.CTRL;
        }
        return MessageType.SIGNAL;
    }
}
