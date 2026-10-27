package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Flink 处理器 — intercepts Flink 数据流 operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FlinkHandler extends AbstractAppHandler {

    /**
     * 数据 流
     */
    private static final String DATA_STREAM = "org/apache/flink/streaming/api/datastream/DataStream";
    /**
     * 流 方法
     */
    private static final String[] STREAM_METHODS = {"execute", "print", "collect", "count", "map", "filter", "flatMap"};

    /**
     * 名称
    */
    @Override
    public String name() {
        return "flink-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "flink.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.FLINK;
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
        registerAll(DATA_STREAM, STREAM_METHODS);
    }

    /**
     * 构建Target
    */
    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.INTERNAL)
                .software(Software.FLINK)
                .host("flink")
                .port(0)
                .path("/")
                .build();
    }
}