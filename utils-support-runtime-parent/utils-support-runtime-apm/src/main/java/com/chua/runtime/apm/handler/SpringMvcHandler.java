package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Spring MVC 处理器 — intercepts dispatcherservlet 请求 处理.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SpringMvcHandler extends AbstractAppHandler {

    /**
     * dispatcher servlet
     */
    private static final String DISPATCHER_SERVLET = "org/springframework/web/servlet/DispatcherServlet";
    /**
     * 执行 dispatch
     */
    private static final String[] DO_DISPATCH = {"doDispatch"};

    /**
     * 名称
    */
    @Override
    public String name() {
        return "spring-mvc-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "spring-mvc.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.SPRING_MVC;
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
        registerAll(DISPATCHER_SERVLET, DO_DISPATCH);
    }

    /**
     * 构建Target
    */
    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.SPRING_MVC)
                .host("spring-mvc")
                .port(8080)
                .path("/")
                .build();
    }
}