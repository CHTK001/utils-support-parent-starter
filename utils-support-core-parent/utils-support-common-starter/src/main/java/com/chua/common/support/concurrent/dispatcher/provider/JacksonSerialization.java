package com.chua.common.support.concurrent.dispatcher.provider;

import com.chua.common.support.base.serialize.Serialization;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
* 基于 Jackson 的默认 {@link Serialization} 实现。
*
* <p>common-starter 内置依赖 jackson-databind，故作为 {@link WalDispatcherProvider}
* 的默认序列化器（不依赖外部 Fury/Kryo 等库）。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"jackson"})
public class JacksonSerialization implements Serialization {

    /** 单例实例 */
    public static final JacksonSerialization INSTANCE = new JacksonSerialization();

    /** JSON 对象映射器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    /** Name */
    public String name() {
        return "jackson";
    }

    @Override
    /** 序列化 */
    public byte[] serialize(Object obj) throws Exception {
        return objectMapper.writeValueAsBytes(obj);
    }

    @Override
    /** 反序列化 */
    public <T> T deserialize(byte[] data, Class<T> type) throws Exception {
        return objectMapper.readValue(data, type);
    }
}