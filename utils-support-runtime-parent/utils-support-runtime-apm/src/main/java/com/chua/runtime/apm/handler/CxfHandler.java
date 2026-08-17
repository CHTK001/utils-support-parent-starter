package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Apache CXF Handler — intercepts CXF WebService client invocation.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CxfHandler extends AbstractAppHandler {

    private static final String INVOKER = "org/apache/cxf/endpoint/ClientImpl";
    private static final String PROXY = "org/apache/cxf/frontend/ClientProxy";
    private static final String[] INVOKE_METHODS = {"invoke"};
    private static final String[] PROXY_METHODS = {"invoke"};

    @Override
    public String name() {
        return "cxf-handler";
    }

    @Override
    protected String enabledKey() {
        return "cxf.enabled";
    }

    @Override
    protected Software software() {
        return Software.CXF;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(INVOKER, INVOKE_METHODS);
        registerAll(PROXY, PROXY_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.CXF)
                .host("cxf")
                .port(80)
                .path("/")
                .build();
    }
}