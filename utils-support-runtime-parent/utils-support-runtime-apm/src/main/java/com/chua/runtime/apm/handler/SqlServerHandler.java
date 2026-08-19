package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * SQL Server 应用层 Handler — 拦截 SQL Server JDBC 驱动关键调用并生成应用语义传输记录。
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
     * SQLServerConnection 类内部名
     */
    private static final String SQLSERVER_CONNECTION_CLASS = "com/microsoft/sqlserver/jdbc/SQLServerConnection";

    /**
     * SQLServerStatement 类内部名
     */
    private static final String SQLSERVER_STATEMENT_CLASS = "com/microsoft/sqlserver/jdbc/SQLServerStatement";

    /**
     * SQLServerPreparedStatement 类内部名
     */
    private static final String SQLSERVER_PREPARED_STATEMENT_CLASS = "com/microsoft/sqlserver/jdbc/SQLServerPreparedStatement";

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
        return "sqlserver-handler";
    }

    @Override
    /** EnabledKey */
    protected String enabledKey() {
        return "sqlserver.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.SQLSERVER_DRIVER;
    }

    @Override
    /** Protocol */
    protected Protocol protocol() {
        return Protocol.SQLSERVER;
    }

    @Override
    /** 注册Interceptors */
    protected void registerInterceptors() {
        registerAllEntryExit(SQLSERVER_STATEMENT_CLASS, SQL_METHODS);
        registerAllEntryExit(SQLSERVER_PREPARED_STATEMENT_CLASS, SQL_METHODS);
        registerAllEntryExit(SQLSERVER_CONNECTION_CLASS, CONNECTION_METHODS);
    }

    @Override
    /** 构建Target */
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