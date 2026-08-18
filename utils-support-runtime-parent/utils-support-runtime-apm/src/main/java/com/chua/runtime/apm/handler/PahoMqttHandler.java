package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Paho MQTT Handler — intercepts Eclipse Paho MQTT client operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PahoMqttHandler extends AbstractAppHandler {

    /**
     * mqtt 客户端
     */
    private static final String MQTT_CLIENT = "org/eclipse/paho/client/mqttv3/MqttClient";
    /**
     * OPERATIONS
     */
    private static final String[] OPERATIONS = {"connect", "publish", "subscribe", "unsubscribe", "disconnect"};

    @Override
    public String name() {
        return "paho-mqtt-handler";
    }

    @Override
    protected String enabledKey() {
        return "paho-mqtt.enabled";
    }

    @Override
    protected Software software() {
        return Software.PAHO_MQTT;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.MQTT;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(MQTT_CLIENT, OPERATIONS);
    }

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