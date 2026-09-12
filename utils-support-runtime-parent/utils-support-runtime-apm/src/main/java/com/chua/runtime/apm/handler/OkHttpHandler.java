package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
* OkHttp 处理器 — intercepts OkHttp call 执行.
*
* @author CH
* @since 4.0.0.42
 */
public class OkHttpHandler extends AbstractAppHandler {

    /**
    * CALL
     */
    private static final String CALL = "okhttp3/Call";
    /**
    * 执行 方法
     */
    private static final String[] EXECUTE_METHODS = {"execute", "enqueue"};

    @Override
    /** 名称 */
    public String name() {
        return "okhttp-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "okhttp.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.OKHTTP;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(CALL, EXECUTE_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.OKHTTP)
                .host("http")
                .port(80)
                .path("/")
                .build();
    }
}