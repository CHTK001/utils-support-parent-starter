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

    private static final String KV_CLIENT = "io/etcd/jetcd/KV";
    private static final String[] KV_METHODS = {"put", "get", "delete", "compact"};

    @Override
    public String name() {
        return "etcd-handler";
    }

    @Override
    protected String enabledKey() {
        return "etcd.enabled";
    }

    @Override
    protected Software software() {
        return Software.ETCD;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.ETCD;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(KV_CLIENT, KV_METHODS);
    }

    @Override
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