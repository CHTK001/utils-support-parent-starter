package com.chua.common.support.concurrent.dispatcher;

import java.util.List;
import java.util.function.Consumer;

/**
 * 消费者订阅定义，基于 Consumer 接口，避免反射。
 *
 * @param <T> 消息体类型
 * @author CH
 * @since 4.0.0.42
 */
@SuppressWarnings("NullAway")
@NullUnmarked
public class ConsumerDispatcherDefinition<T> extends DispatcherDefinition {

    private final Consumer<T> consumer;

    public ConsumerDispatcherDefinition(Consumer<T> consumer, List<String> topics) {
        super(consumer, null, topics);
        this.consumer = consumer;
    }

    @Override
    public void dispatch(Object body) {
        consumer.accept((T) body);
    }
}