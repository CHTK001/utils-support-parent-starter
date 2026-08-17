package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * JDK HttpServer Handler — intercepts incoming HTTP requests handled by com.sun.net.httpserver.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class JdkHttpServerHandler extends AbstractAppHandler {

    private static final String HTTP_HANDLER = "com/sun/net/httpserver/HttpHandler";
    private static final String[] HANDLE_METHODS = {"handle"};

    @Override
    public String name() {
        return "jdk-http-server-handler";
    }

    @Override
    protected String enabledKey() {
        return "jdk-http-server.enabled";
    }

    @Override
    protected Software software() {
        return Software.JDK_HTTP_SERVER;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(HTTP_HANDLER, HANDLE_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.JDK_HTTP_SERVER)
                .host("http")
                .port(80)
                .path("/")
                .build();
    }
}