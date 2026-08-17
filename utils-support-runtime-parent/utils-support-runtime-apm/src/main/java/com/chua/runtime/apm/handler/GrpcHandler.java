package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * gRPC Handler — intercepts gRPC client/server calls.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class GrpcHandler extends AbstractAppHandler {

    private static final String CLIENT_CALL = "io/grpc/ClientCall";
    private static final String SERVER_CALL = "io/grpc/ServerCall";
    private static final String[] CLIENT_METHODS = {"start", "sendMessage", "request", "halfClose"};
    private static final String[] SERVER_METHODS = {"sendMessage", "close"};

    @Override
    public String name() {
        return "grpc-handler";
    }

    @Override
    protected String enabledKey() {
        return "grpc.enabled";
    }

    @Override
    protected Software software() {
        return Software.GRPC;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.GRPC;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(CLIENT_CALL, CLIENT_METHODS);
        registerAll(SERVER_CALL, SERVER_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.GRPC)
                .software(Software.GRPC)
                .host("grpc")
                .port(Protocol.GRPC.defaultPort())
                .path("/")
                .build();
    }
}