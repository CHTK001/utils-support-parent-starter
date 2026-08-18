package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Tomcat Handler — intercepts request processing in Apache Tomcat.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TomcatHandler extends AbstractAppHandler {

    /**
     * standard wrapper valve
     */
    private static final String STANDARD_WRAPPER_VALVE = "org/apache/catalina/core/StandardWrapperValve";
    /**
     * invoke methods
     */
    private static final String[] INVOKE_METHODS = {"invoke"};

    @Override
    public String name() {
        return "tomcat-handler";
    }

    @Override
    protected String enabledKey() {
        return "tomcat.enabled";
    }

    @Override
    protected Software software() {
        return Software.TOMCAT;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(STANDARD_WRAPPER_VALVE, INVOKE_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.TOMCAT)
                .host("tomcat")
                .port(8080)
                .path("/")
                .build();
    }
}