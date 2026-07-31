package com.chua.filesystem.support.serialize;

import com.chua.common.support.base.serialize.Serialization;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import lombok.SneakyThrows;

/**
 * Smile（二进制 JSON）序列化实现
 * <p>
 * 直接使用 Jackson Smile（ObjectMapper + SmileFactory）进行编解码，性能与兼容性更佳。
 *
 * @author CH
 * @version 1.0.0
 * @since 2025-01-01
 */
@Spi("smile")
public class SmileSerialization implements Serialization {

    /**
     * 线程安全：ObjectMapper 是线程安全的（配置后不再修改）
     */
    private static final ObjectMapper MAPPER = new ObjectMapper(new SmileFactory());

    @Override
    public String name() {
        return "smile";
    }

    @SneakyThrows
    @Override
    public byte[] serialize(Object obj) {
        return MAPPER.writeValueAsBytes(obj);
    }

    @SneakyThrows
    @Override
    public <T> T deserialize(byte[] data, Class<T> type) {
        return MAPPER.readValue(data, type);
    }
}
