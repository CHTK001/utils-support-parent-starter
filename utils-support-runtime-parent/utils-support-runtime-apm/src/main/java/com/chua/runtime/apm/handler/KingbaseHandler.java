package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * kingbasees（金仓）应用层 处理器 — 拦截金仓 JDBC 驱动关键调用并生成应用语义传输记录。
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
     * kbconnection 类内部名
     */
    private static final String KINGBASE_CONNECTION_CLASS = "com/kingbase8/KbConnection";

    /**
     * kb对账单 类内部名
     */
    private static final String KINGBASE_STATEMENT_CLASS = "com/kingbase8/KbStatement";

    /**
     * kbprepared对账单 类内部名
     */
    private static final String KINGBASE_PREPARED_STATEMENT_CLASS = "com/kingbase8/KbPreparedStatement";

    /**
     * SQL 执行方法集合（对账单 / prepared对账单 共有）
     */
    private static final String[] SQL_METHODS = {"execute", "executeQuery", "executeUpdate"};

    /**
     * 连接预编译方法集合
     */
    private static final String[] CONNECTION_METHODS = {"prepareStatement", "prepareCall", "createStatement"};

    @Override
    /**
     * 名称
    */
    public String name() {
        return "kingbase-handler";
    }

    @Override
    /**
     * 已启用键
    */
    protected String enabledKey() {
        return "kingbase.enabled";
    }

    @Override
    /**
     * Software
    */
    protected Software software() {
        return Software.KINGBASE_DRIVER;
    }

    @Override
    /**
     * 协议
    */
    protected Protocol protocol() {
        return Protocol.KINGBASE;
    }

    @Override
    /**
     * 注册拦截器
    */
    protected void registerInterceptors() {
        registerAllEntryExit(KINGBASE_STATEMENT_CLASS, SQL_METHODS);
        registerAllEntryExit(KINGBASE_PREPARED_STATEMENT_CLASS, SQL_METHODS);
        registerAllEntryExit(KINGBASE_CONNECTION_CLASS, CONNECTION_METHODS);
    }

    @Override
    /**
     * 构建Target
    */
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