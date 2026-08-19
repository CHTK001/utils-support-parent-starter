package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Neo4j 应用层 Handler — 拦截 Neo4j Java Driver 核心调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code org.neo4j.driver.Session} — run</li>
 *   <li>{@code org.neo4j.driver.Transaction} — run</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：Neo4j Driver 不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class Neo4jHandler extends AbstractAppHandler {

    /**
     * 会话 class
     */
    private static final String SESSION_CLASS = "org/neo4j/driver/Session";
    /**
     * transaction class
     */
    private static final String TRANSACTION_CLASS = "org/neo4j/driver/Transaction";
    /**
     * run methods
     */
    private static final String[] RUN_METHODS = {"run"};

    @Override
    /** Name */
    public String name() {
        return "neo4j-handler";
    }

    @Override
    /** EnabledKey */
    protected String enabledKey() {
        return "neo4j.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.NEO4J_DRIVER;
    }

    @Override
    /** Protocol */
    protected Protocol protocol() {
        return Protocol.NEO4J;
    }

    @Override
    /** 注册Interceptors */
    protected void registerInterceptors() {
        registerAll(SESSION_CLASS, RUN_METHODS);
        registerAll(TRANSACTION_CLASS, RUN_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.NEO4J)
                .software(Software.NEO4J_DRIVER)
                .host("neo4j")
                .port(Protocol.NEO4J.defaultPort())
                .path("/")
                .build();
    }
}