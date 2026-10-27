package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Play 框架 处理器 — intercepts Play HTTP 请求 处理.
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
     * 请求 处理器
     */
    private static final String REQUEST_HANDLER = "play/http/RequestHandler";
    /**
     * router 方法
     */
    private static final String[] ROUTER_METHODS = {"routeRequest"};
    /**
     * 处理器 方法
     */
    private static final String[] HANDLER_METHODS = {"handlerForRequest", "handleRequest"};

    /**
     * 名称
    */
    @Override
    public String name() {
        return "play-framework-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "play-framework.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.PLAY;
    }

    /**
     * 协议
    */
    @Override
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    /**
     * 注册拦截器
    */
    @Override
    protected void registerInterceptors() {
        registerAll(ROUTER, ROUTER_METHODS);
        registerAll(REQUEST_HANDLER, HANDLER_METHODS);
    }

    /**
     * 构建Target
    */
    @Override
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