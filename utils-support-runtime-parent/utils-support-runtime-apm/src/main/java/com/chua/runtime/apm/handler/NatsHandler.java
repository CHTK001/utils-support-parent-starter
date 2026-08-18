package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * NATS Handler — intercepts NATS publish/subscribe operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class NatsHandler extends AbstractAppHandler {

    /**
     * nats 连接
     */
    private static final String NATS_CONNECTION = "io/nats/client/Connection";
    /**
     * publish methods
     */
    private static final String[] PUBLISH_METHODS = {"publish"};
    /**
     * subscribe methods
     */
    private static final String[] SUBSCRIBE_METHODS = {"subscribe"};

    @Override
    public String name() {
        return "nats-handler";
    }

    @Override
    protected String enabledKey() {
        return "nats.enabled";
    }

    @Override
    protected Software software() {
        return Software.NATS;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.NATS;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(NATS_CONNECTION, PUBLISH_METHODS);
        registerAll(NATS_CONNECTION, SUBSCRIBE_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.NATS)
                .software(Software.NATS)
                .host("nats")
                .port(Protocol.NATS.defaultPort())
                .path("/")
                .build();
    }
}