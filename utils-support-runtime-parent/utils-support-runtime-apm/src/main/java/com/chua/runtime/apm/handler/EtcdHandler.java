package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
   * Etcd 处理器 — intercepts Etcd KV operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class EtcdHandler extends AbstractAppHandler {

    /**
     * kv 客户端
     */
    private static final String KV_CLIENT = "io/etcd/jetcd/KV";
    /**
      * kv 方法
     */
    private static final String[] KV_METHODS = {"put", "get", "delete", "compact"};

    @Override
    /** 名称 */
    public String name() {
        return "etcd-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "etcd.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.ETCD;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.ETCD;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(KV_CLIENT, KV_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.ETCD)
                .software(Software.ETCD)
                .host("etcd")
                .port(Protocol.ETCD.defaultPort())
                .path("/")
                .build();
    }
}