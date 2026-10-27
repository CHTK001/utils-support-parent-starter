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

    /**
     * 名称
    */
    @Override
    public String name() {
        return "jdk-http-server-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "jdk-http-server.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.JDK_HTTP_SERVER;
    }

    /**
     * 协议
    */
    @Override
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    /**
     * 注册拦截器
    */
    @Override
    protected void registerInterceptors() {
        registerAll(HTTP_HANDLER, HANDLE_METHODS);
    }

    /**
     * 构建Target
    */
    @Override
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