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

    /**
     * api 客户端
     */
    private static final String API_CLIENT = "io/kubernetes/client/openapi/ApiClient";
    /**
     * api methods
     */
    private static final String[] API_METHODS = {"execute", "call"};

    @Override
    /** Name */
    public String name() {
        return "kubernetes-handler";
    }

    @Override
    /** EnabledKey */
    protected String enabledKey() {
        return "kubernetes.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.KUBERNETES;
    }

    @Override
    /** Protocol */
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    /** 注册Interceptors */
    protected void registerInterceptors() {
        registerAll(API_CLIENT, API_METHODS);
    }

    @Override
    /** 构建Target */
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