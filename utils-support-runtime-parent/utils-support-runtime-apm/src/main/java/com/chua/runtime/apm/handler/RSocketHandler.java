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
    private static final String[] RSOCKET_METHODS = {"requestResponse", "requestStream", "requestChannel", "requestFireAndForget"};

    @Override
    /** 名称 */
    public String name() {
        return "rsocket-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "rsocket.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.RSOCKET;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.RSOCKET;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(RSOCKET_REQUESTS, RSOCKET_METHODS);
    }

    @Override
    /** 构建Target */
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