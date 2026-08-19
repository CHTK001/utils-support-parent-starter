package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * KingbaseES（金仓）应用层 Handler — 拦截金仓 JDBC 驱动关键调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code com.kingbase8.KbConnection} — prepareStatement / prepareCall / createStatement</li>
 *   <li>{@code com.kingbase8.KbStatement} — execute / executeQuery / executeUpdate</li>
 *   <li>{@code com.kingbase8.KbPreparedStatement} — execute / executeQuery / executeUpdate</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：金仓驱动不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class KingbaseHandler extends AbstractAppHandler {

    /**
     * KbConnection 类内部名
     */
    private static final String KINGBASE_CONNECTION_CLASS = "com/kingbase8/KbConnection";

    /**
     * KbStatement 类内部名
     */
    private static final String KINGBASE_STATEMENT_CLASS = "com/kingbase8/KbStatement";

    /**
     * KbPreparedStatement 类内部名
     */
    private static final String KINGBASE_PREPARED_STATEMENT_CLASS = "com/kingbase8/KbPreparedStatement";

    /**
     * SQL 执行方法集合（Statement / PreparedStatement 共有）
     */
    private static final String[] SQL_METHODS = {"execute", "executeQuery", "executeUpdate"};

    /**
     * 连接预编译方法集合
     */
    private static final String[] CONNECTION_METHODS = {"prepareStatement", "prepareCall", "createStatement"};

    @Override
    /** Name */
    public String name() {
        return "kingbase-handler";
    }

    @Override
    /** EnabledKey */
    protected String enabledKey() {
        return "kingbase.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.KINGBASE_DRIVER;
    }

    @Override
    /** Protocol */
    protected Protocol protocol() {
        return Protocol.KINGBASE;
    }

    @Override
    /** 注册Interceptors */
    protected void registerInterceptors() {
        registerAllEntryExit(KINGBASE_STATEMENT_CLASS, SQL_METHODS);
        registerAllEntryExit(KINGBASE_PREPARED_STATEMENT_CLASS, SQL_METHODS);
        registerAllEntryExit(KINGBASE_CONNECTION_CLASS, CONNECTION_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        Object conn = resolveConnection(instance);
        String url = conn != null ? String.valueOf(findField(conn, "url")) : null;
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.KINGBASE)
                .software(Software.KINGBASE_DRIVER)
                .host(parseUrlHost(url) != null ? parseUrlHost(url) : "kingbase")
                .port(parseUrlPort(url, Protocol.KINGBASE.defaultPort()))
                .path(url != null ? parseUrlDb(url) : "/")
                .build();
    }
}