package com.chua.common.support.serialize;

import java.io.Serializable;
import org.jspecify.annotations.NullUnmarked;

/**
 * 默认序列化提供者实现。
 *
 * @author CH
 * @since 1.0.0
 */
@NullUnmarked
public class DefaultSerializerProvider implements SerializerProvider {

    @Override
    public <T extends Serializable> Serializer<T> getSerializer(Class<T> type) {
        return new JsonSerializer<>(type);
    }
}
