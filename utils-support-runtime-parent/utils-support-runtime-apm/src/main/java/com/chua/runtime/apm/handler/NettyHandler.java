package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Netty Handler — intercepts Netty channel operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class NettyHandler extends AbstractAppHandler {

    /**
     * CHANNEL
     */
    private static final String CHANNEL = "io/netty/channel/Channel";
    /**
     * channel handler context
     */
    private static final String CHANNEL_HANDLER_CONTEXT = "io/netty/channel/ChannelHandlerContext";
    /**
     * channel methods
     */
    private static final String[] CHANNEL_METHODS = {"write", "writeAndFlush", "read"};
    /**
     * context methods
     */
    private static final String[] CONTEXT_METHODS = {"fireChannelRead", "fireChannelActive", "fireChannelInactive"};

    @Override
    public String name() {
        return "netty-handler";
    }

    @Override
    protected String enabledKey() {
        return "netty.enabled";
    }

    @Override
    protected Software software() {
        return Software.NETTY;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.TCP;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(CHANNEL, CHANNEL_METHODS);
        registerAll(CHANNEL_HANDLER_CONTEXT, CONTEXT_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.TCP)
                .software(Software.NETTY)
                .host("netty")
                .port(0)
                .path("/")
                .build();
    }
}