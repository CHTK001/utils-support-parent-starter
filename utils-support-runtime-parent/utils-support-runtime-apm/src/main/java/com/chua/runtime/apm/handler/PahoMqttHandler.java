package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Paho MQTT 处理器 — intercepts Eclipse Paho MQTT 客户端 operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PahoMqttHandler extends AbstractAppHandler {

    /**
     * MQTT 客户端
     */
    private static final String MQTT_CLIENT = "org/eclipse/paho/client/mqttv3/MqttClient";
    /**
     * OPERATIONS
     */
    private static final String[] OPERATIONS = {"connect", "publish", "subscribe", "unsubscribe", "disconnect"};

    /**
     * 名称
    */
    @Override
    public String name() {
        return "paho-mqtt-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "paho-mqtt.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.PAHO_MQTT;
    }

    /**
     * 协议
    */
    @Override
    protected Protocol protocol() {
        return Protocol.MQTT;
    }

    /**
     * 注册拦截器
    */
    @Override
    protected void registerInterceptors() {
        registerAll(MQTT_CLIENT, OPERATIONS);
    }

    /**
     * 构建Target
    */
    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.MQTT)
                .software(Software.PAHO_MQTT)
                .host("mqtt")
                .port(Protocol.MQTT.defaultPort())
                .path("/")
                .build();
    }
}