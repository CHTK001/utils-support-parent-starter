package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Pulsar 应用层 Handler — 拦截 Apache Pulsar Client 关键调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code org.apache.pulsar.client.api.Producer} — send / sendAsync（生产）</li>
 *   <li>{@code org.apache.pulsar.client.api.Consumer} — receive / acknowledge（消费）</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：Pulsar 不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PulsarHandler extends AbstractAppHandler {

    /**
     * Producer 接口 / 实现类内部名
     */
    private static final String PRODUCER_CLASS = "org/apache/pulsar/client/api/Producer";

    /**
     * Consumer 接口 / 实现类内部名
     */
    private static final String CONSUMER_CLASS = "org/apache/pulsar/client/api/Consumer";

    /**
     * Producer 方法集合
     */
    private static final String[] PRODUCER_METHODS = {"send", "sendAsync"};

    /**
     * Consumer 方法集合
     */
    private static final String[] CONSUMER_METHODS = {"receive", "receiveAsync", "acknowledge"};

    @Override
    public String name() {
        return "pulsar-handler";
    }

    @Override
    protected String enabledKey() {
        return "pulsar.enabled";
    }

    @Override
    protected Software software() {
        return Software.PULSAR;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.PULSAR;
    }

    @Override
    protected Software softwareForEntry(InterceptContext ctx) {
        return Software.PULSAR;
    }

    @Override
    protected EndpointKind kindForEntry(InterceptContext ctx) {
        String cn = ctx.getClassName();
        return cn != null && cn.contains("Producer") ? EndpointKind.PRODUCER : EndpointKind.CONSUMER;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(PRODUCER_CLASS, PRODUCER_METHODS);
        registerAll(CONSUMER_CLASS, CONSUMER_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        Object conf = instance != null ? findField(instance, "conf") : null;
        String url = conf != null ? String.valueOf(findField(conf, "serviceUrl")) : null;
        return Endpoint.builder()
                .kind(kindForEntry(ctx))
                .protocol(Protocol.PULSAR)
                .software(Software.PULSAR)
                .host(parseUrlHost(url) != null ? parseUrlHost(url) : "pulsar")
                .port(parseUrlPort(url, Protocol.PULSAR.defaultPort()))
                .path("/")
                .build();
    }
}