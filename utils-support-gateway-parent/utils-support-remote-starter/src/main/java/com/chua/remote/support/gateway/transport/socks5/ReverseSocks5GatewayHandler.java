package com.chua.remote.support.gateway.transport.socks5;

import com.chua.remote.support.spi.GatewayTokenVerifier;
import com.chua.remote.support.gateway.agent.AgentInfo;
import com.chua.remote.support.gateway.agent.AgentRegistry;
import com.chua.remote.support.gateway.config.GatewayConfigService;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.socksx.SocksMessage;
import io.netty.handler.codec.socksx.SocksVersion;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandResponse;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialResponse;
import io.netty.handler.codec.socksx.v5.DefaultSocks5PasswordAuthResponse;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequest;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequest;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthStatus;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * Gateway 暴露。SOCKS5 入口）岃证后通过在线 Agent 建立反向 TCP 隧道。

 * @author CH
 */@Slf4j
public class ReverseSocks5GatewayHandler extends SimpleChannelInboundHandler<SocksMessage> {

    private final AgentRegistry agentRegistry;
    private final GatewayConfigService configService;
    private final ReverseSocks5TunnelManager tunnelManager;
    private AgentInfo authenticatedAgent;

    public ReverseSocks5GatewayHandler(AgentRegistry agentRegistry,
                                       GatewayConfigService configService,
                                       ReverseSocks5TunnelManager tunnelManager) {
        this.agentRegistry = agentRegistry;
        this.configService = configService;
        this.tunnelManager = tunnelManager;
    }

