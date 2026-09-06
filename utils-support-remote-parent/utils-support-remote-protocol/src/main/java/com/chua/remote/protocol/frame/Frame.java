package com.chua.remote.protocol.frame;

import com.chua.common.support.serialize.JsonSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 远控消息帧。
 *
 * <p>所有经过网关传输的消息都封装为 Frame，包含消息类型、会话标识和载荷。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Frame implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 消息类型 */
    private MessageType type;

    /** 会话标识 */
    private String sessionId;

    /** 载荷（JSON 文本或二进制序列化数据） */
    private byte[] payload;

    /** 附加元数据 */
    private Map<String, String> metadata;

    /**
     * 将载荷反序列化为指定类型的对象。
     *
     * @param clazz 目标类型
     * @param <T>   泛型
     * @return 反序列化后的对象
     */
    public <T> T payloadAs(Class<T> clazz) {
        if (payload == null || payload.length == 0) {
            return null;
        }
        return new JsonSerializer<>(clazz).deserialize(payload);
    }
}
