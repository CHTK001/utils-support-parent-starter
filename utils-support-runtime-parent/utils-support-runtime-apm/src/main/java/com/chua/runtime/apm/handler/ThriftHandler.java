package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Thrift 应用层 处理器 — 拦截 Apache Thrift RPC 进出站调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code org.apache.thrift.TServiceClient} — sendBase / recvBase（客户端进出站核心）</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：Thrift 不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ThriftHandler extends AbstractAppHandler {

    /**
     * t服务客户端 类内部名
     */
    private static final String T_SERVICE_CLIENT = "org/apache/thrift/TServiceClient";

    /**
     * t服务客户端 方法集合（进出站核心）
     */
    private static final String[] CLIENT_METHODS = {"sendBase", "recvBase"};

    @Override
    /**
     * 名称
    */
    public String name() {
        return "thrift-handler";
    }

    @Override
    /**
     * 已启用键
    */
    protected String enabledKey() {
        return "thrift.enabled";
    }

    @Override
    /**
     * Software
    */
    protected Software software() {
        return Software.THRIFT;
    }

    @Override
    /**
     * 协议
    */
    protected Protocol protocol() {
        return Protocol.THRIFT;
    }

    @Override
    /**
     * 注册拦截器
    */
    protected void registerInterceptors() {
        registerAll(T_SERVICE_CLIENT, CLIENT_METHODS);
    }

    @Override
    /**
     * 构建Target
    */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        Object transport = instance != null ? findField(instance, "iprot_") : null;
        String url = transport != null ? String.valueOf(findField(transport, "trans_")) : null;
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.THRIFT)
                .software(Software.THRIFT)
                .host(parseUrlHost(url) != null ? parseUrlHost(url) : "thrift")
                .port(parseUrlPort(url, Protocol.THRIFT.defaultPort()))
                .path("/")
                .build();
    }
}