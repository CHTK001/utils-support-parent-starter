package com.chua.datalake.support.mqtt;

import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.spi.pipeline.PipelineEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MQTT 入站适配器：订阅 MQTT Broker → JSON → DataEnvelope → PipelineEngine。
 *
 * <p>支持 MQTT 5.0 协议，自动将 JSON 消息体解析为 {@code Map<String, Object>}，
 * 包装为 {@link DataEnvelope} 后交给 PipelineEngine 执行管线处理。</p>
 *
 * <p>配置示例：</p>
 * <pre>{@code
 * MqttInboundAdapter adapter = MqttInboundAdapter.builder()
 *     .brokerUrl("tcp://localhost:1883")
 *     .clientId("datalake-sub-001")
 *     .topic("sensor/+/data")
 *     .pipelineId("sensor-pipeline")
 *     .pipelineEngine(engine)
 *     .qos(1)
 *     .build();
 * adapter.start();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class MqttInboundAdapter {

    /** MQTT 客户端 */
    private volatile MqttClient client;

    /** Broker URL */
    private final String brokerUrl;

    /** 客户端 ID */
    private final String clientId;

    /** 订阅主题（支持通配符 +/#） */
    private final String topic;

    /** 管线 ID */
    private final String pipelineId;

    /** 管线引擎 */
    private final PipelineEngine pipelineEngine;

    /** QoS 级别（0/1/2） */
    private final int qos;

    /** 用户名（可选） */
    private final String username;

    /** 密码（可选） */
    private final String password;

    /** 消息计数器 */
    private final AtomicLong messageCount = new AtomicLong(0);

    /** JSON 解析器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 运行状态 */
    private volatile boolean running = false;

    private MqttInboundAdapter(Builder builder) {
        this.brokerUrl = builder.brokerUrl;
        this.clientId = builder.clientId;
        this.topic = builder.topic;
        this.pipelineId = builder.pipelineId;
        this.pipelineEngine = builder.pipelineEngine;
        this.qos = builder.qos;
        this.username = builder.username;
        this.password = builder.password;
    }

    /**
     * 创建构建器。
     *
     * @return 新构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 启动 MQTT 订阅。
     */
    public void start() {
        if (running) {
            return;
        }
        try {
            client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
            MqttConnectionOptions connOpts = new MqttConnectionOptions();
            connOpts.setCleanStart(true);
            connOpts.setKeepAliveInterval(30);
            if (username != null) {
                connOpts.setUserName(username);
            }
            if (password != null) {
                connOpts.setPassword(password.getBytes(StandardCharsets.UTF_8));
            }
            connOpts.setAutomaticReconnect(true);

            client.setCallback(new org.eclipse.paho.mqttv5.client.MqttCallback() {
                @Override
                public void disconnected(org.eclipse.paho.mqttv5.client.MqttDisconnectResponse response) {
                    log.warn("[datalake-mqtt] 连接断开: {}", response.getReasonString());
                }

                @Override
                public void mqttErrorOccurred(MqttException exception) {
                    log.error("[datalake-mqtt] MQTT 错误: {}", exception.getMessage(), exception);
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    handleMessage(topic, message);
                }

                @Override
                public void deliveryComplete(org.eclipse.paho.mqttv5.client.IMqttToken token) {
                }

                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    log.info("[datalake-mqtt] {}连接成功: {}", reconnect ? "重新" : "", serverURI);
                }

                @Override
                public void authPacketArrived(int reasonCode, MqttProperties properties) {
                }
            });

            client.connect(connOpts);
            client.subscribe(topic, qos);
            running = true;
            log.info("[datalake-mqtt] 启动成功: broker={}, topic={}, clientId={}", brokerUrl, topic, clientId);
        } catch (MqttException e) {
            log.error("[datalake-mqtt] 启动失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 停止 MQTT 订阅。
     */
    public void stop() {
        if (!running || client == null) {
            return;
        }
        try {
            client.disconnect();
            client.close();
            running = false;
            log.info("[datalake-mqtt] 已停止, 共接收 {} 条消息", messageCount.get());
        } catch (MqttException e) {
            log.warn("[datalake-mqtt] 停止异常: {}", e.getMessage());
        }
    }

    /**
     * 处理 MQTT 消息。
     *
     * @param topic   主题
     * @param message 消息
     */
    @SuppressWarnings("unchecked")
    private void handleMessage(String topic, MqttMessage message) {
        try {
            String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
            Map<String, Object> data = objectMapper.readValue(payload, Map.class);
            data.put("_mqtt_topic", topic);
            data.put("_mqtt_qos", message.getQos());
            data.put("_mqtt_retained", message.isRetained());

            DataEnvelope envelope = DataEnvelope.builder()
                    .parsed(data)
                    .pipelineId(pipelineId)
                    .traceId("mqtt-" + messageCount.incrementAndGet())
                    .timestamp(System.currentTimeMillis())
                    .build();
            envelope.addTrace("[MQTT] topic=" + topic + " qos=" + message.getQos());

            pipelineEngine.execute(pipelineId, envelope);
            log.debug("[datalake-mqtt] 消息处理完成: topic={}, count={}", topic, messageCount.get());
        } catch (Exception e) {
            log.error("[datalake-mqtt] 消息处理失败: topic={}, error={}", topic, e.getMessage(), e);
        }
    }

    /**
     * 返回已接收消息数。
     *
     * @return 消息计数
     */
    public long getMessageCount() {
        return messageCount.get();
    }

    /**
     * 是否运行中。
     *
     * @return true 表示已启动
     */
    public boolean isRunning() {
        return running;
    }

    // ━━━━━━━━━━━━━━ Builder ━━━━━━━━━━━━━━

    /**
     * MQTT 入站适配器构建器。
     */
    public static class Builder {
        private String brokerUrl = "tcp://localhost:1883";
        private String clientId = "datalake-mqtt-" + System.currentTimeMillis();
        private String topic = "#";
        private String pipelineId;
        private PipelineEngine pipelineEngine;
        private int qos = 1;
        private String username;
        private String password;

        public Builder brokerUrl(String brokerUrl) { this.brokerUrl = brokerUrl; return this; }
        public Builder clientId(String clientId) { this.clientId = clientId; return this; }
        public Builder topic(String topic) { this.topic = topic; return this; }
        public Builder pipelineId(String pipelineId) { this.pipelineId = pipelineId; return this; }
        public Builder pipelineEngine(PipelineEngine engine) { this.pipelineEngine = engine; return this; }
        public Builder qos(int qos) { this.qos = qos; return this; }
        public Builder username(String username) { this.username = username; return this; }
        public Builder password(String password) { this.password = password; return this; }

        public MqttInboundAdapter build() {
            if (pipelineId == null || pipelineEngine == null) {
                throw new IllegalArgumentException("pipelineId 和 pipelineEngine 不能为空");
            }
            return new MqttInboundAdapter(this);
        }
    }
}
