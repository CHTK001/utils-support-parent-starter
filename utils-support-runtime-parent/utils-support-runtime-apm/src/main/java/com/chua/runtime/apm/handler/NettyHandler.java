package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Netty 处理器 — intercepts Netty 通道 operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class NettyHandler extends AbstractAppHandler {

    /**
     * 通道
     */
    private static final String CHANNEL = "io/netty/channel/Channel";
    /**
     * 通道 处理器 上下文
     */
    private static final String CHANNEL_HANDLER_CONTEXT = "io/netty/channel/ChannelHandlerContext";
    /**
     * 通道 方法
     */
    private static final String[] CHANNEL_METHODS = {"write", "writeAndFlush", "read"};
    /**
     * 上下文 方法
     */
    private static final String[] CONTEXT_METHODS = {"fireChannelRead", "fireChannelActive", "fireChannelInactive"};

    /**
     * 名称
    */
    @Override
    public String name() {
        return "netty-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "netty.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.NETTY;
    }

    /**
     * 协议
    */
    @Override
    protected Protocol protocol() {
        return Protocol.TCP;
    }

    /**
     * 注册拦截器
    */
    @Override
    protected void registerInterceptors() {
        registerAll(CHANNEL, CHANNEL_METHODS);
        registerAll(CHANNEL_HANDLER_CONTEXT, CONTEXT_METHODS);
    }

    /**
     * 构建Target
    */
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