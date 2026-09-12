package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
   * H2 应用层 处理器 — 拦截 H2 JDBC 驱动关键调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code org.h2.jdbc.JdbcConnection} — prepareStatement / createStatement / prepareCall</li>
 *   <li>{@code org.h2.jdbc.JdbcStatement} — execute / executeQuery / executeUpdate</li>
 *   <li>{@code org.h2.jdbc.JdbcPreparedStatement} — execute / executeQuery / executeUpdate</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：H2 驱动不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class H2Handler extends AbstractAppHandler {

    /**
      * jdbcconnection 类内部名
     */
    private static final String H2_CONNECTION_CLASS = "org/h2/jdbc/JdbcConnection";

    /**
      * jdbc对账单 类内部名
     */
    private static final String H2_STATEMENT_CLASS = "org/h2/jdbc/JdbcStatement";

    /**
      * jdbcprepared对账单 类内部名
     */
    private static final String H2_PREPARED_STATEMENT_CLASS = "org/h2/jdbc/JdbcPreparedStatement";

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
        return "h2-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "h2.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.H2_DRIVER;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.H2;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAllEntryExit(H2_STATEMENT_CLASS, SQL_METHODS);
        registerAllEntryExit(H2_PREPARED_STATEMENT_CLASS, SQL_METHODS);
        registerAllEntryExit(H2_CONNECTION_CLASS, CONNECTION_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        Object conn = resolveConnection(instance);
        String url = conn != null ? String.valueOf(findField(conn, "url")) : null;
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.H2)
                .software(Software.H2_DRIVER)
                .host(parseUrlHost(url) != null ? parseUrlHost(url) : "h2")
                .port(parseUrlPort(url, Protocol.H2.defaultPort()))
                .path(url != null ? parseUrlDb(url) : "/")
                .build();
    }
}