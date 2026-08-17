package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * RSocket Handler — intercepts RSocket requester/responder operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RSocketHandler extends AbstractAppHandler {

    private static final String RSOCKET_REQUESTS = "io/rsocket/RSocket";
    private static final String[] RSOCKET_METHODS = {"requestResponse", "requestStream", "requestChannel", "requestFireAndForget"};

    @Override
    public String name() {
        return "rsocket-handler";
    }

    @Override
    protected String enabledKey() {
        return "rsocket.enabled";
    }

    @Override
    protected Software software() {
        return Software.RSOCKET;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.RSOCKET;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(RSOCKET_REQUESTS, RSOCKET_METHODS);
    }

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