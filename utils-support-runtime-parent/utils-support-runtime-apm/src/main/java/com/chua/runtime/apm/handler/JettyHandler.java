package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Jetty 处理器 — intercepts 请求 处理 入 Eclipse Jetty.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class JettyHandler extends AbstractAppHandler {

    /**
     * 服务端
     */
    private static final String SERVER = "org/eclipse/jetty/server/Server";
    /**
     * 处理 方法
     */
    private static final String[] HANDLE_METHODS = {"handle"};

    @Override
    /** 名称 */
    public String name() {
        return "jetty-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "jetty.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.JETTY;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(SERVER, HANDLE_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.JETTY)
                .host("jetty")
                .port(8080)
                .path("/")
                .build();
    }
}