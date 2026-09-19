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

    @Override
    /** 名称 */
    public String name() {
        return "xxl-job-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "xxl-job.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.XXL_JOB;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.INTERNAL;
    }

    @Override
    /** 注册拦截器 */
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