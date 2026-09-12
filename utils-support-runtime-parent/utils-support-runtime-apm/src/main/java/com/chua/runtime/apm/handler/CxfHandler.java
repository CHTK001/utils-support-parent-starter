package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
   * Apache CXF 处理器 — intercepts CXF web服务 客户端 invocation.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CxfHandler extends AbstractAppHandler {

    /**
     * INVOKER
     */
    private static final String INVOKER = "org/apache/cxf/endpoint/ClientImpl";
    /**
      * 代理
     */
    private static final String PROXY = "org/apache/cxf/frontend/ClientProxy";
    /**
      * invoke 方法
     */
    private static final String[] INVOKE_METHODS = {"invoke"};
    /**
      * 代理 方法
     */
    private static final String[] PROXY_METHODS = {"invoke"};

    @Override
    /** 名称 */
    public String name() {
        return "cxf-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "cxf.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.CXF;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(INVOKER, INVOKE_METHODS);
        registerAll(PROXY, PROXY_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.CXF)
                .host("cxf")
                .port(80)
                .path("/")
                .build();
    }
}