package com.chua.common.support.concurrent.dispatcher;


/**
* 分发器提供者工具接口，定义消息的发布和订阅行为。
* <p>
* 该接口采用 SPI 机制实现，允许不同的消息中间件（如内存、Kafka、RabbitMQ、Chronicle）进行扩展和替换。
* 注册和注销统一通过 {@link DispatcherDefinition} 进行管理。
* </p>
*
* @author CH
* @since 2025-11-26
 */
public interface DispatcherProvider {

    /**
    * 启动分发器，初始化连接等资源。
    */
    default void start() {
    }

    /**
    * 发布消息到指定主题。
    *
    * @param topic 目标主题
    * @param body  消息体
    */
    void publish(String topic, Object body);

    /**
    * 注册订阅定义，消息到达时通过 definition 的 dispatch 方法触发。
    *
    * @param definition 订阅定义
    */
    void subscribe(DispatcherDefinition definition);

    /**
    * 注销订阅定义。
    *
    * @param definition 订阅定义
    */
    void unsubscribe(DispatcherDefinition definition);

    /**
    * 关闭分发器，释放所有相关资源。
    */
    void close();
}
