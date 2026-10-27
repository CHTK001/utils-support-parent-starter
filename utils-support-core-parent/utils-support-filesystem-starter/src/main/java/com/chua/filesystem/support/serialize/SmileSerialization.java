package com.chua.filesystem.support.serialize;

import com.chua.common.support.base.serialize.Serialization;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import lombok.SneakyThrows;

/**
 * Smile（二进制 JSON）序列化实现
 * <p>
 * 直接使用 Jackson Smile（对象映射器 + smile工厂）进行编解码，性能与兼容性更佳。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("smile")
public class SmileSerialization implements Serialization {

    /**
     * 线程安全：对象映射器 是线程安全的（配置后不再修改）
     */
    private static final ObjectMapper MAPPER = new ObjectMapper(new SmileFactory());

    /**
     * 名称
    */
    @Override
    public String name() {
        return "smile";
    }

    /**
     * 序列化
    */
    @SneakyThrows
    @Override
    public byte[] serialize(Object obj) {
        return MAPPER.writeValueAsBytes(obj);
    }

    /**
     * 反序列化
    */
    @SneakyThrows
    @Override
    public <T> T deserialize(byte[] data, Class<T> type) {
        return MAPPER.readValue(data, type);
    }
}
