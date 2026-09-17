package com.chua.common.support.serialize;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.spi.annotations.Spi;

import java.io.Serializable;
import java.lang.reflect.Type;

/**
 * JSON序列化实现，基于Jackson实现。
 *
 * @author CH
 * @since 1.0.0
*/
@Spi("json")
public class JsonSerializer<T extends Serializable> implements Serializer<T> {

    private static final long serialVersionUID = 1L; // 串行版本uid

    /**
    * 类型
    */
    private final Type type;

    /**
    * 创建 json序列化器 实例
    * @param clazz clazz
    */
    public JsonSerializer(Class<T> clazz) {
        this.type = clazz;
    }

    /**
    * 创建 json序列化器 实例
    * @param type 类型
    */
    public JsonSerializer(Type type) {
        this.type = type;
    }

    @Override
    /** 序列化 */
    public byte[] serialize(T object) {
        return Json.toJsonByte(object);
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
    * 反序列化
    *
    * @param bytes bytes
    * @return deserialize的结果
    */
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
