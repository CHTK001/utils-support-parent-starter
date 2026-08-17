package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Quartz Handler — intercepts Quartz job execution.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class QuartzHandler extends AbstractAppHandler {

    private static final String JOB = "org/quartz/Job";
    private static final String[] EXECUTE_METHODS = {"execute"};

    @Override
    public String name() {
        return "quartz-handler";
    }

    @Override
    protected String enabledKey() {
        return "quartz.enabled";
    }

    @Override
    protected Software software() {
        return Software.QUARTZ;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.INTERNAL;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(JOB, EXECUTE_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.INTERNAL)
                .software(Software.QUARTZ)
                .host("quartz")
                .port(0)
                .path("/")
                .build();
    }
}