package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
* Spring Cloud Gateway 处理器 — intercepts gateway 过滤器 chain 执行.
*
* @author CH
* @since 4.0.0.42
 */
public class SpringCloudGatewayHandler extends AbstractAppHandler {

    /**
    * 过滤器 处理器
     */
    private static final String FILTERING_HANDLER = "org/springframework/cloud/gateway/handler/FilteringWebHandler";
    /**
    * 处理 方法
     */
    private static final String[] HANDLE_METHODS = {"handle"};

    @Override
    /** 名称 */
    public String name() {
        return "spring-cloud-gateway-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "spring-cloud-gateway.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.SPRING_CLOUD_GATEWAY;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(FILTERING_HANDLER, HANDLE_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.SPRING_CLOUD_GATEWAY)
                .host("gateway")
                .port(80)
                .path("/")
                .build();
    }
}