package com.chua.runtime.apm.handler;

import com.chua.runtime.plugin.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * PostgreSQL 应用层 Handler — 拦截 PG JDBC 驱动关键调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code org.postgresql.jdbc.PgConnection} — prepareStatement / prepareCall / createStatement</li>
 *   <li>{@code org.postgresql.jdbc.PgStatement} — execute / executeQuery / executeUpdate</li>
 *   <li>{@code org.postgresql.jdbc.PgPreparedStatement} — execute / executeQuery / executeUpdate</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：PostgreSQL 驱动不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PostgreSqlHandler extends AbstractAppHandler {

    /**
     * PgConnection 类内部名
     */
    private static final String PG_CONNECTION_CLASS = "org/postgresql/jdbc/PgConnection";

    /**
     * PgStatement 类内部名
     */
    private static final String PG_STATEMENT_CLASS = "org/postgresql/jdbc/PgStatement";

    /**
     * PgPreparedStatement 类内部名
     */
    private static final String PG_PREPARED_STATEMENT_CLASS = "org/postgresql/jdbc/PgPreparedStatement";

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
        return "postgresql-handler";
    }

    @Override
    protected String enabledKey() {
        return "postgresql.enabled";
    }

    @Override
    protected Software software() {
        return Software.POSTGRESQL_DRIVER;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.POSTGRESQL;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(PG_STATEMENT_CLASS, SQL_METHODS);
        registerAll(PG_PREPARED_STATEMENT_CLASS, SQL_METHODS);
        registerAll(PG_CONNECTION_CLASS, CONNECTION_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        Object conn = resolveConnection(instance);
        String url = conn != null ? String.valueOf(findField(conn, "url")) : null;
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.POSTGRESQL)
                .software(Software.POSTGRESQL_DRIVER)
                .host(parseUrlHost(url) != null ? parseUrlHost(url) : "postgres")
                .port(parseUrlPort(url, Protocol.POSTGRESQL.defaultPort()))
                .path(url != null ? parseUrlDb(url) : "/")
                .build();
    }
}