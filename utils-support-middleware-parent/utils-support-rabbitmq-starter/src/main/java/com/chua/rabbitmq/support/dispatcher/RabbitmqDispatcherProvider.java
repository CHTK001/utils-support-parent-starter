package com.chua.rabbitmq.support.dispatcher;

import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import com.chua.common.support.concurrent.dispatcher.DispatcherDefinition;
import com.chua.common.support.concurrent.dispatcher.provider.AbstractDispatcherProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.rabbitmq.client.*;
import lombok.extern.slf4j.Slf4j;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * RabbitMQ 分发器提供者，基于 RabbitMQ 实现跨进程的发布订阅。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("rabbitmq")
public class RabbitmqDispatcherProvider extends AbstractDispatcherProvider {

    /**
     * RabbitMQ 连接
     */
    private Connection connection;

    /**
     * RabbitMQ 通道
     */
    private Channel channel;

    /**
     * 主题与订阅定义列表的映射
     */
    private final Map<String, List<DispatcherDefinition>> definitionMap = new ConcurrentHashMap<>();

    /**
     * 消费者线程池
     */
    private final ExecutorService executor = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
            /**
             * Thread工厂Builder。
             *
             * @return 结果值
             */
            new ThreadFactoryBuilder().setNameFormat("rabbitmq-dispatcher-%d").setDaemon(true).build());

    /**
     * 是否已关闭
     */
    private volatile boolean closed = false;

    /**
     * 创建 rabbitmqdispatcher提供者 实例
     * @param config 配置
     */
    public RabbitmqDispatcherProvider(DispatcherConfig config) {
        super(config);
    }

    @Override
    /**
     * 开始
    */
    public void start() {
        try {
            var factory = new ConnectionFactory();
            factory.setUri(config.getUrl());
            factory.setConnectionTimeout((int) config.getConnectionTimeoutMillis());
            this.connection = factory.newConnection(executor);
            this.channel = connection.createChannel();
        } catch (Exception e) {
            throw new RuntimeException("创建 RabbitMQ 连接失败", e);
        }
    }

    @Override
    /**
     * 发布
    */
    public void publish(String topic, Object body) {
        try {
            var value = body == null ? "" : body.toString();
            channel.basicPublish(topic, "", null, value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("RabbitMQ 发布消息失败，主题：{}", topic, e);
        }
    }

    @Override
    /**
     * 订阅
    */
    public void subscribe(DispatcherDefinition definition) {
        for (var topic : definition.getTopics()) {
            definitionMap.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(definition);
            if (!closed) {
                startConsumer(topic);
            }
        }
    }

    /**
     * 启动 RabbitMQ 消费者监听指定交换器。
     *
     * @param topic 交换器名称
     */
    private void startConsumer(String topic) {
        try {
            channel.exchangeDeclare(topic, "fanout", true);
            var queueName = channel.queueDeclare().getQueue();
            channel.queueBind(queueName, topic, "");
            channel.basicConsume(queueName, true, (consumerTag, delivery) -> {
                var body = new String(delivery.getBody(), StandardCharsets.UTF_8);
                var definitions = definitionMap.get(topic);
                if (definitions != null) {
                    for (var def : definitions) {
                        def.dispatch(body);
                    }
                }
            }, consumerTag -> {});
        } catch (IOException e) {
            log.error("RabbitMQ 启动消费者失败，主题：{}", topic, e);
        }
    }

    @Override
    /**
     * 取消订阅
    */
    public void unsubscribe(DispatcherDefinition definition) {
        for (var topic : definition.getTopics()) {
            var definitions = definitionMap.get(topic);
            if (definitions != null) {
                definitions.remove(definition);
                if (definitions.isEmpty()) {
                    definitionMap.remove(topic);
                }
            }
        }
    }

    @Override
    /**
     * 关闭
    */
    public void close() {
        closed = true;
        try {
            channel.close();
            connection.close();
        } catch (Exception e) {
            log.warn("关闭 RabbitMQ 连接失败", e);
        }
        executor.shutdown();
        definitionMap.clear();
    }
}
