package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * ShardingSphere Handler — intercepts SQL execution via ShardingSphere.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ShardingSphereHandler extends AbstractAppHandler {

    private static final String SHARDING_SPHERE = "org/apache/shardingsphere";
    private static final String[] EXECUTE_METHODS = {"execute", "executeQuery", "executeUpdate"};

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
        return Protocol.SQL;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(SHARDING_SPHERE, EXECUTE_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.SQL)
                .software(Software.SHARDING_SPHERE)
                .host("shardingsphere")
                .port(0)
                .path("/")
                .build();
    }
}