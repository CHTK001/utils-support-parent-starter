package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Seata Handler — intercepts Seata global transaction begin/commit/rollback.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SeataHandler extends AbstractAppHandler {

    /**
     * global transaction
     */
    private static final String GLOBAL_TRANSACTION = "io/seata/tm/api/GlobalTransaction";
    /**
     * tx methods
     */
    private static final String[] TX_METHODS = {"begin", "commit", "rollback", "getStatus"};

    @Override
    /** Name */
    public String name() {
        return "seata-handler";
    }

    @Override
    /** EnabledKey */
    protected String enabledKey() {
        return "seata.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.SEATA;
    }

    @Override
    /** Protocol */
    protected Protocol protocol() {
        return Protocol.INTERNAL;
    }

    @Override
    /** 注册Interceptors */
    protected void registerInterceptors() {
        registerAll(GLOBAL_TRANSACTION, TX_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.INTERNAL)
                .software(Software.SEATA)
                .host("seata")
                .port(0)
                .path("/")
                .build();
    }
}