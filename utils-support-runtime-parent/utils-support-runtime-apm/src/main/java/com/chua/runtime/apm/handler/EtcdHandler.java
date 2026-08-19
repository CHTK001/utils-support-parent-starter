package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Etcd Handler — intercepts Etcd KV operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class EtcdHandler extends AbstractAppHandler {

    /**
     * kv 客户端
     */
    private static final String KV_CLIENT = "io/etcd/jetcd/KV";
    /**
     * kv methods
     */
    private static final String[] KV_METHODS = {"put", "get", "delete", "compact"};

    @Override
    /** Name */
    public String name() {
        return "etcd-handler";
    }

    @Override
    /** EnabledKey */
    protected String enabledKey() {
        return "etcd.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.ETCD;
    }

    @Override
    /** Protocol */
    protected Protocol protocol() {
        return Protocol.ETCD;
    }

    @Override
    /** 注册Interceptors */
    protected void registerInterceptors() {
        registerAll(KV_CLIENT, KV_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.ETCD)
                .software(Software.ETCD)
                .host("etcd")
                .port(Protocol.ETCD.defaultPort())
                .path("/")
                .build();
    }
}