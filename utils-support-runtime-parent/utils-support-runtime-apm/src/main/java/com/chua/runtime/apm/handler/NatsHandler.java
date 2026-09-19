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

    @Override
    /**
     * 名称
    */
    public String name() {
        return "nats-handler";
    }

    @Override
    /**
     * 已启用键
    */
    protected String enabledKey() {
        return "nats.enabled";
    }

    @Override
    /**
     * Software
    */
    protected Software software() {
        return Software.NATS;
    }

    @Override
    /**
     * 协议
    */
    protected Protocol protocol() {
        return Protocol.NATS;
    }

    @Override
    /**
     * 注册拦截器
    */
    protected void registerInterceptors() {
        registerAll(NATS_CONNECTION, PUBLISH_METHODS);
        registerAll(NATS_CONNECTION, SUBSCRIBE_METHODS);
    }

    @Override
    /**
     * 构建Target
    */
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