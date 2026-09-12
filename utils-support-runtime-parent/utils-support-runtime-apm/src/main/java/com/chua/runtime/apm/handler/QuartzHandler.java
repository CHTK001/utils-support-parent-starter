package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
   * 石英石 处理器 — intercepts 石英石 作业 执行.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class QuartzHandler extends AbstractAppHandler {

    /**
      * 作业
     */
    private static final String JOB = "org/quartz/Job";
    /**
      * 执行 方法
     */
    private static final String[] EXECUTE_METHODS = {"execute"};

    @Override
    /** 名称 */
    public String name() {
        return "quartz-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "quartz.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.QUARTZ;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.INTERNAL;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(JOB, EXECUTE_METHODS);
    }

    @Override
    /** 构建Target */
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