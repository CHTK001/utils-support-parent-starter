package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
   * 异步http客户端 处理器 — intercepts 异步 HTTP 客户端 Requests.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class AsyncHttpClientHandler extends AbstractAppHandler {

    /**
      * 异步 HTTP 客户端
     */
    private static final String ASYNC_HTTP_CLIENT = "org/asynchttpclient/AsyncHttpClient";
    /**
      * 执行 方法
     */
    private static final String[] EXECUTE_METHODS = {"executeRequest", "execute"};

    @Override
    /** 名称 */
    public String name() {
        return "async-http-client-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "async-http-client.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.ASYNC_HTTP_CLIENT;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(ASYNC_HTTP_CLIENT, EXECUTE_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.ASYNC_HTTP_CLIENT)
                .host("http")
                .port(80)
                .path("/")
                .build();
    }
}