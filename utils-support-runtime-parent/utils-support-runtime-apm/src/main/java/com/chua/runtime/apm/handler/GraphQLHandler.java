package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * 图计算ql 处理器 — intercepts 图计算ql 执行.
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
     * 执行 方法
     */
    private static final String[] EXECUTE_METHODS = {"execute", "executeAsync"};

    @Override
    /** 名称 */
    public String name() {
        return "graphql-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "graphql.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.GRAPHQL_JAVA;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    /** 注册拦截器 */
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