package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Curator 处理器 — intercepts ZooKeeper operations via Apache Curator.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CuratorHandler extends AbstractAppHandler {

    /**
     * curator 框架
     */
    private static final String CURATOR_FRAMEWORK = "org/apache/curator/framework/CuratorFramework";
    /**
     * OPERATIONS
     */
    private static final String[] OPERATIONS = {"create", "delete", "getData", "setData", "getChildren", "checkExists"};

    /**
     * 名称
    */
    @Override
    public String name() {
        return "curator-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "curator.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.CURATOR;
    }

    /**
     * 协议
    */
    @Override
    protected Protocol protocol() {
        return Protocol.ZOOKEEPER;
    }

    /**
     * 注册拦截器
    */
    @Override
    protected void registerInterceptors() {
        registerAll(CURATOR_FRAMEWORK, OPERATIONS);
    }

    /**
     * 构建Target
    */
    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.ZOOKEEPER)
                .software(Software.CURATOR)
                .host("zookeeper")
                .port(Protocol.ZOOKEEPER.defaultPort())
                .path("/")
                .build();
    }
}