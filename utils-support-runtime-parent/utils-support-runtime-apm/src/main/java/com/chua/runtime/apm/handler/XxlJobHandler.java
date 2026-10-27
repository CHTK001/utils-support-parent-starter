package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * XXL-作业 处理器 — intercepts XXL-作业 任务 执行.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class XxlJobHandler extends AbstractAppHandler {

    /**
     * 作业 处理器
     */
    private static final String JOB_HANDLER = "com/xxl/job/core/handler/IJobHandler";
    /**
     * 执行器
     */
    private static final String EXECUTOR = "com/xxl/job/core/executor/XxlJobExecutor";
    /**
     * 执行 方法
     */
    private static final String[] EXECUTE_METHODS = {"execute"};
    /**
     * 启动 方法
     */
    private static final String[] START_METHODS = {"start", "stop"};

    /**
     * 名称
    */
    @Override
    public String name() {
        return "xxl-job-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "xxl-job.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.XXL_JOB;
    }

    /**
     * 协议
    */
    @Override
    protected Protocol protocol() {
        return Protocol.INTERNAL;
    }

    /**
     * 注册拦截器
    */
    @Override
    protected void registerInterceptors() {
        registerAll(JOB_HANDLER, EXECUTE_METHODS);
        registerAll(EXECUTOR, START_METHODS);
    }

    /**
     * 构建Target
    */
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