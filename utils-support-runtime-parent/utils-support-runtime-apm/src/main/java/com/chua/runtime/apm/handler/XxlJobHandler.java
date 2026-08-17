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

    private static final String JOB_HANDLER = "com/xxl/job/core/handler/IJobHandler";
    private static final String EXECUTOR = "com/xxl/job/core/executor/XxlJobExecutor";
    private static final String[] EXECUTE_METHODS = {"execute"};
    private static final String[] START_METHODS = {"start", "stop"};

    @Override
    public String name() {
        return "xxl-job-handler";
    }

    @Override
    protected String enabledKey() {
        return "xxl-job.enabled";
    }

    @Override
    protected Software software() {
        return Software.XXL_JOB;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.INTERNAL;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(JOB_HANDLER, EXECUTE_METHODS);
        registerAll(EXECUTOR, START_METHODS);
    }

    @Override
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