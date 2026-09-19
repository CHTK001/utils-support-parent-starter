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

    @Override
    /** 名称 */
    public String name() {
        return "undertow-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "undertow.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.UNDERTOW;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(HTTP_HANDLER, HANDLE_REQUEST);
    }

    @Override
    /** 构建Target */
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