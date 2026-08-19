package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Play Framework Handler — intercepts Play HTTP request handling.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PlayFrameworkHandler extends AbstractAppHandler {

    /**
     * ROUTER
     */
    private static final String ROUTER = "play/core/routing/Router";
    /**
     * 请求 handler
     */
    private static final String REQUEST_HANDLER = "play/http/RequestHandler";
    /**
     * router methods
     */
    private static final String[] ROUTER_METHODS = {"routeRequest"};
    /**
     * handler methods
     */
    private static final String[] HANDLER_METHODS = {"handlerForRequest", "handleRequest"};

    @Override
    /** Name */
    public String name() {
        return "play-framework-handler";
    }

    @Override
    /** EnabledKey */
    protected String enabledKey() {
        return "play-framework.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.PLAY;
    }

    @Override
    /** Protocol */
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    /** 注册Interceptors */
    protected void registerInterceptors() {
        registerAll(ROUTER, ROUTER_METHODS);
        registerAll(REQUEST_HANDLER, HANDLER_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.PLAY)
                .host("play")
                .port(9000)
                .path("/")
                .build();
    }
}