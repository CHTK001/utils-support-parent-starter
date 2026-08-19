package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Spring Integration Handler — intercepts message channel send operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SpringIntegrationHandler extends AbstractAppHandler {

    /**
     * 消息 channel
     */
    private static final String MESSAGE_CHANNEL = "org/springframework/integration/channel/AbstractMessageChannel";
    /**
     * send methods
     */
    private static final String[] SEND_METHODS = {"send", "receive"};

    @Override
    /** Name */
    public String name() {
        return "spring-integration-handler";
    }

    @Override
    /** EnabledKey */
    protected String enabledKey() {
        return "spring-integration.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.SPRING_INTEGRATION;
    }

    @Override
    /** Protocol */
    protected Protocol protocol() {
        return Protocol.MESSAGE;
    }

    @Override
    /** 注册Interceptors */
    protected void registerInterceptors() {
        registerAll(MESSAGE_CHANNEL, SEND_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.MESSAGE)
                .software(Software.SPRING_INTEGRATION)
                .host("spring-integration")
                .port(0)
                .path("/")
                .build();
    }
}