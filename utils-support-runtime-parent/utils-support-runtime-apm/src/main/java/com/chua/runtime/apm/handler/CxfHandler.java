// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
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

    /**
     * 名称
    */
    @Override
    public String name() {
        return "cxf-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "cxf.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.CXF;
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
        registerAll(INVOKER, INVOKE_METHODS);
        registerAll(PROXY, PROXY_METHODS);
    }

    /**
     * 构建Target
    */
    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.CXF)
                .host("cxf")
                .port(80)
                .path("/")
                .build();
    }}