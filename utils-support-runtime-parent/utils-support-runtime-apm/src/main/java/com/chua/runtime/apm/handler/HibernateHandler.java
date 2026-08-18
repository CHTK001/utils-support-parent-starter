package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Hibernate Handler — intercepts Hibernate ORM session operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HibernateHandler extends AbstractAppHandler {

    /**
     * SESSION
     */
    private static final String SESSION = "org/hibernate/Session";
    /**
     * 会话 methods
     */
    private static final String[] SESSION_METHODS = {"save", "update", "delete", "load", "get", "merge", "persist"};

    @Override
    public String name() {
        return "hibernate-handler";
    }

    @Override
    protected String enabledKey() {
        return "hibernate.enabled";
    }

    @Override
    protected Software software() {
        return Software.HIBERNATE;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.SQL;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(SESSION, SESSION_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.SQL)
                .software(Software.HIBERNATE)
                .host("hibernate")
                .port(0)
                .path("/")
                .build();
    }
}