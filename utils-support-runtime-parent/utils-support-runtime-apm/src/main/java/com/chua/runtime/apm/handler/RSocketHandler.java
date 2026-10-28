package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * r套接字 处理器 — intercepts r套接字 请求/responder operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RSocketHandler extends AbstractAppHandler {

    /**
     * rsocket Requests
     */
    private static final String RSOCKET_REQUESTS = "io/rsocket/RSocket";
    /**
     * rsocket 方法
     */
    private static final String[] RSOCKET_METHODS =
            {"requestResponse", "requestStream", "requestChannel", "requestFireAndForget"};

    /**
     * 名称
    */
    @Override
    public String name() {
        return "rsocket-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "rsocket.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.RSOCKET;
    }

    /**
     * 协议
    */
    @Override
    protected Protocol protocol() {
        return Protocol.RSOCKET;
    }

    /**
     * 注册拦截器
    */
    @Override
    protected void registerInterceptors() {
        registerAll(RSOCKET_REQUESTS, RSOCKET_METHODS);
    }

    /**
     * 构建Target
    */
    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.RSOCKET)
                .software(Software.RSOCKET)
                .host("rsocket")
                .port(Protocol.RSOCKET.defaultPort())
                .path("/")
                .build();
    }
}