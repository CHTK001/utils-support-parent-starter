package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * JDK http服务端 处理器 — intercepts 收入 HTTP Requests 处理 by com.sun.net.httpserver.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class JdkHttpServerHandler extends AbstractAppHandler {

    /**
     * HTTP 处理器
     */
    private static final String HTTP_HANDLER = "com/sun/net/httpserver/HttpHandler";
    /**
     * 处理 方法
     */
    private static final String[] HANDLE_METHODS = {"handle"};

    @Override
    /** 名称 */
    public String name() {
        return "jdk-http-server-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "jdk-http-server.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.JDK_HTTP_SERVER;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(HTTP_HANDLER, HANDLE_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.JDK_HTTP_SERVER)
                .host("http")
                .port(80)
                .path("/")
                .build();
    }
}