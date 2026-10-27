package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Undertow 处理器 — intercepts 请求 处理 入 Undertow.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class UndertowHandler extends AbstractAppHandler {

    /**
     * HTTP 处理器
     */
    private static final String HTTP_HANDLER = "io/undertow/server/HttpHandler";
    /**
     * 处理 请求
     */
    private static final String[] HANDLE_REQUEST = {"handleRequest"};

    /**
     * 名称
    */
    @Override
    public String name() {
        return "undertow-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "undertow.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.UNDERTOW;
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
        registerAll(HTTP_HANDLER, HANDLE_REQUEST);
    }

    /**
     * 构建Target
    */
    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.UNDERTOW)
                .host("undertow")
                .port(8080)
                .path("/")
                .build();
    }
}