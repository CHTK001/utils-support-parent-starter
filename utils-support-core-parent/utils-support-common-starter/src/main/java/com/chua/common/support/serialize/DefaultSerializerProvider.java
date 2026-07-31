package com.chua.common.support.serialize;

import java.io.Serializable;

/**
 * 默认序列化提供者实现。
 *
 * @author CH
 * @since 1.0.0
 */
public class DefaultSerializerProvider implements SerializerProvider {

    @Override
    public <T extends Serializable> Serializer<T> getSerializer(Class<T> type) {
        return new JsonSerializer<>(type);
    }
}
