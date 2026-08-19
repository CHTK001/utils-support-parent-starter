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

    /**
     * 客户端 call
     */
    private static final String CLIENT_CALL = "io/grpc/ClientCall";
    /**
     * 服务器 call
     */
    private static final String SERVER_CALL = "io/grpc/ServerCall";
    /**
     * 客户端 methods
     */
    private static final String[] CLIENT_METHODS = {"start", "sendMessage", "request", "halfClose"};
    /**
     * 服务器 methods
     */
    private static final String[] SERVER_METHODS = {"sendMessage", "close"};

    @Override
    /** Name */
    public String name() {
        return "grpc-handler";
    }

    @Override
    /** EnabledKey */
    protected String enabledKey() {
        return "grpc.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.GRPC;
    }

    @Override
    /** Protocol */
    protected Protocol protocol() {
        return Protocol.GRPC;
    }

    @Override
    /** 注册Interceptors */
    protected void registerInterceptors() {
        registerAll(CLIENT_CALL, CLIENT_METHODS);
        registerAll(SERVER_CALL, SERVER_METHODS);
    }

    @Override
    /** 构建Target */
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