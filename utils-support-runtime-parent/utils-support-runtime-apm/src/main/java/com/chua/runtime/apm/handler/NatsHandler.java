package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * NATS 处理器 — intercepts NATS 发布/订阅 operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class NatsHandler extends AbstractAppHandler {

    /**
     * NATS 连接
     */
    private static final String NATS_CONNECTION = "io/nats/client/Connection";
    /**
     * 发布 方法
     */
    private static final String[] PUBLISH_METHODS = {"publish"};
    /**
     * 订阅 方法
     */
    private static final String[] SUBSCRIBE_METHODS = {"subscribe"};

    /**
     * 名称
    */
    @Override
    public String name() {
        return "nats-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "nats.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.NATS;
    }

    /**
     * 协议
    */
    @Override
    protected Protocol protocol() {
        return Protocol.NATS;
    }

    /**
     * 注册拦截器
    */
    @Override
    protected void registerInterceptors() {
        registerAll(NATS_CONNECTION, PUBLISH_METHODS);
        registerAll(NATS_CONNECTION, SUBSCRIBE_METHODS);
    }

    /**
     * 构建Target
    */
    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.NATS)
                .software(Software.NATS)
                .host("nats")
                .port(Protocol.NATS.defaultPort())
                .path("/")
                .build();
    }
}