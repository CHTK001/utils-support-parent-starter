package com.chua.runtime.apm.handler;

import com.chua.runtime.plugin.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * RabbitMQ 应用层 Handler — 拦截 RabbitMQ Java Client 关键调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code com.rabbitmq.client.impl.ChannelN} — basicPublish / basicConsume / basicGet / basicAck 等</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：RabbitMQ 客户端不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RabbitMqHandler extends AbstractAppHandler {

    /**
     * ChannelN 类内部名
     */
    private static final String CHANNEL_CLASS = "com/rabbitmq/client/impl/ChannelN";

    /**
     * Channel 方法集合（生产/消费/确认）
     */
    private static final String[] CHANNEL_METHODS = {
            "basicPublish", "basicConsume", "basicGet", "basicAck", "basicNack",
            "basicReject", "basicQos", "basicCancel", "exchangeDeclare", "queueDeclare",
            "queueBind", "confirmSelect", "txSelect", "txCommit", "txRollback"
    };

    /**
     * Channel 内部名（RabbitMQ 方对 Channel 接口的别名）
     */
    private static final String CONNECTION_CLASS = "com/rabbitmq/client/impl/ConnectionImpl";

    @Override
    public String name() {
        return "rabbitmq-handler";
    }

    @Override
    protected String enabledKey() {
        return "rabbitmq.enabled";
    }

    @Override
    protected Software software() {
        return Software.RABBITMQ_CLIENT;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.RABBITMQ;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(CHANNEL_CLASS, CHANNEL_METHODS);
        register(CONNECTION_CLASS, "createChannel");
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        Object connection = findField(instance, "connection");
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.RABBITMQ)
                .software(Software.RABBITMQ_CLIENT)
                .host("rabbitmq")
                .port(Protocol.RABBITMQ.defaultPort())
                .path("/")
                .build();
    }
}