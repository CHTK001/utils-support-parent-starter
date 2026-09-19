package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Spring 批量 处理器 — intercepts 批量 step 执行.
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
     * 执行 方法
     */
    private static final String[] EXECUTE_METHODS = {"execute"};

    @Override
    /**
     * 名称
    */
    public String name() {
        return "spring-batch-handler";
    }

    @Override
    /**
     * 已启用键
    */
    protected String enabledKey() {
        return "spring-batch.enabled";
    }

    @Override
    /**
     * Software
    */
    protected Software software() {
        return Software.SPRING_BATCH;
    }

    @Override
    /**
     * 协议
    */
    protected Protocol protocol() {
        return Protocol.INTERNAL;
    }

    @Override
    /**
     * 注册拦截器
    */
    protected void registerInterceptors() {
        registerAll(STEP, EXECUTE_METHODS);
    }

    @Override
    /**
     * 构建Target
    */
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