package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * JDK httpurlconnection 处理器 — intercepts HTTP Requests via Java.net.httpurlconnection.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class JdkHttpClientHandler extends AbstractAppHandler {

    /**
     * HTTP URL 连接
     */
    private static final String HTTP_URL_CONNECTION = "java/net/HttpURLConnection";
    /**
     * 连接 方法
     */
    private static final String[] CONNECT_METHODS = {"connect", "getInputStream", "getOutputStream", "getResponseCode"};

    /**
     * 名称
    */
    @Override
    public String name() {
        return "jdk-http-client-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "jdk-http-client.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.JDK_HTTP_CLIENT;
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
        registerAll(HTTP_URL_CONNECTION, CONNECT_METHODS);
    }

    /**
     * 构建Target
    */
    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.JDK_HTTP_CLIENT)
                .host("http")
                .port(80)
                .path("/")
                .build();
    }
}