package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Oracle 应用层 处理器 — 拦截 Oracle JDBC 驱动关键调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code oracle.jdbc.driver.OracleConnection} — prepareStatement / prepareCall / createStatement</li>
 *   <li>{@code oracle.jdbc.driver.OracleStatement} — execute / executeQuery / executeUpdate</li>
 *   <li>{@code oracle.jdbc.driver.OraclePreparedStatement} — execute / executeQuery / executeUpdate</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：Oracle 驱动不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OracleHandler extends AbstractAppHandler {

    /**
     * oracleconnection 类内部名
     */
    private static final String ORACLE_CONNECTION_CLASS = "oracle/jdbc/driver/OracleConnection";

    /**
     * oracle对账单 类内部名
     */
    private static final String ORACLE_STATEMENT_CLASS = "oracle/jdbc/driver/OracleStatement";

    /**
     * oracleprepared对账单 类内部名
     */
    private static final String ORACLE_PREPARED_STATEMENT_CLASS = "oracle/jdbc/driver/OraclePreparedStatement";

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
        return "oracle-handler";
    }

    @Override
    /**
     * 已启用键
    */
    protected String enabledKey() {
        return "oracle.enabled";
    }

    @Override
    /**
     * Software
    */
    protected Software software() {
        return Software.ORACLE_DRIVER;
    }

    @Override
    /**
     * 协议
    */
    protected Protocol protocol() {
        return Protocol.ORACLE;
    }

    @Override
    /**
     * 注册拦截器
    */
    protected void registerInterceptors() {
        registerAllEntryExit(ORACLE_STATEMENT_CLASS, SQL_METHODS);
        registerAllEntryExit(ORACLE_PREPARED_STATEMENT_CLASS, SQL_METHODS);
        registerAllEntryExit(ORACLE_CONNECTION_CLASS, CONNECTION_METHODS);
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
                .protocol(Protocol.ORACLE)
                .software(Software.ORACLE_DRIVER)
                .host(parseUrlHost(url) != null ? parseUrlHost(url) : "oracle")
                .port(parseUrlPort(url, Protocol.ORACLE.defaultPort()))
                .path(url != null ? parseUrlDb(url) : "/")
                .build();
    }
}