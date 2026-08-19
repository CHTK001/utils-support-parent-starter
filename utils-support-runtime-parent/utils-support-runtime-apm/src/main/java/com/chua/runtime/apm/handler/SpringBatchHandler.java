package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Spring Batch Handler — intercepts batch step execution.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SpringBatchHandler extends AbstractAppHandler {

    /**
     * STEP
     */
    private static final String STEP = "org/springframework/batch/core/step/Step";
    /**
     * execute methods
     */
    private static final String[] EXECUTE_METHODS = {"execute"};

    @Override
    /** Name */
    public String name() {
        return "spring-batch-handler";
    }

    @Override
    /** EnabledKey */
    protected String enabledKey() {
        return "spring-batch.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.SPRING_BATCH;
    }

    @Override
    /** Protocol */
    protected Protocol protocol() {
        return Protocol.INTERNAL;
    }

    @Override
    /** 注册Interceptors */
    protected void registerInterceptors() {
        registerAll(STEP, EXECUTE_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.INTERNAL)
                .software(Software.SPRING_BATCH)
                .host("spring-batch")
                .port(0)
                .path("/")
                .build();
    }
}