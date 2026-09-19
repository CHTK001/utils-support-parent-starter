package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * PostgreSQL 应用层 处理器 — 拦截 PG JDBC 驱动关键调用并生成应用语义传输记录。
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
     * pgconnection 类内部名
     */
    private static final String PG_CONNECTION_CLASS = "org/postgresql/jdbc/PgConnection";

    /**
     * pg对账单 类内部名
     */
    private static final String PG_STATEMENT_CLASS = "org/postgresql/jdbc/PgStatement";

    /**
     * pgprepared对账单 类内部名
     */
    private static final String PG_PREPARED_STATEMENT_CLASS = "org/postgresql/jdbc/PgPreparedStatement";

    /**
     * SQL 执行方法集合（对账单 / prepared对账单 共有）
     */
    private static final String[] SQL_METHODS = {"execute", "executeQuery", "executeUpdate"};

    /**
     * 连接预编译方法集合
     */
    private static final String[] CONNECTION_METHODS = {"prepareStatement", "prepareCall", "createStatement"};

    @Override
    /** 名称 */
    public String name() {
        return "postgresql-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "postgresql.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.POSTGRESQL_DRIVER;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.POSTGRESQL;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAllEntryExit(PG_STATEMENT_CLASS, SQL_METHODS);
        registerAllEntryExit(PG_PREPARED_STATEMENT_CLASS, SQL_METHODS);
        registerAllEntryExit(PG_CONNECTION_CLASS, CONNECTION_METHODS);
    }

    @Override
    /** 构建Target */
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