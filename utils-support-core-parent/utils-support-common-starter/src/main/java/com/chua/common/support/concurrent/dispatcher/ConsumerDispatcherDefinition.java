package com.chua.common.support.concurrent.dispatcher;

import lombok.Getter;

import java.util.List;
import java.util.function.Consumer;

/**
 * 消费者订阅定义，基于 Consumer 接口，避免反射。
 * <p>
 * 泛型 T 在普通实例化时会被擦除，反射无法恢复实际类型，
 * 因此额外携带 {@link #bodyType} 供序列化框架还原消息体类型。
 * </p>
 *
 * @param <T> 消息体类型
 * @author CH
 * @since 4.0.0.42
 */
public class ConsumerDispatcherDefinition<T> extends DispatcherDefinition {

    /**
     * 消息体回调
     */
    private final Consumer<T> consumer;

    /**
     * 消息体类型（泛型 T 的原始类型），用于反序列化还原
     */
    @Getter
    private final Class<?> bodyType;

    /**
     * 构造订阅定义，消息体类型由后续解析推断。
     *
     * @param consumer 消息体回调
     * @param topics   订阅主题列表
     */
    public ConsumerDispatcherDefinition(Consumer<T> consumer, List<String> topics) {
        this(consumer, null, topics);
    }

    /**
     * 构造订阅定义，显式指定消息体类型。
     *
     * @param consumer 消息体回调
     * @param bodyType 消息体类型
     * @param topics   订阅主题列表
     */
    public ConsumerDispatcherDefinition(Consumer<T> consumer, Class<?> bodyType, List<String> topics) {
        super(consumer, null, topics);
        this.consumer = consumer;
        this.bodyType = bodyType;
    }

    @Override
    /** 分发 */
    public void dispatch(Object body) {
        consumer.accept((T) body);
    }
}
