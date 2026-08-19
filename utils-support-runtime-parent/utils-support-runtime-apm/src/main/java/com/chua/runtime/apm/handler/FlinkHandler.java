package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Flink Handler — intercepts Flink DataStream operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FlinkHandler extends AbstractAppHandler {

    /**
     * data stream
     */
    private static final String DATA_STREAM = "org/apache/flink/streaming/api/datastream/DataStream";
    /**
     * stream methods
     */
    private static final String[] STREAM_METHODS = {"execute", "print", "collect", "count", "map", "filter", "flatMap"};

    @Override
    /** Name */
    public String name() {
        return "flink-handler";
    }

    @Override
    /** EnabledKey */
    protected String enabledKey() {
        return "flink.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.FLINK;
    }

    @Override
    /** Protocol */
    protected Protocol protocol() {
        return Protocol.INTERNAL;
    }

    @Override
    /** 注册Interceptors */
    protected void registerInterceptors() {
        registerAll(DATA_STREAM, STREAM_METHODS);
    }

    @Override
    /** 构建Target */
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