package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Hazelcast 应用层 Handler — 拦截 Hazelcast IMap 操作并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code com.hazelcast.map.IMap} — get / put / remove / query</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：Hazelcast 不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HazelcastHandler extends AbstractAppHandler {

    /**
     * imap class
     */
    private static final String IMAP_CLASS = "com/hazelcast/map/IMap";
    /**
     * 映射 methods
     */
    private static final String[] MAP_METHODS = {"get", "put", "remove", "replace", "putIfAbsent", "delete", "containsKey", "size"};

    @Override
    /** Name */
    public String name() {
        return "hazelcast-handler";
    }

    @Override
    /** EnabledKey */
    protected String enabledKey() {
        return "hazelcast.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.HAZELCAST;
    }

    @Override
    /** Protocol */
    protected Protocol protocol() {
        return Protocol.HAZELCAST;
    }

    @Override
    /** 注册Interceptors */
    protected void registerInterceptors() {
        registerAll(IMAP_CLASS, MAP_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HAZELCAST)
                .software(Software.HAZELCAST)
                .host("hazelcast")
                .port(Protocol.HAZELCAST.defaultPort())
                .path("/")
                .build();
    }
}