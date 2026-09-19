package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Vert.x 处理器 — intercepts Vert.x HTTP 服务端/客户端 operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class VertxHandler extends AbstractAppHandler {

    /**
     * HTTP 服务器 请求
     */
    private static final String HTTP_SERVER_REQUEST = "io/vertx/core/http/HttpServerRequest";
    /**
     * HTTP 客户端 请求
     */
    private static final String HTTP_CLIENT_REQUEST = "io/vertx/core/http/HttpClientRequest";
    /**
     * 服务器 方法
     */
    private static final String[] SERVER_METHODS = {"handler", "body", "params", "headers"};
    /**
     * 客户端 方法
     */
    private static final String[] CLIENT_METHODS = {"send", "end", "putHeader"};

    @Override
    /**
     * 名称
    */
    public String name() {
        return "vertx-handler";
    }

    @Override
    /**
     * 已启用键
    */
    protected String enabledKey() {
        return "vertx.enabled";
    }

    @Override
    /**
     * Software
    */
    protected Software software() {
        return Software.VERTX;
    }

    @Override
    /**
     * 协议
    */
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    /**
     * 注册拦截器
    */
    protected void registerInterceptors() {
        registerAll(HTTP_SERVER_REQUEST, SERVER_METHODS);
        registerAll(HTTP_CLIENT_REQUEST, CLIENT_METHODS);
    }

    @Override
    /**
     * 构建Target
    */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.VERTX)
                .host("vertx")
                .port(8080)
                .path("/")
                .build();
    }
}