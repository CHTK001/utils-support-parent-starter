package com.chua.mqtt.support.dispatcher;

import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import com.chua.common.support.concurrent.dispatcher.DispatcherDefinition;
import com.chua.common.support.concurrent.dispatcher.provider.AbstractDispatcherProvider;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MQTT 分发器提供者，基于 Eclipse Paho MQTT v3 客户端实现跨进程的发布/订阅消息分发。
 * <p>
 * 通过 MQTT Broker 中转消息，支持多实例、跨服务的事件驱动通信。
 * 适用于分布式系统中需要解耦的异步消息场景，如配置变更通知、缓存刷新、数据同步触发等。
 * </p>
 * <p>
 * 使用轻量级内存持久化 {@link MemoryPersistence}，不保留离线消息。
 * 订阅关系使用 {@link ConcurrentHashMap} + {@link CopyOnWriteArrayList} 保证线程安全。
 * </p>
 * <p>环境配置属性：</p>
 * <ul>
 *   <li>{@code url} — MQTT Broker 地址（如 tcp://localhost:1883）</li>
 *   <li>{@code clientId} — 客户端 ID，不指定则自动生成（默认 {@value #DEFAULT_CLIENT_ID_PREFIX} + 时间戳）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("mqtt")
public class MqttDispatcherProvider extends AbstractDispatcherProvider {

    /**
     * 默认客户端 ID 前缀，后接当前时间戳以保证唯一性
     */
    private static final String DEFAULT_CLIENT_ID_PREFIX = "mqtt-dispatcher-";

    /**
     * Eclipse Paho MQTT 客户端，管理与 Broker 的连接、发布和订阅
     */
    private MqttClient client;

    /**
     * 主题 -> 分发定义列表 的映射表。
     * 一个主题可被多个分发器订阅，使用 CopyOnWriteArrayList 保证遍历时的线程安全。
     */
    private final Map<String, List<DispatcherDefinition>> definitionMap = new ConcurrentHashMap<>();

    /**
     * 构造 MQTT 分发器提供者。
     * <p>
     * 根据配置创建 MQTT 客户端、连接到 Broker、注册回调处理器。
     * 回调处理器负责接收消息后按主题分发给对应的 {@link DispatcherDefinition}。
     * </p>
     *
     * @param config 分发器配置，必须包含 Broker URL
     * @throws RuntimeException 如果连接 Broker 失败
     */
    public MqttDispatcherProvider(DispatcherConfig config) {
        super(config);
    }

    @Override
    /** 开始 */
    public void start() {
        try {
            String clientId = config.getClientId() != null ? config.getClientId() : DEFAULT_CLIENT_ID_PREFIX + System.currentTimeMillis();
            client = new MqttClient(config.getUrl(), clientId, new MemoryPersistence());
            client.connect();
            client.setCallback(new MqttCallback() {
                @Override
                /** ConnectionLost */
                public void connectionLost(Throwable cause) {
                    log.warn("MQTT 连接已断开: {}", cause.getMessage(), cause);
                }

                @Override
                /** MessageArrived */
                public void messageArrived(String topic, MqttMessage message) {
                    var defs = definitionMap.get(topic);
                    if (defs != null) {
                        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
                        for (var def : defs) {
                            def.dispatch(payload);
                        }
                    }
                }

                @Override
                /** DeliveryComplete */
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });
            log.info("MQTT 分发器已连接到 Broker: {}", config.getUrl());
        } catch (MqttException e) {
            throw new RuntimeException("MQTT 分发器连接 Broker 失败: " + config.getUrl(), e);
        }
    }

    /**
     * 向指定主题发布消息。
     * <p>
     * 使用 QoS 1（至少一次）投递语义，确保消息不丢失。
     * 消息体为 {@link Object#toString()} 的 UTF-8 编码字节数组。
     * </p>
     *
     * @param topic 目标主题名称，不能为空
     * @param body  消息体，为 null 时发布空消息
     */
    @Override
    public void publish(String topic, Object body) {
        try {
            byte[] payload = body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8);
            client.publish(topic, payload, 1, false);
        } catch (MqttException e) {
            log.error("MQTT 发布消息失败, topic={}, error={}", topic, e.getMessage(), e);
        }
    }

    /**
     * 订阅分发定义中声明的所有主题。
     * <p>
     * 将分发定义注册到本地映射表，并向 MQTT Broker 订阅对应主题。
     * 同一个主题允许多个分发定义共存。
     * </p>
     *
     * @param definition 分发定义，包含待订阅的主题列表
     */
    @Override
    public void subscribe(DispatcherDefinition definition) {
        for (var topic : definition.getTopics()) {
            try {
                definitionMap.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(definition);
                client.subscribe(topic);
                log.debug("MQTT 已订阅主题: {}", topic);
            } catch (MqttException e) {
                log.error("MQTT 订阅主题失败, topic={}, error={}", topic, e.getMessage(), e);
            }
        }
    }

    /**
     * 取消订阅分发定义中声明的所有主题。
     * <p>
     * 从本地映射表中移除指定的分发定义。
     * 只有当某个主题下的所有分发定义都被移除后，才真正向 MQTT Broker 发送取消订阅请求。
     * </p>
     *
     * @param definition 分发定义，包含待取消订阅的主题列表
     */
    @Override
    public void unsubscribe(DispatcherDefinition definition) {
        for (var topic : definition.getTopics()) {
            var defs = definitionMap.get(topic);
            if (defs != null) {
                defs.remove(definition);
                if (defs.isEmpty()) {
                    definitionMap.remove(topic);
                    try {
                        client.unsubscribe(topic);
                        log.debug("MQTT 已取消订阅主题: {}", topic);
                    } catch (MqttException e) {
                        log.warn("MQTT 取消订阅主题失败, topic={}, error={}", topic, e.getMessage(), e);
                    }
                }
            }
        }
    }

    /**
     * 关闭分发器，释放资源。
     * <p>
     * 依次执行：断开 MQTT 连接 -> 关闭客户端 -> 清空本地订阅映射表。
     * 如果连接已断开或关闭过程中发生异常，仅记录警告并继续执行后续清理。
     * </p>
     */
    @Override
    public void close() {
        try {
            if (client != null && client.isConnected()) {
                client.disconnect();
                log.info("MQTT 分发器已断开连接");
            }
        } catch (MqttException e) {
            log.warn("MQTT 断开连接时发生异常: {}", e.getMessage());
        }
        try {
            if (client != null) {
                client.close();
            }
        } catch (MqttException e) {
            log.warn("MQTT 关闭客户端时发生异常: {}", e.getMessage());
        }
        definitionMap.clear();
        log.info("MQTT 分发器已关闭");
    }
}
