package com.chua.fory.support.serialize;

import com.chua.common.support.base.serialize.Serialization;
import com.chua.common.support.spi.annotations.Spi;
import org.apache.fury.Fury;
import org.apache.fury.ThreadLocalFury;
import org.apache.fury.config.Language;

/**
 * Apache Fory（Fury）二进制序列化实现。
 *
 * <p>使用 ThreadLocalFury 包装，每个线程持有独立 Fury 实例，保证线程安全。
 * 支持对象引用跟踪（循环引用）、跨语言互操作与模式演化。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"fory", "fury"})
public class ForySerialization implements Serialization {

    /**
     * ThreadLocalFury：每个线程独立 Fury 实例，并发安全，跨线程可互反序列化
     */
    private static final ThreadLocalFury FURY = Fury.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .buildThreadLocalFury();

    @Override
    public String name() {
        return "fory";
    }

    @Override
    public byte[] serialize(Object obj) {
        if (obj == null) {
            return new byte[0];
        }
        return FURY.serialize(obj);
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
        return (T) FURY.deserialize(data);
    }
}