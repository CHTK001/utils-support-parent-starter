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

    /**
     * GRAPHQL
     */
    private static final String GRAPHQL = "graphql/GraphQL";
    /**
     * execute methods
     */
    private static final String[] EXECUTE_METHODS = {"execute", "executeAsync"};

    @Override
    /** Name */
    public String name() {
        return "graphql-handler";
    }

    @Override
    /** EnabledKey */
    protected String enabledKey() {
        return "graphql.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.GRAPHQL_JAVA;
    }

    @Override
    /** Protocol */
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    /** 注册Interceptors */
    protected void registerInterceptors() {
        registerAll(GRAPHQL, EXECUTE_METHODS);
    }

    @Override
    /** 构建Target */
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