package com.chua.fory.support.serialize;

import com.chua.common.support.base.serialize.Serialization;
import com.chua.common.support.spi.annotations.Spi;
import org.apache.fury.Fury;
import org.apache.fury.config.Language;

/**
 * Apache Fory（Fury）二进制序列化实现。
 *
 * <p>直接使用 Apache Fury 进行编解码，支持对象引用跟踪（循环引用）、跨语言互操作与模式演化。
 * 采用单个共享 Fury 实例 + 调用方 {@code synchronized} 保证线程安全，避免
 * {@link ThreadLocal} 每线程冷启动建 schema 的性能开销。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"fory", "fury"})
public class ForySerialization implements Serialization {

    /**
     * 全局共享 Fury 实例（调用方通过 同步 保证线程安全）
     */
    private static final Fury FURY = Fury.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .registerGuavaTypes(false)
            .build();

    /**
     * 获取共享 Fury 实例。
     *
     * @return Fury 实例
     */
    private static Fury fury() {
        return FURY;
    }

    @Override
    /** 名称 */
    public String name() {
        return "fory";
    }

    @Override
    /** 序列化 */
    public byte[] serialize(Object obj) {
        if (obj == null) {
            return new byte[0];
        }
        return fury().serialize(obj);
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
        return (T) fury().deserialize(data);
    }
}
