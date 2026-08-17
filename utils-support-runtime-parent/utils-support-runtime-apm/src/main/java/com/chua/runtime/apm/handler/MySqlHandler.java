package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * MySQL 应用层 Handler — 拦截 MySQL JDBC 驱动关键调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code com.mysql.cj.jdbc.ConnectionImpl} — prepareStatement / prepareCall / createStatement</li>
 *   <li>{@code com.mysql.cj.jdbc.StatementImpl} — execute / executeQuery / executeUpdate</li>
 *   <li>{@code com.mysql.cj.jdbc.ClientPreparedStatement} — execute / executeQuery / executeUpdate（客户端预编译）</li>
 *   <li>{@code com.mysql.cj.jdbc.ServerPreparedStatement} — execute / executeQuery / executeUpdate（服务端预编译）</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：MySQL 驱动不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MySqlHandler extends AbstractAppHandler {

    /**
     * ConnectionImpl 类内部名
     */
    private static final String MYSQL_CONNECTION_CLASS = "com/mysql/cj/jdbc/ConnectionImpl";

    /**
     * StatementImpl 类内部名
     */
    private static final String MYSQL_STATEMENT_CLASS = "com/mysql/cj/jdbc/StatementImpl";

    /**
     * ClientPreparedStatement 类内部名
     */
    private static final String MYSQL_CLIENT_PREPARED_STATEMENT_CLASS = "com/mysql/cj/jdbc/ClientPreparedStatement";

    /**
     * ServerPreparedStatement 类内部名
     */
    private static final String MYSQL_SERVER_PREPARED_STATEMENT_CLASS = "com/mysql/cj/jdbc/ServerPreparedStatement";

    /**
     * SQL 执行方法集合（Statement / PreparedStatement 共有）
     */
    private static final String[] SQL_METHODS = {"execute", "executeQuery", "executeUpdate"};

    /**
     * 连接预编译方法集合
     */
    private static final String[] CONNECTION_METHODS = {"prepareStatement", "prepareCall", "createStatement"};

    @Override
    public String name() {
        return "mysql-handler";
    }

    @Override
    protected String enabledKey() {
        return "mysql.enabled";
    }

    @Override
    protected Software software() {
        return Software.MYSQL_DRIVER;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.MYSQL;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(MYSQL_STATEMENT_CLASS, SQL_METHODS);
        registerAll(MYSQL_CLIENT_PREPARED_STATEMENT_CLASS, SQL_METHODS);
        registerAll(MYSQL_SERVER_PREPARED_STATEMENT_CLASS, SQL_METHODS);
        registerAll(MYSQL_CONNECTION_CLASS, CONNECTION_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        Object conn = resolveConnection(instance);
        String url = conn != null ? String.valueOf(findField(conn, "url")) : null;
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.MYSQL)
                .software(Software.MYSQL_DRIVER)
                .host(parseUrlHost(url) != null ? parseUrlHost(url) : "mysql")
                .port(parseUrlPort(url, Protocol.MYSQL.defaultPort()))
                .path(url != null ? parseUrlDb(url) : "/")
                .build();
    }
}