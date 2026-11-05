// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
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

    /**
     * 名称
    */
    @Override
    public String name() {
        return "graphql-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "graphql.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.GRAPHQL_JAVA;
    }

    /**
     * 协议
    */
    @Override
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    /**
     * 注册拦截器
    */
    @Override
    protected void registerInterceptors() {
        registerAll(GRAPHQL, EXECUTE_METHODS);
    }

    /**
     * 构建Target
    */
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
    }}