package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Feign 处理器 — intercepts Feign 客户端 Requests.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FeignHandler extends AbstractAppHandler {

    /**
     * Feign 客户端
     */
    private static final String FEIGN_CLIENT = "feign/Client";
    /**
     * 执行 方法
     */
    private static final String[] EXECUTE_METHODS = {"execute"};

    /**
     * 名称
    */
    @Override
    public String name() {
        return "feign-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "feign.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.FEIGN;
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
        registerAll(FEIGN_CLIENT, EXECUTE_METHODS);
    }

    /**
     * 构建Target
    */
    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.FEIGN)
                .host("http")
                .port(80)
                .path("/")
                .build();
    }
}