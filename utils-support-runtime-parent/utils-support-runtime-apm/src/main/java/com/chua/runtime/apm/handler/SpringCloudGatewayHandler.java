package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Spring Cloud Gateway Handler — intercepts gateway filter chain execution.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SpringCloudGatewayHandler extends AbstractAppHandler {

    private static final String FILTERING_HANDLER = "org/springframework/cloud/gateway/handler/FilteringWebHandler";
    private static final String[] HANDLE_METHODS = {"handle"};

    @Override
    public String name() {
        return "spring-cloud-gateway-handler";
    }

    @Override
    protected String enabledKey() {
        return "spring-cloud-gateway.enabled";
    }

    @Override
    protected Software software() {
        return Software.SPRING_CLOUD_GATEWAY;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(FILTERING_HANDLER, HANDLE_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.SPRING_CLOUD_GATEWAY)
                .host("gateway")
                .port(80)
                .path("/")
                .build();
    }
}