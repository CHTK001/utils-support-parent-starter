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

    @Override
    /** 名称 */
    public String name() {
        return "curator-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "curator.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.CURATOR;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.ZOOKEEPER;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(CURATOR_FRAMEWORK, OPERATIONS);
    }

    @Override
    /** 构建Target */
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