package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * XXL-Job Handler — intercepts XXL-Job task execution.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class XxlJobHandler extends AbstractAppHandler {

    /**
     * job handler
     */
    private static final String JOB_HANDLER = "com/xxl/job/core/handler/IJobHandler";
    /**
     * EXECUTOR
     */
    private static final String EXECUTOR = "com/xxl/job/core/executor/XxlJobExecutor";
    /**
     * execute methods
     */
    private static final String[] EXECUTE_METHODS = {"execute"};
    /**
     * start methods
     */
    private static final String[] START_METHODS = {"start", "stop"};

    @Override
    /** Name */
    public String name() {
        return "xxl-job-handler";
    }

    @Override
    /** EnabledKey */
    protected String enabledKey() {
        return "xxl-job.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.XXL_JOB;
    }

    @Override
    /** Protocol */
    protected Protocol protocol() {
        return Protocol.INTERNAL;
    }

    @Override
    /** 注册Interceptors */
    protected void registerInterceptors() {
        registerAll(JOB_HANDLER, EXECUTE_METHODS);
        registerAll(EXECUTOR, START_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.INTERNAL)
                .software(Software.XXL_JOB)
                .host("xxl-job")
                .port(0)
                .path("/")
                .build();
    }
}