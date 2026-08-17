package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * MQTT 应用层 Handler — 拦截 Eclipse Paho 客户端关键调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code org.eclipse.paho.client.mqttv3.MqttAsyncClient} — publish / subscribe / unsubscribe（异步客户端）</li>
 *   <li>{@code org.eclipse.paho.client.mqttv3.MqttClient} — publish / subscribe（同步客户端）</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：Paho 客户端不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MqttHandler extends AbstractAppHandler {

    /**
     * MqttAsyncClient 类内部名
     */
    private static final String MQTT_ASYNC_CLIENT = "org/eclipse/paho/client/mqttv3/MqttAsyncClient";

    /**
     * MqttClient 类内部名
     */
    private static final String MQTT_SYNC_CLIENT = "org/eclipse/paho/client/mqttv3/MqttClient";

    /**
     * MQTT 方法集合（发布/订阅/断开）
     */
    private static final String[] MQTT_METHODS = {"publish", "subscribe", "unsubscribe"};

    @Override
    public String name() {
        return "mqtt-handler";
    }

    @Override
    protected String enabledKey() {
        return "mqtt.enabled";
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
    protected Software softwareForEntry(InterceptContext ctx) {
        return Software.PAHO_MQTT;
    }

    @Override
    protected EndpointKind kindForEntry(InterceptContext ctx) {
        // publish 为生产者，subscribe 为消费者
        String method = ctx.getMethodName();
        return "subscribe".equals(method) || "unsubscribe".equals(method)
                ? EndpointKind.CONSUMER : EndpointKind.PRODUCER;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(MQTT_ASYNC_CLIENT, MQTT_METHODS);
        registerAll(MQTT_SYNC_CLIENT, MQTT_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        Object serverURI = instance != null ? findField(instance, "serverURI") : null;
        String url = serverURI != null ? String.valueOf(serverURI) : null;
        return Endpoint.builder()
                .kind(kindForEntry(ctx))
                .protocol(Protocol.MQTT)
                .software(Software.PAHO_MQTT)
                .host(parseUrlHost(url) != null ? parseUrlHost(url) : "mqtt")
                .port(parseUrlPort(url, Protocol.MQTT.defaultPort()))
                .path("/")
                .build();
    }
}