    /**
     * channelRead0
     * @param ctx 参数
     * @param socksRequest 参数
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, SocksMessage socksRequest) {
        if (socksRequest.version() != SocksVersion.SOCKS5) {
            ctx.close();
            return;
        }
        switch (socksRequest) {
            case Socks5InitialRequest initialRequest -> handleInitialRequest(ctx, initialRequest);
            case Socks5PasswordAuthRequest authRequest -> handlePasswordAuth(ctx, authRequest);
            case Socks5CommandRequest commandRequest -> handleCommand(ctx, commandRequest);
            default -> ctx.close();
        }
    }

    private void handleInitialRequest(ChannelHandlerContext ctx, Socks5InitialRequest initialRequest) {
        if (!initialRequest.authMethods().contains(Socks5AuthMethod.PASSWORD)) {
            ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.UNACCEPTED))
                    .addListener(ignored -> ctx.close());
            return;
        }
        ChannelPipeline pipeline = ctx.pipeline();
        pipeline.addFirst(new Socks5CommandRequestDecoder());
        pipeline.addFirst(new Socks5PasswordAuthRequestDecoder());
        ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.PASSWORD));
    }

    private void handlePasswordAuth(ChannelHandlerContext ctx, Socks5PasswordAuthRequest authRequest) {
        String verifyCode = authRequest.username();
        String token = authRequest.password();
        AgentInfo agent = authenticate(verifyCode, token);
        if (agent == null) {
            log.warn("SOCKS5 认证失败: verifyCode={} remote={}", verifyCode, ctx.channel().remoteAddress());
            ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE))
                    .addListener(ignored -> ctx.close());
            return;
        }
        authenticatedAgent = agent;
        ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS));
    }

    private AgentInfo authenticate(String verifyCode, String token) {
        if (configService == null || verifyCode == null || verifyCode.isBlank() || token == null || token.isBlank()) {
            return null;
        }
        if (!configService.verifyToken(token)) {
            return null;
        }
        AgentInfo agent = agentRegistry.findByVerifyCode(verifyCode);
        if (agent == null || agent.getChannel() == null || !agent.getChannel().isActive()) {
            return null;
        }
        GatewayTokenVerifier.TokenAuth auth = configService.authenticateToken(token);
        if (auth != null && !auth.canAccessAgent(agent.getAgentId())) {
            log.warn("SOCKS5 令牌无权访问 Agent: agentId={} verifyCode={}", agent.getAgentId(), verifyCode);
            return null;
        }
        if (!agentRegistry.isSocks5AccessEnabled(agent.getAgentId())) {
            log.warn("SOCKS5 接入已停。 agentId={} verifyCode={}", agent.getAgentId(), verifyCode);
            return null;
        }
        return agent;
    }

    private void handleCommand(ChannelHandlerContext ctx, Socks5CommandRequest commandRequest) {
        if (authenticatedAgent == null) {
            ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FORBIDDEN, commandRequest.dstAddrType()))
                    .addListener(ignored -> ctx.close());
            return;
        }
        if (commandRequest.type() != Socks5CommandType.CONNECT) {
            ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.COMMAND_UNSUPPORTED, commandRequest.dstAddrType()))
                    .addListener(ignored -> ctx.close());
            return;
        }

        ctx.channel().config().setAutoRead(false);
        String host = commandRequest.dstAddr();
        int port = commandRequest.dstPort();
        String addrType = addressTypeName(commandRequest.dstAddrType());
        tunnelManager.openTunnel(authenticatedAgent, ctx.channel(), host, port, addrType)
                .whenComplete((result, error) -> ctx.executor().execute(() -> {
                    if (error != null || result == null || !result.success()) {
                        Socks5CommandStatus status = result != null ? result.status() : Socks5CommandStatus.FAILURE;
                        ctx.writeAndFlush(new DefaultSocks5CommandResponse(status, commandRequest.dstAddrType()))
                                .addListener(ignored -> ctx.close());
                        return;
                    }
                    ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, commandRequest.dstAddrType()))
                            .addListener(ignored -> switchToRelay(ctx, result.streamId()));
                }));
    }

    private void switchToRelay(ChannelHandlerContext ctx, String streamId) {
        ChannelPipeline pipeline = ctx.pipeline();
        removeIfPresent(pipeline, Socks5InitialRequestDecoder.class);
        removeIfPresent(pipeline, Socks5PasswordAuthRequestDecoder.class);
        removeIfPresent(pipeline, Socks5CommandRequestDecoder.class);
        pipeline.addLast(new ClientRelayHandler(streamId, tunnelManager));
        pipeline.remove(this);
        ctx.channel().config().setAutoRead(true);
        ctx.read();
    }

    private void removeIfPresent(ChannelPipeline pipeline, Class<? extends ChannelHandler> handlerType) {
        try {
            ChannelHandler handler = pipeline.get(handlerType);
            if (handler != null) {
                pipeline.remove(handler);
            }
        }
 catch (Exception ignored) {
        }
    }

    private String addressTypeName(Socks5AddressType addressType) {
        if (Socks5AddressType.IPv4.equals(addressType)) { return "IPV4"; }
        if (Socks5AddressType.IPv6.equals(addressType)) { return "IPV6"; }
        if (Socks5AddressType.DOMAIN.equals(addressType)) { return "DOMAIN"; }
        return String.valueOf(addressType);
    }

    /**
     * channelInactive
     * @param ctx 参数
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        tunnelManager.closeClientStreams(ctx.channel());
        super.channelInactive(ctx);
    }

    /**
     * exceptionCaught
     * @param ctx 参数
     * @param cause 参数
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("SOCKS5 Gateway 连接异常: {}", cause.getMessage());
        ctx.close();
    }

    private static final class ClientRelayHandler extends ChannelInboundHandlerAdapter {
        private final String streamId;
        private final ReverseSocks5TunnelManager tunnelManager;

        private ClientRelayHandler(String streamId, ReverseSocks5TunnelManager tunnelManager) {
            this.streamId = streamId;
            this.tunnelManager = tunnelManager;
        }

        /**
         * channelRead
         * @param ctx 参数
         * @param msg 参数
         */
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof ByteBuf buf) {
                try {
                    tunnelManager.forwardClientData(streamId, buf);
                }
 finally {
                    ReferenceCountUtil.release(buf);
                }
            }
 else {
                ReferenceCountUtil.release(msg);
            }
        }

        /**
         * channelInactive
         * @param ctx 参数
         */
        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            tunnelManager.closeFromClient(streamId, "client_closed");
            super.channelInactive(ctx);
        }

        /**
         * exceptionCaught
         * @param ctx 参数
         * @param cause 参数
         */
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
