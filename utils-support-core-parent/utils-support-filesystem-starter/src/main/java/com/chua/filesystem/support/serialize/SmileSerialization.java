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
   * @版本 1.0.0
 * @since 4.0.0.42
 */
@Spi("smile")
public class SmileSerialization implements Serialization {

    /**
      * 线程安全：对象映射器 是线程安全的（配置后不再修改）
     */
    private static final ObjectMapper MAPPER = new ObjectMapper(new SmileFactory());

    @Override
    /** 名称 */
    public String name() {
        return "smile";
    }

    @SneakyThrows
    @Override
    /** 序列化 */
    public byte[] serialize(Object obj) {
        return MAPPER.writeValueAsBytes(obj);
    }

    @SneakyThrows
    @Override
    /** 反序列化 */
    public <T> T deserialize(byte[] data, Class<T> type) {
        return MAPPER.readValue(data, type);
    }
}
