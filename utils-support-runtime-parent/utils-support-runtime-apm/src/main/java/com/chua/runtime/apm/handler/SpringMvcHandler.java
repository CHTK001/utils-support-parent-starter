package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Spring MVC Handler — intercepts DispatcherServlet request handling.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SpringMvcHandler extends AbstractAppHandler {

    private static final String DISPATCHER_SERVLET = "org/springframework/web/servlet/DispatcherServlet";
    private static final String[] DO_DISPATCH = {"doDispatch"};

    @Override
    public String name() {
        return "spring-mvc-handler";
    }

    @Override
    protected String enabledKey() {
        return "spring-mvc.enabled";
    }

    @Override
    protected Software software() {
        return Software.WEB_MVC;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(DISPATCHER_SERVLET, DO_DISPATCH);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.WEB_MVC)
                .host("spring-mvc")
                .port(8080)
                .path("/")
                .build();
    }
}