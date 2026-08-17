package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Cassandra 应用层 Handler — 拦截 Cassandra Java Driver 关键调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code com.datastax.oss.driver.api.core.CqlSession} — execute / executeAsync（同步/异步执行入口）</li>
 *   <li>{@code com.datastax.oss.driver.internal.core.session.DefaultSession} — 同步执行实现类</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：Cassandra 驱动不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CassandraHandler extends AbstractAppHandler {

    /**
     * CqlSession 接口类内部名
     */
    private static final String CQL_SESSION = "com/datastax/oss/driver/api/core/CqlSession";

    /**
     * DefaultSession 实现类内部名
     */
    private static final String DEFAULT_SESSION = "com/datastax/oss/driver/internal/core/session/DefaultSession";

    /**
     * Session 方法集合（同步 + 异步执行）
     */
    private static final String[] SESSION_METHODS = {"execute", "executeAsync", "prepare", "prepareAsync"};

    @Override
    public String name() {
        return "cassandra-handler";
    }

    @Override
    protected String enabledKey() {
        return "cassandra.enabled";
    }

    @Override
    protected Software software() {
        return Software.CASSANDRA_DRIVER;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.CASSANDRA;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(CQL_SESSION, SESSION_METHODS);
        registerAll(DEFAULT_SESSION, SESSION_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        Object contexts = findField(instance, "contexts");
        String url = contexts != null ? String.valueOf(findField(contexts, "node")) : null;
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.CASSANDRA)
                .software(Software.CASSANDRA_DRIVER)
                .host(parseUrlHost(url) != null ? parseUrlHost(url) : "cassandra")
                .port(parseUrlPort(url, Protocol.CASSANDRA.defaultPort()))
                .path("/")
                .build();
    }
}