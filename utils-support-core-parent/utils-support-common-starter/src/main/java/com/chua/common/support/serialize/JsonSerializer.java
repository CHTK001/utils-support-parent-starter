package com.chua.common.support.serialize;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.spi.annotations.Spi;

import java.io.Serializable;
import java.lang.reflect.Type;
import org.jspecify.annotations.NullUnmarked;

/**
 * JSON序列化实现，基于Jackson实现。
 *
 * @author CH
 * @since 1.0.0
 */
@NullUnmarked
@Spi("json")
public class JsonSerializer<T extends Serializable> implements Serializer<T> {

    /**
     * 类型
     */
    private final Type type;

    public JsonSerializer(Class<T> clazz) {
        this.type = clazz;
    }

    public JsonSerializer(Type type) {
        this.type = type;
    }

    @Override
    public byte[] serialize(T object) {
        return Json.toJsonByte(object);
    }

    @Override
    @SuppressWarnings("unchecked")
    public T deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return Json.fromJson(bytes, (Class<T>) type);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
