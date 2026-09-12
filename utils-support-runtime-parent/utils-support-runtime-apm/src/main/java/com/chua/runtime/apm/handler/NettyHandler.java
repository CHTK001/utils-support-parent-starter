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

    @Override
    /** 名称 */
    public String name() {
        return "netty-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "netty.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.NETTY;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.TCP;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(CHANNEL, CHANNEL_METHODS);
        registerAll(CHANNEL_HANDLER_CONTEXT, CONTEXT_METHODS);
    }

    @Override
    /** 构建Target */
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