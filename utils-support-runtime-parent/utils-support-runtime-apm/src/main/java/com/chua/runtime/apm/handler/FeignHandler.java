package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Feign Handler — intercepts Feign client requests.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FeignHandler extends AbstractAppHandler {

    /**
     * feign 客户端
     */
    private static final String FEIGN_CLIENT = "feign/Client";
    /**
     * execute methods
     */
    private static final String[] EXECUTE_METHODS = {"execute"};

    @Override
    public String name() {
        return "feign-handler";
    }

    @Override
    protected String enabledKey() {
        return "feign.enabled";
    }

    @Override
    protected Software software() {
        return Software.FEIGN;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(FEIGN_CLIENT, EXECUTE_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.FEIGN)
                .host("http")
                .port(80)
                .path("/")
                .build();
    }
}