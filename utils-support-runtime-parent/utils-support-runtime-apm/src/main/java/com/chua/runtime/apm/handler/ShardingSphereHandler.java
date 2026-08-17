package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * ShardingSphere 应用层 Handler — 拦截 ShardingSphere JDBC 关键调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code org.apache.shardingsphere.driver.jdbc.core.connection.ShardingSphereConnection} — prepareStatement / createStatement</li>
 *   <li>{@code org.apache.shardingsphere.driver.jdbc.core.statement.ShardingSphereStatement} — execute / executeQuery / executeUpdate</li>
 *   <li>{@code org.apache.shardingsphere.driver.jdbc.core.statement.ShardingSpherePreparedStatement} — execute / executeQuery / executeUpdate</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：ShardingSphere 不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ShardingSphereHandler extends AbstractAppHandler {

    /**
     * ShardingSphereConnection 类内部名
     */
    private static final String SS_CONNECTION = "org/apache/shardingsphere/driver/jdbc/core/connection/ShardingSphereConnection";

    /**
     * ShardingSphereStatement 类内部名
     */
    private static final String SS_STATEMENT = "org/apache/shardingsphere/driver/jdbc/core/statement/ShardingSphereStatement";

    /**
     * ShardingSpherePreparedStatement 类内部名
     */
    private static final String SS_PREPARED_STATEMENT = "org/apache/shardingsphere/driver/jdbc/core/statement/ShardingSpherePreparedStatement";

    /**
     * SQL 执行方法集合（Statement / PreparedStatement 共有）
     */
    private static final String[] SQL_METHODS = {"execute", "executeQuery", "executeUpdate"};

    /**
     * 连接预编译方法集合
     */
    private static final String[] CONNECTION_METHODS = {"prepareStatement", "createStatement"};

    @Override
    public String name() {
        return "shardingsphere-handler";
    }

    @Override
    protected String enabledKey() {
        return "shardingsphere.enabled";
    }

    @Override
    protected Software software() {
        return Software.SHARDING_SPHERE;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.SHARDING_SPHERE;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(SS_STATEMENT, SQL_METHODS);
        registerAll(SS_PREPARED_STATEMENT, SQL_METHODS);
        registerAll(SS_CONNECTION, CONNECTION_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.SHARDING_SPHERE)
                .software(Software.SHARDING_SPHERE)
                .host("shardingsphere")
                .port(0)
                .path("/")
                .build();
    }
}