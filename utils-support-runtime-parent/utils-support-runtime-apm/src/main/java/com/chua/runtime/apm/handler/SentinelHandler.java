package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Sentinel 处理器 — intercepts Sentinel resource entry/exit.
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
     * entry 方法
     */
    private static final String[] ENTRY_METHODS = {"entry", "asyncEntry"};
    /**
     * exit 方法
     */
    private static final String[] EXIT_METHODS = {"exit"};

    /**
     * 名称
    */
    @Override
    public String name() {
        return "sentinel-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "sentinel.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.SENTINEL;
    }

    /**
     * 协议
    */
    @Override
    protected Protocol protocol() {
        return Protocol.INTERNAL;
    }

    /**
     * 注册拦截器
    */
    @Override
    protected void registerInterceptors() {
        registerAll(SPH_U, ENTRY_METHODS);
        registerAll(SPH_ENTRY, EXIT_METHODS);
    }

    /**
     * 构建Target
    */
    @Override
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