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

    @Override
    /**
     * 名称
    */
    public String name() {
        return "spring-mvc-handler";
    }

    @Override
    /**
     * 已启用键
    */
    protected String enabledKey() {
        return "spring-mvc.enabled";
    }

    @Override
    /**
     * Software
    */
    protected Software software() {
        return Software.SPRING_MVC;
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
        registerAll(DISPATCHER_SERVLET, DO_DISPATCH);
    }

    @Override
    /**
     * 构建Target
    */
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