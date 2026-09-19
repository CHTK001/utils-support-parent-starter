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

    @Override
    /** 名称 */
    public String name() {
        return "paho-mqtt-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "paho-mqtt.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.PAHO_MQTT;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.MQTT;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(MQTT_CLIENT, OPERATIONS);
    }

    @Override
    /** 构建Target */
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