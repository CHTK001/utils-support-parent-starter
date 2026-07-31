package com.chua.mqtt.support.subscribe;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.datalake.subscribe.Subscriber;
import com.chua.datalake.subscribe.SubscriberConfig;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.util.Collection;
import java.util.function.Consumer;

/**
 * MQTT 协议订阅者实现，基于 Eclipse Paho MQTT v3 客户端接入 MQTT Broker。
 * <p>
 * 实现 {@link Subscriber} 接口，提供标准的 MQTT 连接、订阅、取消订阅生命周期管理。
 * 支持自动重连、心跳保活、自定义客户端 ID 和令牌认证。
 * </p>
 * <p>
 * 适用于数据湖订阅场景，作为 CDC（变更数据捕获）的 MQTT 协议通道。
 * 可通过 SPI 注解 {@code @Spi("mqtt")} 自动发现注册。
 * </p>
 * <p>默认连接参数：</p>
 * <ul>
 *   <li>默认 Broker 地址：tcp://127.0.0.1:1883</li>
 *   <li>默认 QoS：1（至少一次）</li>
 *   <li>心跳间隔：60 秒</li>
 * </ul>
 *
 * @author CH
 * @since 2024
 */
@Slf4j
@Spi("mqtt")
public class MqttSubscriber implements Subscriber {

    /**
     * 订阅者配置，包含 Broker 地址、端口、客户端 ID、认证令牌等
     */
    protected SubscriberConfig config;

    /**
     * Eclipse Paho MQTT 客户端实例，管理底层 TCP 连接和 MQTT 协议交互
     */
    protected MqttClient mqttClient;

    /**
     * 连接状态标记，{@code volatile} 保证多线程可见性
     */
    protected volatile boolean connected;

    /**
     * 消息处理器回调，接收 UTF-8 编码的消息负载
     */
    protected Consumer<String> messageHandler;

    /**
     * 使用默认配置创建 MQTT 订阅者。
     * <p>默认连接到 tcp://127.0.0.1:1883，适用于本地开发和测试。</p>
     */
    public MqttSubscriber() {
        this(new SubscriberConfig()
                .setHost("127.0.0.1")
                .setPort(1883));
    }

    /**
     * 使用自定义配置创建 MQTT 订阅者。
     *
     * @param config 订阅者配置，需包含 Broker 地址和端口
     */
    public MqttSubscriber(SubscriberConfig config) {
        this.config = config;
    }

    @Override
    public SubscriberConfig config() {
        return config;
    }

    @Override
    public String protocol() {
        return "MQTT";
    }

