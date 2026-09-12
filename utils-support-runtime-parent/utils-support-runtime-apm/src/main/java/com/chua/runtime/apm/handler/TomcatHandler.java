package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
   * Tomcat 处理器 — intercepts 请求 处理 入 Apache Tomcat.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TomcatHandler extends AbstractAppHandler {

    /**
      * 标准 包装器 valve
     */
    private static final String STANDARD_WRAPPER_VALVE = "org/apache/catalina/core/StandardWrapperValve";
    /**
      * invoke 方法
     */
    private static final String[] INVOKE_METHODS = {"invoke"};

    @Override
    /** 名称 */
    public String name() {
        return "tomcat-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "tomcat.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.TOMCAT;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(STANDARD_WRAPPER_VALVE, INVOKE_METHODS);
    }

    @Override
    /** 构建Target */
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