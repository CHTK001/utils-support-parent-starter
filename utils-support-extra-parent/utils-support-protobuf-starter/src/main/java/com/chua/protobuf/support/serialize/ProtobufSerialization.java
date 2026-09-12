package com.chua.protobuf.support.serialize;

import com.chua.common.support.base.serialize.Serialization;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;
import io.protostuff.LinkedBuffer;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.Schema;
import io.protostuff.runtime.RuntimeSchema;

import java.util.concurrent.ConcurrentHashMap;

/**
* 基于 protostuff 的 Protobuf 二进制序列化实现。
*
* <p>实现 {@link Serialization} 接口，通过 protostuff 运行时动态生成 Schema，
* 提供无需 .Proto.io 文件的 Protobuf 编解码能力。</p>
*
* <p>与 {@link ProtobufSerializer} 的区别：
* <ul>
*   <li>{@link ProtobufSerializer} — 类型安全，需指定 Class<T>，实现 {@code Serializer<T>} 接口</li>
*   <li>{@link ProtobufSerialization} — 通用接口，实现 {@link Serialization} 接口，适合 SPI 发现</li>
* </ul>
*
* <p>通过 {@code @Spi("protobuf")} 注册为 {@link Serialization} 的 SPI 实现，
* 并由 {@code @AutoSpi} 在编译期自动生成 {@code META-INF/extensions} SPI 索引。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("protobuf")
@AutoSpi(value = "com.chua.common.support.base.serialize.Serialization")
public class ProtobufSerialization implements Serialization {

    /**
    * 模式 实例缓存池，按实体类类型缓存 模式 实例
     */
    private static final ConcurrentHashMap<Class<?>, Schema<?>> SCHEMA_POOL = new ConcurrentHashMap<>();

    /**
    * 链接缓冲 线程局部变量，避免每次序列化都分配新缓冲区
     */
    private static final ThreadLocal<LinkedBuffer> BUFFER_THREAD_LOCAL =
            ThreadLocal.withInitial(() -> LinkedBuffer.allocate(LinkedBuffer.DEFAULT_BUFFER_SIZE));

    @Override
    /** 名称 */
    public String name() {
        return "protobuf";
    }

    @Override
    /** 序列化 */
    public byte[] serialize(Object obj) throws Exception {
        if (obj == null) {
            return new byte[0];
        }
        Schema<Object> schema = (Schema<Object>) SCHEMA_POOL.computeIfAbsent(obj.getClass(), RuntimeSchema::getSchema);
        LinkedBuffer buffer = BUFFER_THREAD_LOCAL.get();
        try {
            return ProtostuffIOUtil.toByteArray(obj, schema, buffer);
        } finally {
            buffer.clear();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
    * 反序列化
    *
    * @param data 数据
    * @param type 类型
    * @return deserialize的结果
     */
    public <T> T deserialize(byte[] data, Class<T> type) throws Exception {
        if (data == null || data.length == 0) {
            return null;
        }
        Schema<T> schema = (Schema<T>) SCHEMA_POOL.computeIfAbsent(type, RuntimeSchema::getSchema);
        T result = schema.newMessage();
        ProtostuffIOUtil.mergeFrom(data, result, schema);
        return result;
    }

    /**
    * 清除所有 模式 实例缓存。
     */
    public static void clearPool() {
        SCHEMA_POOL.clear();
    }
}