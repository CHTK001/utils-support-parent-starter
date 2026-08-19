package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Sentinel Handler — intercepts Sentinel resource entry/exit.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SentinelHandler extends AbstractAppHandler {

    /**
     * sph u
     */
    private static final String SPH_U = "com/alibaba/csp/sentinel/SphU";
    /**
     * sph entry
     */
    private static final String SPH_ENTRY = "com/alibaba/csp/sentinel/Entry";
    /**
     * entry methods
     */
    private static final String[] ENTRY_METHODS = {"entry", "asyncEntry"};
    /**
     * exit methods
     */
    private static final String[] EXIT_METHODS = {"exit"};

    @Override
    /** Name */
    public String name() {
        return "sentinel-handler";
    }

    @Override
    /** EnabledKey */
    protected String enabledKey() {
        return "sentinel.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.SENTINEL;
    }

    @Override
    /** Protocol */
    protected Protocol protocol() {
        return Protocol.INTERNAL;
    }

    @Override
    /** 注册Interceptors */
    protected void registerInterceptors() {
        registerAll(SPH_U, ENTRY_METHODS);
        registerAll(SPH_ENTRY, EXIT_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.INTERNAL)
                .software(Software.SENTINEL)
                .host("sentinel")
                .port(0)
                .path("/")
                .build();
    }
}