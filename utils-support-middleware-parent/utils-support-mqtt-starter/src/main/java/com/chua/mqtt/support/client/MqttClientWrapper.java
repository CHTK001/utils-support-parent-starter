package com.chua.mqtt.support.client;

import com.chua.common.support.objects.annotation.OnClose;
import com.chua.common.support.objects.annotation.OnError;
import com.chua.common.support.objects.annotation.OnMessage;
import com.chua.common.support.objects.annotation.OnOpen;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * MQTT 全功能链式客户端。
 *
 * <p>封装 Eclipse Paho MQTT v3，提供链式 API，支持 @OnOpen/@OnClose/@OnMessage 注解。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // 创建客户端
 * MqttClientWrapper client = MqttClientWrapper.builder()
 *     .broker("tcp://127.0.0.1:1883")
 *     .clientId("my-client")
 *     .username("user").password("pass")
 *     .build();
 * client.start();
 *
 * // 订阅
 * client.subscribe().topic("order/#").qos(1).handler((topic, msg) -> {
 *     System.out.println(topic + ": " + msg);
 * }).start();
 *
 * // 发布
 * client.publish().topic("order/created").payload("{\"id\":1}").qos(1).send();
 *
 * // 注解方式
 * client.register(new Object() {
 *     &#64;OnOpen
 *     public void onConnect() { System.out.println("已连接"); }
 *
 *     &#64;OnMessage("order/#")
 *     public void onOrder(String payload) { System.out.println("收到: " + payload); }
 *
 *     &#64;OnClose
 *     public void onDisconnect() { System.out.println("已断开"); }
 * });
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Getter
public class MqttClientWrapper implements AutoCloseable {

    /** Broker */
    private final String broker;
    /** 客户端ID */
    private final String clientId;
    /** Username */
    private final String username;
    /** 密码 */
    private final String password;
    /** Keepalive */
    private final int keepAlive;
    /** Clean会话 */
    private final boolean cleanSession;
    /** Connection超时 */
    private final int connectionTimeout;
    /** Automaticreconnect */
    private final boolean automaticReconnect;

    /** Mqtt客户端 */
    private MqttClient mqttClient;
    private final Map<String, List<BiConsumer<String, String>>> topicHandlers = new ConcurrentHashMap<>();
    /** Connectlisteners */
    private final List<Runnable> connectListeners = new CopyOnWriteArrayList<>();
    /** Disconnectlisteners */
    private final List<Consumer<Throwable>> disconnectListeners = new CopyOnWriteArrayList<>();
    /** 错误listeners */
    private final List<Consumer<Throwable>> errorListeners = new CopyOnWriteArrayList<>();
    /** Connected */
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private MqttClientWrapper(Builder b) {
        this.broker = b.broker;
        this.clientId = b.clientId;
        this.username = b.username;
        this.password = b.password;
        this.keepAlive = b.keepAlive;
        this.cleanSession = b.cleanSession;
        this.connectionTimeout = b.connectionTimeout;
        this.automaticReconnect = b.automaticReconnect;
    }

    // ==================== 工厂方法 ====================

    public static MqttClientWrapper create(String broker) {
        return builder().broker(broker).build();
    }

    public static Builder builder() { return new Builder(); }

    /**
     * 判断客户端是否已连接。
     *
     * @return 已连接返回 true
     */
    public boolean isConnected() {
        return connected.get() && mqttClient != null && mqttClient.isConnected();
    }

    // ==================== 启动/停止 ====================

