package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Kubernetes Handler — intercepts Kubernetes API calls.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class KubernetesHandler extends AbstractAppHandler {

    private static final String API_CLIENT = "io/kubernetes/client/openapi/ApiClient";
    private static final String[] API_METHODS = {"execute", "call"};

    @Override
    public String name() {
        return "kubernetes-handler";
    }

    @Override
    protected String enabledKey() {
        return "kubernetes.enabled";
    }

    @Override
    protected Software software() {
        return Software.KUBERNETES;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(API_CLIENT, API_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.KUBERNETES)
                .host("kubernetes")
                .port(443)
                .path("/")
                .build();
    }
}