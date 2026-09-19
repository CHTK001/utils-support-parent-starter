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

    @Override
    /**
     * 名称
    */
    public String name() {
        return "jdk-http-client-handler";
    }

    @Override
    /**
     * 已启用键
    */
    protected String enabledKey() {
        return "jdk-http-client.enabled";
    }

    @Override
    /**
     * Software
    */
    protected Software software() {
        return Software.JDK_HTTP_CLIENT;
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
        registerAll(HTTP_URL_CONNECTION, CONNECT_METHODS);
    }

    @Override
    /**
     * 构建Target
    */
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