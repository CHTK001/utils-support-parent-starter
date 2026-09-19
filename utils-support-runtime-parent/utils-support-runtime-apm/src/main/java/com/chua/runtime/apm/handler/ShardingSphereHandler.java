package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * 分库分表sphere 处理器 — intercepts SQL 执行 via 分库分表sphere.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ShardingSphereHandler extends AbstractAppHandler {

    /**
     * 分库分表 sphere
     */
    private static final String SHARDING_SPHERE = "org/apache/shardingsphere";
    /**
     * 执行 方法
     */
    private static final String[] EXECUTE_METHODS = {"execute", "executeQuery", "executeUpdate"};

    @Override
    /** 名称 */
    public String name() {
        return "shardingsphere-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "shardingsphere.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.SHARDING_SPHERE;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.SQL;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(SHARDING_SPHERE, EXECUTE_METHODS);
    }

    @Override
    /** 构建Target */
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