package com.chua.common.support.serialize;

import java.io.Serializable;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import org.jspecify.annotations.NullUnmarked;

/**
 * 序列化流管理器
 *
 * <p>统一管理序列化和反序列化操作，提供简洁易用的序列化执行 API。
 * 内部封装了 {@link SerializerProvider} 的调度能力，对外提供统一的序列化入口。
 *
 * <p>核心能力：
 * <ul>
 *   <li><strong>默认JSON序列化</strong>：通过 {@link #serialize(Object)} 使用JSON序列化对象</li>
 *   <li><strong>自定义序列化器</strong>：通过 {@link #use(Serializer)} 切换序列化实现</li>
 *   <li><strong>类型安全反序列化</strong>：通过 {@link #deserialize(byte[], Class)} 指定目标类型</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * SerializerFlow flow = new SerializerFlow();
 * byte[] bytes = flow.serialize(object);
 * User user = flow.deserialize(bytes, User.class);
 *
 * // 使用Java原生序列化
 * byte[] javaBytes = flow.use(new JavaSerializer<>()).serialize(object);
 * }</pre>
 *
 * @author CH
 * @since 1.0.0
 */
@NullUnmarked
public class SerializerFlow {

    /**
     * 底层序列化提供者
     */
    private final SerializerProvider provider;

    /**
     * 当前使用的序列化器
     */
    private volatile Serializer<? extends Serializable> currentSerializer;

    /**
     * 创建默认的序列化流管理器
     *
     * <p>使用 {@link DefaultSerializerProvider} 作为默认序列化提供者，
     * 默认使用 {@link JsonSerializer} 进行序列化和反序列化。
     */
    public SerializerFlow() {
        this(new DefaultSerializerProvider(), new JsonSerializer<>(Object.class));
    }

    /**
     * 创建指定提供者和序列化器的序列化流管理器
     *
     * @param provider       序列化提供者
     * @param serializer 默认序列化器
     */
    public SerializerFlow(SerializerProvider provider, Serializer<? extends Serializable> serializer) {
        this.provider = provider;
        this.currentSerializer = serializer;
    }

    /**
     * 序列化对象为字节数组
     *
     * @param object 待序列化的对象
     * @return 字节数组
     */
    public byte[] serialize(Object object) {
        if (object == null) {
            return new byte[0];
        }
@SuppressWarnings({"unchecked"})
        Serializer<Serializable> s = (Serializer<Serializable>) currentSerializer;
        return s.serialize((Serializable) object);
    }

    /**
     * 将字节数组反序列化为指定类型的对象
     *
     * @param bytes 字节数组
     * @param clazz 目标类型
     * @return 反序列化后的对象
     */
    public <T extends Serializable> T deserialize(byte[] bytes, Class<T> clazz) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        Serializer<T> serializer = (Serializer<T>) new JsonSerializer<>(clazz);
        return serializer.deserialize(bytes);
    }

    /**
     * 将字节数组反序列化为指定类型的对象（使用当前序列化器）
     *
     * @param bytes 字节数组
     * @return 反序列化后的对象
     */
    public <T extends Serializable> T deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        return (T) currentSerializer.deserialize(bytes);
    }

    /**
     * 切换序列化器
     *
     * @param serializer 新的序列化器
     * @return 当前序列化流管理器（支持链式调用）
     */
    public SerializerFlow use(Serializer<? extends Serializable> serializer) {
        this.currentSerializer = serializer;
        return this;
    }

    /**
     * 获取当前使用的序列化器
     *
     * @return 当前序列化器
     */
    public Serializer<? extends Serializable> getSerializer() {
        return currentSerializer;
    }

    /**
     * 获取底层序列化提供者
     *
     * @return 序列化提供者
     */
    public SerializerProvider getProvider() {
        return provider;
    }

    /**
     * 配置构建器
     *
     * <p>支持链式调用风格的序列化器配置，通过 {@link #done()} 返回上一级管理器。
     */
    public class SerializerConfigBuilder {
        private Serializer<? extends Serializable> serializer;

        public SerializerConfigBuilder json() {
            this.serializer = new JsonSerializer<>(Object.class);
            return this;
        }

        public SerializerConfigBuilder java() {
            this.serializer = new JavaSerializer<>();
            return this;
        }

        /**
         * 完成配置构建，返回上一级管理器
         *
         * @return 序列化流管理器
         */
        public SerializerFlow done() {
            return new SerializerFlow(provider, serializer);
        }
    }

    /**
     * 获取配置构建器（链式调用入口）
     *
     * @return 配置构建器
     */
    public SerializerConfigBuilder config() {
        return new SerializerConfigBuilder();
    }
}