    public MqttClientWrapper start() {
        try {
            mqttClient = new MqttClient(broker, clientId, new MemoryPersistence());
            mqttClient.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    connected.set(false);
                    log.warn("MQTT 连接断开: {}", cause.getMessage());
                    for (Consumer<Throwable> listener : disconnectListeners) {
                        try { listener.accept(cause); } catch (Exception e) { log.error("断开回调异常", e); }
                    }
                    for (Consumer<Throwable> listener : errorListeners) {
                        try { listener.accept(cause); } catch (Exception e) { log.error("错误回调异常", e); }
                    }
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
                    // 精确匹配
                    List<BiConsumer<String, String>> exactHandlers = topicHandlers.get(topic);
                    if (exactHandlers != null) {
                        for (BiConsumer<String, String> h : exactHandlers) {
                            try { h.accept(topic, payload); } catch (Exception e) {
                                log.error("消息处理异常", e);
                                for (Consumer<Throwable> listener : errorListeners) {
                                    try { listener.accept(e); } catch (Exception ex) { log.error("错误回调异常", ex); }
                                }
                            }
                        }
                    }
                    // 通配符匹配
                    for (Map.Entry<String, List<BiConsumer<String, String>>> entry : topicHandlers.entrySet()) {
                        if (!entry.getKey().equals(topic) && matchTopic(entry.getKey(), topic)) {
                            for (BiConsumer<String, String> h : entry.getValue()) {
                                try { h.accept(topic, payload); } catch (Exception e) {
                                    log.error("消息处理异常", e);
                                    for (Consumer<Throwable> listener : errorListeners) {
                                        try { listener.accept(e); } catch (Exception ex) { log.error("错误回调异常", ex); }
                                    }
                                }
                            }
                        }
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {}
            });

            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(cleanSession);
            options.setKeepAliveInterval(keepAlive);
            options.setConnectionTimeout(connectionTimeout);
            options.setAutomaticReconnect(automaticReconnect);
            if (username != null) {
                options.setUserName(username);
            }
            if (password != null) {
                options.setPassword(password.toCharArray());
            }
            mqttClient.connect(options);
            connected.set(true);
            log.info("MQTT 客户端连接成功: {}", broker);

            // 触发连接回调
            for (Runnable listener : connectListeners) {
                try { listener.run(); } catch (Exception e) { log.error("连接回调异常", e); }
            }
        } catch (Exception e) {
            throw new MqttClientException("MQTT 连接失败: " + broker, e);
        }
        return this;
    }

    public MqttClientWrapper shutdown() {
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                mqttClient.disconnect();
                mqttClient.close();
            }
            connected.set(false);

            // 触发断开回调
            for (Consumer<Throwable> listener : disconnectListeners) {
                try { listener.accept(new RuntimeException("客户端主动关闭")); } catch (Exception e) { log.error("断开回调异常", e); }
            }

            log.info("MQTT 客户端关闭");
        } catch (Exception e) {
            log.warn("MQTT 关闭异常: {}", e.getMessage());
        }
        return this;
    }

    @Override
    public void close() { shutdown(); }

    // ==================== 操作入口 ====================

    public SubscribeOperation subscribe() { return new SubscribeOperation(this); }
    public PublishOperation publish() { return new PublishOperation(this); }

    /**
     * 注册注解处理器。
     *
     * <p>扫描对象上的 @OnOpen/@OnClose/@OnMessage 注解并注册回调。</p>
     */
    public MqttClientWrapper register(Object handler) {
        Class<?> clazz = handler.getClass();
        boolean found = false;

        for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
            method.setAccessible(true);

            if (method.isAnnotationPresent(OnOpen.class)) {
                connectListeners.add(() -> invokeMethod(handler, method));
                found = true;
            } else if (method.isAnnotationPresent(OnClose.class)) {
                disconnectListeners.add(cause -> invokeMethod(handler, method));
                found = true;
            } else if (method.isAnnotationPresent(OnMessage.class)) {
                OnMessage ann = method.getAnnotation(OnMessage.class);
                String topic = ann.value();
                if (topic != null && !topic.isEmpty()) {
                    onMessage(topic, (t, msg) -> invokeMethod(handler, method, msg));
                } else {
                    onMessage("#", (t, msg) -> invokeMethod(handler, method, msg));
                }
                found = true;
            } else if (method.isAnnotationPresent(OnError.class)) {
                errorListeners.add(t -> {
                    try {
                        method.setAccessible(true);
                        method.invoke(handler, t);
                    } catch (Exception e) {
                        log.error("MQTT 客户端 @OnError 方法执行异常", e);
                    }
                });
                found = true;
            }
        }

        if (!found) {
            log.warn("未找到 @OnOpen/@OnClose/@OnMessage/@OnError 注解: {}", clazz.getSimpleName());
        } else {
            log.info("注册注解处理器: {}", clazz.getSimpleName());
        }
        return this;
    }

    // ==================== 事件监听 ====================

    public MqttClientWrapper onConnect(Runnable listener) {
        connectListeners.add(listener);
        return this;
    }

    public MqttClientWrapper onDisconnect(Consumer<Throwable> listener) {
        disconnectListeners.add(listener);
        return this;
    }

    public MqttClientWrapper onMessage(String topic, BiConsumer<String, String> handler) {
        topicHandlers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
        return this;
    }

    // ==================== Builder ====================

    public static class Builder {
        /** Broker */
        private String broker = "tcp://127.0.0.1:1883";
        /** 客户端ID */
        private String clientId = "mqtt-client-" + System.currentTimeMillis();
        /** Username */
        private String username;
        /** 密码 */
        private String password;
        /** Keepalive */
        private int keepAlive = 60;
        /** Clean会话 */
        private boolean cleanSession = true;
        /** Connection超时 */
        private int connectionTimeout = 10;
        /** Automaticreconnect */
        private boolean automaticReconnect = true;

        public Builder broker(String b) { this.broker = b; return this; }
        public Builder clientId(String id) { this.clientId = id; return this; }
        public Builder username(String u) { this.username = u; return this; }
        public Builder password(String p) { this.password = p; return this; }
        public Builder keepAlive(int sec) { this.keepAlive = sec; return this; }
        public Builder cleanSession(boolean c) { this.cleanSession = c; return this; }
        public Builder connectionTimeout(int sec) { this.connectionTimeout = sec; return this; }
        public Builder automaticReconnect(boolean r) { this.automaticReconnect = r; return this; }

        public MqttClientWrapper build() { return new MqttClientWrapper(this); }
    }

    // ==================== 订阅操作 ====================

    public static class SubscribeOperation {
        /** 客户端 */
        private final MqttClientWrapper client;
        /** Topic */
        private String topic;
        /** QOS */
        private int qos = 1;
        private BiConsumer<String, String> handler;

        SubscribeOperation(MqttClientWrapper client) { this.client = client; }

        public SubscribeOperation topic(String t) { this.topic = t; return this; }
        public SubscribeOperation qos(int q) { this.qos = q; return this; }
        public SubscribeOperation handler(BiConsumer<String, String> h) { this.handler = h; return this; }
        public SubscribeOperation onMessage(Consumer<String> h) {
            this.handler = (t, msg) -> h.accept(msg);
            return this;
        }

        public void start() {
            try {
                client.mqttClient.subscribe(topic, qos);
                if (handler != null) {
                    client.onMessage(topic, handler);
                }
                log.info("MQTT 订阅: topic={}, qos={}", topic, qos);
            } catch (MqttException e) {
                throw new MqttClientException("订阅失败: " + topic, e);
            }
        }

        public void stop() {
            try { client.mqttClient.unsubscribe(topic); }
            catch (MqttException e) { throw new MqttClientException("取消订阅失败", e); }
        }
    }

    // ==================== 发布操作 ====================

    public static class PublishOperation {
        /** 客户端 */
        private final MqttClientWrapper client;
        /** Topic */
        private String topic;
        /** Payload */
        private byte[] payload;
        /** QOS */
        private int qos = 1;
        /** Retained */
        private boolean retained = false;

        PublishOperation(MqttClientWrapper client) { this.client = client; }

        public PublishOperation topic(String t) { this.topic = t; return this; }
        public PublishOperation payload(String p) { this.payload = p.getBytes(StandardCharsets.UTF_8); return this; }
        public PublishOperation payload(byte[] p) { this.payload = p; return this; }
        public PublishOperation qos(int q) { this.qos = q; return this; }
        public PublishOperation retained(boolean r) { this.retained = r; return this; }
        public PublishOperation qos0() { this.qos = 0; return this; }
        public PublishOperation qos1() { this.qos = 1; return this; }
        public PublishOperation qos2() { this.qos = 2; return this; }

        public void send() {
            try {
                MqttMessage msg = new MqttMessage(payload);
                msg.setQos(qos);
                msg.setRetained(retained);
                client.mqttClient.publish(topic, msg);
            } catch (MqttException e) {
                throw new MqttClientException("发布失败: " + topic, e);
            }
        }

        public void sendAsync() {
            try {
                MqttMessage msg = new MqttMessage(payload);
                msg.setQos(qos);
                msg.setRetained(retained);
                client.mqttClient.publish(topic, msg);
            } catch (MqttException e) {
                throw new MqttClientException("异步发布失败: " + topic, e);
            }
        }

        /**
         * 同步发布：等待 PUBACK（QoS>0）或发送完成（QoS 0）。性能压测使用，避免丢消息。
         *
         * @param timeoutMs 最大等待毫秒
         */
        public void sendAndWait(long timeoutMs) {
            try {
                MqttMessage msg = new MqttMessage(payload);
                msg.setQos(qos);
                msg.setRetained(retained);
                org.eclipse.paho.client.mqttv3.MqttDeliveryToken token = client.mqttClient.getTopic(topic).publish(msg);
                token.waitForCompletion(timeoutMs);
            } catch (MqttException e) {
                throw new MqttClientException("同步发布失败: " + topic, e);
            }
        }
    }

    // ==================== 内部方法 ====================

    private static boolean matchTopic(String pattern, String topic) {
        String[] patternParts = pattern.split("/");
        String[] topicParts = topic.split("/");
        int p = 0, t = 0;
        while (p < patternParts.length && t < topicParts.length) {
            if ("#".equals(patternParts[p])) {
                return true;
            }
            if ("+".equals(patternParts[p])) {
                p++;
                t++;
            } else if (patternParts[p].equals(topicParts[t])) {
                p++;
                t++;
            } else {
                return false;
            }
        }
        return p == patternParts.length && t == topicParts.length;
    }

    private void invokeMethod(Object handler, Method method, Object... args) {
        try {
            method.invoke(handler, args);
        } catch (Exception e) {
            log.error("注解方法调用异常: {}.{}", handler.getClass().getSimpleName(), method.getName(), e);
        }
    }

    // ==================== 异常类 ====================

    public static class MqttClientException extends RuntimeException {
        public MqttClientException(String message, Throwable cause) { super(message, cause); }
    }
}
