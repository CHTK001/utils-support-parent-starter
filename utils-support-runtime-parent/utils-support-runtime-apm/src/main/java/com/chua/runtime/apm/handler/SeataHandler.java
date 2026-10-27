package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Seata 处理器 — intercepts Seata 全局 transaction 开始/commit/rollback.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SeataHandler extends AbstractAppHandler {

    /**
     * 全局 transaction
     */
    private static final String GLOBAL_TRANSACTION = "io/seata/tm/api/GlobalTransaction";
    /**
     * tx 方法
     */
    private static final String[] TX_METHODS = {"begin", "commit", "rollback", "getStatus"};

    /**
     * 名称
    */
    @Override
    public String name() {
        return "seata-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "seata.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.SEATA;
    }

    /**
     * 协议
    */
    @Override
    protected Protocol protocol() {
        return Protocol.INTERNAL;
    }

    /**
     * 注册拦截器
    */
    @Override
    protected void registerInterceptors() {
        registerAll(GLOBAL_TRANSACTION, TX_METHODS);
    }

    /**
     * 构建Target
    */
    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.INTERNAL)
                .software(Software.SEATA)
                .host("seata")
                .port(0)
                .path("/")
                .build();
    }
}