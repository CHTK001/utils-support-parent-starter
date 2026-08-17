package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * 达梦（DM）应用层 Handler — 拦截达梦 JDBC 驱动关键调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code dm.jdbc.driver.DmConnection} — prepareStatement / prepareCall / createStatement</li>
 *   <li>{@code dm.jdbc.driver.DmStatement} — execute / executeQuery / executeUpdate</li>
 *   <li>{@code dm.jdbc.driver.DmPreparedStatement} — execute / executeQuery / executeUpdate</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：达梦驱动不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DamengHandler extends AbstractAppHandler {

    /**
     * DmConnection 类内部名
     */
    private static final String DAMENG_CONNECTION_CLASS = "dm/jdbc/driver/DmConnection";

    /**
     * DmStatement 类内部名
     */
    private static final String DAMENG_STATEMENT_CLASS = "dm/jdbc/driver/DmStatement";

    /**
     * DmPreparedStatement 类内部名
     */
    private static final String DAMENG_PREPARED_STATEMENT_CLASS = "dm/jdbc/driver/DmPreparedStatement";

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
        return "dameng-handler";
    }

    @Override
    protected String enabledKey() {
        return "dameng.enabled";
    }

    @Override
    protected Software software() {
        return Software.DAMENG_DRIVER;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.DAMENG;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(DAMENG_STATEMENT_CLASS, SQL_METHODS);
        registerAll(DAMENG_PREPARED_STATEMENT_CLASS, SQL_METHODS);
        registerAll(DAMENG_CONNECTION_CLASS, CONNECTION_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        Object conn = resolveConnection(instance);
        String url = conn != null ? String.valueOf(findField(conn, "url")) : null;
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.DAMENG)
                .software(Software.DAMENG_DRIVER)
                .host(parseUrlHost(url) != null ? parseUrlHost(url) : "dameng")
                .port(parseUrlPort(url, Protocol.DAMENG.defaultPort()))
                .path(url != null ? parseUrlDb(url) : "/")
                .build();
    }
}