package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Vert.x Handler — intercepts Vert.x HTTP server/client operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class VertxHandler extends AbstractAppHandler {

    private static final String HTTP_SERVER_REQUEST = "io/vertx/core/http/HttpServerRequest";
    private static final String HTTP_CLIENT_REQUEST = "io/vertx/core/http/HttpClientRequest";
    private static final String[] SERVER_METHODS = {"handler", "body", "params", "headers"};
    private static final String[] CLIENT_METHODS = {"send", "end", "putHeader"};

    @Override
    public String name() {
        return "vertx-handler";
    }

    @Override
    protected String enabledKey() {
        return "vertx.enabled";
    }

    @Override
    protected Software software() {
        return Software.VERTX;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(HTTP_SERVER_REQUEST, SERVER_METHODS);
        registerAll(HTTP_CLIENT_REQUEST, CLIENT_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.VERTX)
                .host("vertx")
                .port(8080)
                .path("/")
                .build();
    }
}