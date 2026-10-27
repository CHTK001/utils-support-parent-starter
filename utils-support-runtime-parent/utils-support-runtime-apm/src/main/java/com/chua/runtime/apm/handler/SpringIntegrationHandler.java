package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Spring Integration 处理器 — intercepts 消息 通道 发送 operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SpringIntegrationHandler extends AbstractAppHandler {

    /**
     * 消息 通道
     */
    private static final String MESSAGE_CHANNEL = "org/springframework/integration/channel/AbstractMessageChannel";
    /**
     * 发送 方法
     */
    private static final String[] SEND_METHODS = {"send", "receive"};

    /**
     * 名称
    */
    @Override
    public String name() {
        return "spring-integration-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "spring-integration.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.SPRING_INTEGRATION;
    }

    /**
     * 协议
    */
    @Override
    protected Protocol protocol() {
        return Protocol.MESSAGE;
    }

    /**
     * 注册拦截器
    */
    @Override
    protected void registerInterceptors() {
        registerAll(MESSAGE_CHANNEL, SEND_METHODS);
    }

    /**
     * 构建Target
    */
    @Override
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