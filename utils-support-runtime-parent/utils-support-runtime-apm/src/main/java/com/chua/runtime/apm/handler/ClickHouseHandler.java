package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * click房子 应用层 处理器 — 拦截 click房子 JDBC 驱动关键调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code com.clickhouse.jdbc.ClickHouseConnection} — prepareStatement / prepareCall / createStatement</li>
 *   <li>{@code com.clickhouse.jdbc.ClickHouseStatement} — execute / executeQuery / executeUpdate</li>
 *   <li>{@code com.clickhouse.jdbc.ClickHousePreparedStatement} — execute / executeQuery / executeUpdate</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：ClickHouse 驱动不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ClickHouseHandler extends AbstractAppHandler {

    /**
     * click房子connection 类内部名
     */
    private static final String CLICKHOUSE_CONNECTION_CLASS = "com/clickhouse/jdbc/ClickHouseConnection";

    /**
     * click房子对账单 类内部名
     */
    private static final String CLICKHOUSE_STATEMENT_CLASS = "com/clickhouse/jdbc/ClickHouseStatement";

    /**
     * click房子prepared对账单 类内部名
     */
    private static final String CLICKHOUSE_PREPARED_STATEMENT_CLASS = "com/clickhouse/jdbc/ClickHousePreparedStatement";

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
        return "clickhouse-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "clickhouse.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.CLICKHOUSE_DRIVER;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.CLICKHOUSE;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAllEntryExit(CLICKHOUSE_STATEMENT_CLASS, SQL_METHODS);
        registerAllEntryExit(CLICKHOUSE_PREPARED_STATEMENT_CLASS, SQL_METHODS);
        registerAllEntryExit(CLICKHOUSE_CONNECTION_CLASS, CONNECTION_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        Object conn = resolveConnection(instance);
        String url = conn != null ? String.valueOf(findField(conn, "url")) : null;
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.CLICKHOUSE)
                .software(Software.CLICKHOUSE_DRIVER)
                .host(parseUrlHost(url) != null ? parseUrlHost(url) : "clickhouse")
                .port(parseUrlPort(url, Protocol.CLICKHOUSE.defaultPort()))
                .path(url != null ? parseUrlDb(url) : "/")
                .build();
    }
}