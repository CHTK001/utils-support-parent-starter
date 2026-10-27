package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * 达梦（DM）应用层 处理器 — 拦截达梦 JDBC 驱动关键调用并生成应用语义传输记录。
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
     * dmconnection 类内部名
     */
    private static final String DAMENG_CONNECTION_CLASS = "dm/jdbc/driver/DmConnection";

    /**
     * dm对账单 类内部名
     */
    private static final String DAMENG_STATEMENT_CLASS = "dm/jdbc/driver/DmStatement";

    /**
     * dmprepared对账单 类内部名
     */
    private static final String DAMENG_PREPARED_STATEMENT_CLASS = "dm/jdbc/driver/DmPreparedStatement";

    /**
     * SQL 执行方法集合（对账单 / prepared对账单 共有）
     */
    private static final String[] SQL_METHODS = {"execute", "executeQuery", "executeUpdate"};

    /**
     * 连接预编译方法集合
     */
    private static final String[] CONNECTION_METHODS = {"prepareStatement", "prepareCall", "createStatement"};

    /**
     * 名称
    */
    @Override
    public String name() {
        return "dameng-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "dameng.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.DAMENG_DRIVER;
    }

    /**
     * 协议
    */
    @Override
    protected Protocol protocol() {
        return Protocol.DAMENG;
    }

    /**
     * 注册拦截器
    */
    @Override
    protected void registerInterceptors() {
        registerAllEntryExit(DAMENG_STATEMENT_CLASS, SQL_METHODS);
        registerAllEntryExit(DAMENG_PREPARED_STATEMENT_CLASS, SQL_METHODS);
        registerAllEntryExit(DAMENG_CONNECTION_CLASS, CONNECTION_METHODS);
    }

    /**
     * 构建Target
    */
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