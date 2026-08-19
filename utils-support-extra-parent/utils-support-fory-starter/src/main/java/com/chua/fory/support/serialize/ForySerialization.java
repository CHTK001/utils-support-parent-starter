package com.chua.fory.support.serialize;

import com.chua.common.support.base.serialize.Serialization;
import com.chua.common.support.spi.annotations.Spi;
import org.apache.fury.Fury;
import org.apache.fury.config.Language;

/**
 * Apache Fory（Fury）二进制序列化实现。
 *
 * <p>直接使用 Apache Fury 进行编解码，支持对象引用跟踪（循环引用）、跨语言互操作与模式演化。
 * Fury 实例为线程安全单例，配置完成后可被多线程并发使用。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"fory", "fury"})
public class ForySerialization implements Serialization {

    /**
     * ThreadLocal 隔离的 Fury 实例，保证线程安全。
     * Fury 自身的 serialize/deserialize 在并发调用时仍可能抛出 Nested call，
     * 因此每个线程持有独立实例。
     */
    private static final ThreadLocal<Fury> FURY = ThreadLocal.withInitial(() ->
            Fury.builder()
                    .withLanguage(Language.JAVA)
                    .withRefTracking(true)
                    .requireClassRegistration(false)
                    .build()
    );

    @Override
    public String name() {
        return "fory";
    }

    @Override
    public byte[] serialize(Object obj) {
        if (obj == null) {
            return new byte[0];
        }
        return FURY.get().serialize(obj);
    }

    /**
     * 从字节数组反序列化为指定类型的对象。
     *
     * <p>Fury 在 Java 原生模式下会在数据流中携带类型信息，因此 {@code type} 参数仅用于返回类型约束，
     * 实际解码过程不依赖该参数。</p>
     *
     * @param data 序列化后的字节数组
     * @param type 目标类型（仅用于返回类型约束）
     * @param <T>  目标类型泛型
     * @return 反序列化后的对象
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T deserialize(byte[] data, Class<T> type) {
        if (data == null || data.length == 0) {
            return null;
        }
        return (T) FURY.get().deserialize(data);
    }
}
