// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
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

    /**
     * 名称
    */
    @Override
    public String name() {
        return "spring-cloud-gateway-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "spring-cloud-gateway.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.SPRING_CLOUD_GATEWAY;
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
        registerAll(FILTERING_HANDLER, HANDLE_METHODS);
    }

    /**
     * 构建Target
    */
    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.SPRING_CLOUD_GATEWAY)
                .host("gateway")
                .port(80)
                .path("/")
                .build();
    }}