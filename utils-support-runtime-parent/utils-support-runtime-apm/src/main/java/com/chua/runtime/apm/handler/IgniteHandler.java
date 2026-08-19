package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Ignite Handler — intercepts Ignite cache operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class IgniteHandler extends AbstractAppHandler {

    /**
     * ignite cache
     */
    private static final String IGNITE_CACHE = "org/apache/ignite/IgniteCache";
    /**
     * cache methods
     */
    private static final String[] CACHE_METHODS = {"get", "put", "remove", "replace", "getAndPut", "getAndRemove", "query"};

    @Override
    /** Name */
    public String name() {
        return "ignite-handler";
    }

    @Override
    /** EnabledKey */
    protected String enabledKey() {
        return "ignite.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.IGNITE;
    }

    @Override
    /** Protocol */
    protected Protocol protocol() {
        return Protocol.IGNITE;
    }

    @Override
    /** 注册Interceptors */
    protected void registerInterceptors() {
        registerAll(IGNITE_CACHE, CACHE_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.IGNITE)
                .software(Software.IGNITE)
                .host("ignite")
                .port(Protocol.IGNITE.defaultPort())
                .path("/")
                .build();
    }
}