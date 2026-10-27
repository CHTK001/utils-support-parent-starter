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

    /**
     * 名称
    */
    @Override
    public String name() {
        return "ignite-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "ignite.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.IGNITE;
    }

    /**
     * 协议
    */
    @Override
    protected Protocol protocol() {
        return Protocol.IGNITE;
    }

    /**
     * 注册拦截器
    */
    @Override
    protected void registerInterceptors() {
        registerAll(IGNITE_CACHE, CACHE_METHODS);
    }

    /**
     * 构建Target
    */
    @Override
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