package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Jetty Handler — intercepts request handling in Eclipse Jetty.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class JettyHandler extends AbstractAppHandler {

    private static final String SERVER = "org/eclipse/jetty/server/Server";
    private static final String[] HANDLE_METHODS = {"handle"};

    @Override
    public String name() {
        return "jetty-handler";
    }

    @Override
    protected String enabledKey() {
        return "jetty.enabled";
    }

    @Override
    protected Software software() {
        return Software.JETTY;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(SERVER, HANDLE_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.JETTY)
                .host("jetty")
                .port(8080)
                .path("/")
                .build();
    }
}