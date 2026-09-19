package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * SQL 服务端 应用层 处理器 — 拦截 SQL 服务端 JDBC 驱动关键调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code com.microsoft.sqlserver.jdbc.SQLServerConnection} — prepareStatement / prepareCall / createStatement</li>
 *   <li>{@code com.microsoft.sqlserver.jdbc.SQLServerStatement} — execute / executeQuery / executeUpdate</li>
 *   <li>{@code com.microsoft.sqlserver.jdbc.SQLServerPreparedStatement} — execute / executeQuery / executeUpdate</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：SQL Server 驱动不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SqlServerHandler extends AbstractAppHandler {

    /**
     * sql服务端connection 类内部名
     */
    private static final String SQLSERVER_CONNECTION_CLASS = "com/microsoft/sqlserver/jdbc/SQLServerConnection";

    /**
     * sql服务端对账单 类内部名
     */
    private static final String SQLSERVER_STATEMENT_CLASS = "com/microsoft/sqlserver/jdbc/SQLServerStatement";

    /**
     * sql服务端prepared对账单 类内部名
     */
    private static final String SQLSERVER_PREPARED_STATEMENT_CLASS = "com/microsoft/sqlserver/jdbc/SQLServerPreparedStatement";

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
        return "sqlserver-handler";
    }

    @Override
    /**
     * 已启用键
    */
    protected String enabledKey() {
        return "sqlserver.enabled";
    }

    @Override
    /**
     * Software
    */
    protected Software software() {
        return Software.SQLSERVER_DRIVER;
    }

    @Override
    /**
     * 协议
    */
    protected Protocol protocol() {
        return Protocol.SQLSERVER;
    }

    @Override
    /**
     * 注册拦截器
    */
    protected void registerInterceptors() {
        registerAllEntryExit(SQLSERVER_STATEMENT_CLASS, SQL_METHODS);
        registerAllEntryExit(SQLSERVER_PREPARED_STATEMENT_CLASS, SQL_METHODS);
        registerAllEntryExit(SQLSERVER_CONNECTION_CLASS, CONNECTION_METHODS);
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
                .protocol(Protocol.SQLSERVER)
                .software(Software.SQLSERVER_DRIVER)
                .host(parseUrlHost(url) != null ? parseUrlHost(url) : "sqlserver")
                .port(parseUrlPort(url, Protocol.SQLSERVER.defaultPort()))
                .path(url != null ? parseUrlDb(url) : "/")
                .build();
    }
}