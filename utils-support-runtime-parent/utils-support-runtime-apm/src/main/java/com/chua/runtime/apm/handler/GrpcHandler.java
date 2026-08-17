package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * gRPC 应用层 Handler — 拦截 gRPC Java Stub 进出站调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code io.grpc.stub.ClientCalls} — unaryCall / serverStreamingCall / clientStreamingCall / bidiStreamingCall</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：gRPC 不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class GrpcHandler extends AbstractAppHandler {

    /**
     * ClientCalls 类内部名
     */
    private static final String CLIENT_CALLS = "io/grpc/stub/ClientCalls";

    /**
     * ClientCalls 方法集合（进出站调用入口）
     */
    private static final String[] CALL_METHODS = {
            "asyncUnaryCall", "asyncServerStreamingCall", "asyncClientStreamingCall",
            "asyncBidiStreamingCall", "blockingUnaryCall", "blockingServerStreamingCall",
            "futureUnaryCall"
    };

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
        registerAll(CLIENT_CALLS, CALL_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.GRPC)
                .software(Software.GRPC)
                .host("grpc")
                .port(0)
                .path("/")
                .build();
    }
}