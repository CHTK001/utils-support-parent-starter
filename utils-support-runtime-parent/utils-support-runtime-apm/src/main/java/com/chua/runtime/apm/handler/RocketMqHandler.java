package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * RocketMQ 应用层 Handler — 拦截 RocketMQ Producer / Consumer 关键调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code org.apache.rocketmq.client.producer.DefaultMQProducer} — send / sendOneway</li>
 *   <li>{@code org.apache.rocketmq.client.consumer.DefaultMQPushConsumer} — subscribe</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：RocketMQ 不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RocketMqHandler extends AbstractAppHandler {

    /**
     * DefaultMQProducer 类内部名
     */
    private static final String PRODUCER_CLASS = "org/apache/rocketmq/client/producer/DefaultMQProducer";

    /**
     * DefaultMQPushConsumer 类内部名
     */
    private static final String CONSUMER_CLASS = "org/apache/rocketmq/client/consumer/DefaultMQPushConsumer";

    /**
     * Producer 方法集合
     */
    private static final String[] PRODUCER_METHODS = {"send", "sendOneway", "sendInTransaction"};

    /**
     * Consumer 方法集合
     */
    private static final String[] CONSUMER_METHODS = {"subscribe", "unsubscribe"};

    @Override
    public String name() {
        return "rocketmq-handler";
    }

    @Override
    protected String enabledKey() {
        return "rocketmq.enabled";
    }

    @Override
    protected Software software() {
        return Software.ROCKETMQ_PRODUCER;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.ROCKETMQ;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(PRODUCER_CLASS, PRODUCER_METHODS);
        registerAll(CONSUMER_CLASS, CONSUMER_METHODS);
    }

    @Override
    protected Software softwareForEntry(InterceptContext ctx) {
        return CONSUMER_CLASS.equals(ctx.getClassName()) ? Software.ROCKETMQ_CONSUMER : Software.ROCKETMQ_PRODUCER;
    }

    @Override
    protected EndpointKind kindForEntry(InterceptContext ctx) {
        return CONSUMER_CLASS.equals(ctx.getClassName()) ? EndpointKind.CONSUMER : EndpointKind.PRODUCER;
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        Object producer = instance;
        String namesrv = null;
        if (producer != null) {
            Object addr = findField(producer, "clientConfig");
            if (addr == null) {
                addr = findField(producer, "defaultMQProducerImpl");
            }
            if (addr != null) {
                namesrv = String.valueOf(findField(addr, "namespace"));
            }
        }
        String host = "rocketmq";
        int port = Protocol.ROCKETMQ.defaultPort();
        if (namesrv != null && namesrv.contains(":")) {
            String[] hp = namesrv.split(":");
            host = hp[0];
            try {
                port = Integer.parseInt(hp[1]);
            } catch (NumberFormatException e) {
                port = Protocol.ROCKETMQ.defaultPort();
            }
        }
        return Endpoint.builder()
                .kind(kindForEntry(ctx))
                .protocol(Protocol.ROCKETMQ)
                .software(softwareForEntry(ctx))
                .host(host)
                .port(port)
                .path("/")
                .build();
    }
}