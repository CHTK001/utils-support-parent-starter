package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Undertow Handler — intercepts request handling in Undertow.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class UndertowHandler extends AbstractAppHandler {

    /**
     * HTTP handler
     */
    private static final String HTTP_HANDLER = "io/undertow/server/HttpHandler";
    /**
     * handle 请求
     */
    private static final String[] HANDLE_REQUEST = {"handleRequest"};

    @Override
    /** Name */
    public String name() {
        return "undertow-handler";
    }

    @Override
    /** EnabledKey */
    protected String enabledKey() {
        return "undertow.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.UNDERTOW;
    }

    @Override
    /** Protocol */
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    /** 注册Interceptors */
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