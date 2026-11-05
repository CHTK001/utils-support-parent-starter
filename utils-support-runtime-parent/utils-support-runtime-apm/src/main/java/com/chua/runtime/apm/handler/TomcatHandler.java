// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
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

    /**
     * 名称
    */
    @Override
    public String name() {
        return "tomcat-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "tomcat.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.TOMCAT;
    }

    /**
     * 协议
    */
    @Override
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    /**
     * 注册拦截器
    */
    @Override
    protected void registerInterceptors() {
        registerAll(STANDARD_WRAPPER_VALVE, INVOKE_METHODS);
    }

    /**
     * 构建Target
    */
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
    }}