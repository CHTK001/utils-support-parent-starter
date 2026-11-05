// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Kubernetes 处理器 — intercepts Kubernetes API calls.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class KubernetesHandler extends AbstractAppHandler {

    /**
     * api 客户端
     */
    private static final String API_CLIENT = "io/kubernetes/client/openapi/ApiClient";
    /**
     * api 方法
     */
    private static final String[] API_METHODS = {"execute", "call"};

    /**
     * 名称
    */
    @Override
    public String name() {
        return "kubernetes-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "kubernetes.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.KUBERNETES;
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
        registerAll(API_CLIENT, API_METHODS);
    }

    /**
     * 构建Target
    */
    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.KUBERNETES)
                .host("kubernetes")
                .port(443)
                .path("/")
                .build();
    }}