package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * AWS SDK 处理器 — intercepts AWS 客户端 operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class AwsSdkHandler extends AbstractAppHandler {

    /**
     * 客户端
     */
    private static final String CLIENT = "software/amazon/awssdk/core/client/ClientExecution";
    /**
     * 执行 方法
     */
    private static final String[] EXEC_METHODS = {"execute"};

    @Override
    /**
     * 名称
    */
    public String name() {
        return "aws-sdk-handler";
    }

    @Override
    /**
     * 已启用键
    */
    protected String enabledKey() {
        return "aws-sdk.enabled";
    }

    @Override
    /**
     * Software
    */
    protected Software software() {
        return Software.AWS_SDK;
    }

    @Override
    /**
     * 协议
    */
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    /**
     * 注册拦截器
    */
    protected void registerInterceptors() {
        registerAll(CLIENT, EXEC_METHODS);
    }

    @Override
    /**
     * 构建Target
    */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.AWS_SDK)
                .host("aws")
                .port(443)
                .path("/")
                .build();
    }
}