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

    private static final String DATA_STREAM = "org/apache/flink/streaming/api/datastream/DataStream";
    private static final String[] STREAM_METHODS = {"execute", "print", "collect", "count", "map", "filter", "flatMap"};

    @Override
    public String name() {
        return "flink-handler";
    }

    @Override
    protected String enabledKey() {
        return "flink.enabled";
    }

    @Override
    protected Software software() {
        return Software.FLINK;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.INTERNAL;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(DATA_STREAM, STREAM_METHODS);
    }

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