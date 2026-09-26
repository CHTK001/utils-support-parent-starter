package com.chua.fory.support.serialize;

import com.chua.common.support.base.serialize.Serialization;
import com.chua.common.support.serialize.DeserializationGuard;
import com.chua.common.support.spi.annotations.Spi;
import org.apache.fury.Fury;
import org.apache.fury.ThreadSafeFury;
import org.apache.fury.config.Language;

/**
 * Apache Fory（Fury）二进制序列化实现。
 *
 * <p>直接使用 Apache Fury 进行编解码，支持对象引用跟踪（循环引用）、跨语言互操作与模式演化。</p>
 *
 * <p>Fury 的 {@code Fury} 对象<strong>不是</strong>线程安全的，因此这里持有的是
 * {@link ThreadSafeFury}（内部为每个线程维护独立编解码器），调用方无需再加锁。</p>
 *
 * <p>Fury 在 {@code requireClassRegistration(false)} 下会按报文携带的类名还原任意类，
 * 与 JDK 原生序列化同属反序列化攻击面，故通过 {@code setClassChecker} 接入
 * {@link DeserializationGuard}：命中黑名单的类名在解析阶段即被拒绝。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"fory", "fury"})
public class ForySerialization implements Serialization {

    /**
     * 全局共享线程安全 Fury 实例
     */
    private static final ThreadSafeFury FURY = newFury();

    /**
     * 构建线程安全 Fury 实例并挂上反序列化类名守卫。
     *
     * @return 已配置完成的 Fury 门面
     */
    private static ThreadSafeFury newFury() {
        ThreadSafeFury fury = Fury.builder()
                .withLanguage(Language.JAVA)
                .withRefTracking(true)
                .requireClassRegistration(false)
                .registerGuavaTypes(false)
                .buildThreadSafeFury();
        fury.setClassChecker((classResolver, className) -> DeserializationGuard.isAllowed(className));
        return fury;
    }

    @Override

    /**
     * Name
     */
    public String name() {
        return "fory";
    }

    @Override

    /**
     * 序列化
     */
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
