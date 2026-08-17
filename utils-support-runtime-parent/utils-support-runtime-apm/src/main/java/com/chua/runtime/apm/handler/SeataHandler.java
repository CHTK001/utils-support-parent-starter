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

    private static final String GLOBAL_TRANSACTION = "io/seata/tm/api/GlobalTransaction";
    private static final String[] TX_METHODS = {"begin", "commit", "rollback", "getStatus"};

    @Override
    public String name() {
        return "seata-handler";
    }

    @Override
    protected String enabledKey() {
        return "seata.enabled";
    }

    @Override
    protected Software software() {
        return Software.SEATA;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.INTERNAL;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(GLOBAL_TRANSACTION, TX_METHODS);
    }

    @Override
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