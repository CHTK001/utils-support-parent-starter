package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Ignite 处理器 — intercepts Ignite 缓存 operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class IgniteHandler extends AbstractAppHandler {

    /**
     * ignite 缓存
     */
    private static final String IGNITE_CACHE = "org/apache/ignite/IgniteCache";
    /**
     * 缓存 方法
     */
    private static final String[] CACHE_METHODS = {"get", "put", "remove", "replace", "getAndPut", "getAndRemove", "query"};

    @Override
    /** 名称 */
    public String name() {
        return "ignite-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "ignite.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.IGNITE;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.IGNITE;
    }

    @Override
    /** 注册拦截器 */
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