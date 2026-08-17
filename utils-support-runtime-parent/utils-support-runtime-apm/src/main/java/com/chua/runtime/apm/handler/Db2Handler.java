package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * DB2 应用层 Handler — 拦截 IBM DB2 JDBC 驱动关键调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code com.ibm.db2.jcc.DB2Connection} — prepareStatement / prepareCall / createStatement</li>
 *   <li>{@code com.ibm.db2.jcc.DB2Statement} — execute / executeQuery / executeUpdate</li>
 *   <li>{@code com.ibm.db2.jcc.DB2PreparedStatement} — execute / executeQuery / executeUpdate</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：DB2 驱动不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class Db2Handler extends AbstractAppHandler {

    /**
     * DB2Connection 类内部名
     */
    private static final String DB2_CONNECTION_CLASS = "com/ibm/db2/jcc/DB2Connection";

    /**
     * DB2Statement 类内部名
     */
    private static final String DB2_STATEMENT_CLASS = "com/ibm/db2/jcc/DB2Statement";

    /**
     * DB2PreparedStatement 类内部名
     */
    private static final String DB2_PREPARED_STATEMENT_CLASS = "com/ibm/db2/jcc/DB2PreparedStatement";

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
        return "db2-handler";
    }

    @Override
    protected String enabledKey() {
        return "db2.enabled";
    }

    @Override
    protected Software software() {
        return Software.DB2_DRIVER;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.DB2;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(DB2_STATEMENT_CLASS, SQL_METHODS);
        registerAll(DB2_PREPARED_STATEMENT_CLASS, SQL_METHODS);
        registerAll(DB2_CONNECTION_CLASS, CONNECTION_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        Object conn = resolveConnection(instance);
        String url = conn != null ? String.valueOf(findField(conn, "url")) : null;
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.DB2)
                .software(Software.DB2_DRIVER)
                .host(parseUrlHost(url) != null ? parseUrlHost(url) : "db2")
                .port(parseUrlPort(url, Protocol.DB2.defaultPort()))
                .path(url != null ? parseUrlDb(url) : "/")
                .build();
    }
}