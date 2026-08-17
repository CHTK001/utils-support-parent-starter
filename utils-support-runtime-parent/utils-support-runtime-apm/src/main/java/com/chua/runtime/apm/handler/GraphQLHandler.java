package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * GraphQL Handler — intercepts GraphQL execution.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class GraphQLHandler extends AbstractAppHandler {

    private static final String GRAPHQL = "graphql/GraphQL";
    private static final String[] EXECUTE_METHODS = {"execute", "executeAsync"};

    @Override
    public String name() {
        return "graphql-handler";
    }

    @Override
    protected String enabledKey() {
        return "graphql.enabled";
    }

    @Override
    protected Software software() {
        return Software.GRAPHQL_JAVA;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(GRAPHQL, EXECUTE_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.GRAPHQL_JAVA)
                .host("graphql")
                .port(80)
                .path("/")
                .build();
    }
}