    /**
     * 连接到 MQTT Broker。
     * <p>
     * 如果未指定客户端 ID，自动生成 {@code mqtt-client-} + 时间戳 作为唯一标识。
     * 连接配置包含：连接超时、60 秒心跳保活、自动重连。
     * 如果配置了令牌（token），将其作为用户名进行认证。
     * </p>
     *
     * @param connectTimeoutMs 连接超时时间（毫秒），如果 &le; 0 则使用配置中的默认超时时间
     * @throws Exception 连接失败时抛出异常，可能为 MqttException 或网络异常
     */
    @Override
    public void connect(int connectTimeoutMs) throws Exception {
        if (config.getClientId() == null) {
            config.setClientId("mqtt-client-" + System.currentTimeMillis());
        }
        int timeout = connectTimeoutMs > 0 ? connectTimeoutMs : config.getConnectTimeoutMs();

        String brokerUrl = "tcp://" + config.getHost() + ":" + config.getPort();
        this.mqttClient = new MqttClient(brokerUrl, config.getClientId());

        MqttConnectOptions options = new MqttConnectOptions();
        options.setConnectionTimeout(timeout / 1000);
        options.setKeepAliveInterval(60);
        options.setAutomaticReconnect(true);
        if (config.getToken() != null && !config.getToken().isEmpty()) {
            options.setUserName(config.getToken());
        }

        this.mqttClient.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                connected = false;
                log.warn("MQTT 连接已断开: {}", cause.getMessage());
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                String payload = new String(message.getPayload());
                if (messageHandler != null) {
                    messageHandler.accept(payload);
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // QoS 1/2 消息投递完成回调，当前无需处理
            }
        });

        this.mqttClient.connect(options);
        this.connected = true;
        log.info("MQTT 订阅者已连接到 Broker: {}", brokerUrl);
    }

    /**
     * 断开与 MQTT Broker 的连接。
     * <p>
     * 将连接状态标记为 false，依次执行断开连接和关闭客户端操作。
     * 如果客户端已经断开或关闭过程中发生异常，静默忽略。
     * </p>
     */
    @Override
    public void disconnect() {
        this.connected = false;
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                mqttClient.disconnect();
                mqttClient.close();
                log.info("MQTT 订阅者已断开连接");
            }
        } catch (Exception ignored) {
            // 断开异常不向上传播，避免影响调用方清理流程
        }
    }

    /**
     * 检查当前是否已连接到 MQTT Broker。
     * <p>同时检查本地状态标记和 Paho 客户端的实际连接状态，两者都通过才视为已连接。</p>
     *
     * @return 如果已连接返回 {@code true}，否则返回 {@code false}
     */
    @Override
    public boolean isConnected() {
        return connected && mqttClient != null && mqttClient.isConnected();
    }

    /**
     * 订阅指定的主题列表。
     * <p>
     * 如果传入了 topics 集合则订阅多个主题并统一使用 QoS 1；
     * 否则使用 pipelineId 作为单个主题订阅。
     * </p>
     *
     * @param pipelineId 管道标识，当 topics 为空时作为默认主题名
     * @param topics     待订阅的主题集合，可以为空
     * @throws IOException 如果客户端未连接或订阅请求失败
     */
    @Override
    public void subscribe(String pipelineId, Collection<String> topics) throws IOException {
        if (mqttClient == null || !mqttClient.isConnected()) {
            throw new IOException("MQTT 客户端未连接，无法订阅");
        }
        try {
            if (topics != null && !topics.isEmpty()) {
                String[] topicArray = topics.toArray(new String[0]);
                int[] qos = new int[topicArray.length];
                for (int i = 0; i < qos.length; i++) {
                    qos[i] = 1;
                }
                mqttClient.subscribe(topicArray, qos);
                log.info("MQTT 已订阅 {} 个主题: {}", topicArray.length, String.join(", ", topicArray));
            } else {
                mqttClient.subscribe(pipelineId, 1);
                log.info("MQTT 已订阅主题: {}", pipelineId);
            }
        } catch (MqttException e) {
            throw new IOException("MQTT 订阅主题失败", e);
        }
    }

    /**
     * 取消所有订阅并断开连接。
     * <p>
     * 断开 MQTT 连接。注意：Paho 客户端不支持按主题取消订阅后保留连接，
     * 因此该方法直接断开连接。
     * </p>
     *
     * @throws IOException 如果断开连接时发生 MQTT 异常
     */
    @Override
    public void unsubscribe() throws IOException {
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                mqttClient.disconnect();
                log.info("MQTT 订阅者已取消订阅并断开连接");
            }
        } catch (MqttException e) {
            throw new IOException("MQTT 取消订阅失败", e);
        }
    }

    /**
     * 拉取消息（MQTT 协议暂不支持主动拉取模式）。
     * <p>
     * MQTT 为发布/订阅协议，消息由 Broker 推送而非消费者拉取。
     * 本方法返回空消息列表的 JSON 字符串，供接口兼容。
     * 实际消息接收通过 {@link #onMessage(Consumer)} 注册的回调处理。
     * </p>
     *
     * @param maxBatch 最大批量数，MQTT 模式下忽略此参数
     * @return 固定返回 {@code {"messages":[]}}
     * @throws IOException 不会抛出，仅满足接口签名
     */
    @Override
    public String poll(int maxBatch) throws IOException {
        return "{\"messages\":[]}";
    }

    /**
     * 注册消息处理器回调。
     * <p>
     * 在 {@link #connect(int)} 中设置的 MqttCallback 会在消息到达时调用此处理器。
     * 消息负载以 UTF-8 字符串形式传递给处理器。
     * </p>
     *
     * @param handler 消息处理回调，接收 UTF-8 编码的消息内容
     */
    @Override
    public void onMessage(Consumer<String> handler) {
        this.messageHandler = handler;
    }
}
