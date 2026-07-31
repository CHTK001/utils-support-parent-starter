package com.chua.remote.support.gateway.transport.tcp;

import com.chua.remote.support.gateway.config.ConnectionMode;
import com.chua.remote.support.gateway.config.Protocol;
import com.chua.remote.support.gateway.core.auth.AclManager;
import com.chua.remote.support.gateway.core.auth.AuthHandler;
import com.chua.remote.support.gateway.core.detector.ProtocolDetector;
import com.chua.remote.support.gateway.core.ratelimit.GatewayRateLimiter;
import com.chua.remote.support.gateway.core.router.TargetEntry;
import com.chua.remote.support.gateway.core.router.TargetRegistry;
import com.chua.remote.support.gateway.core.session.GatewaySession;
import com.chua.remote.support.gateway.core.session.SessionManager;
import com.chua.remote.support.gateway.transport.http.HttpForwardHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * TCP 前端接入处理器
 * <p>
 * 处理来自控制端的前端 TCP 连接。工作流程：
 * <ol>
 *   <li>协议嗅探（ProtocolDetector）：自动识别 SSH / HTTP / RDP / VNC 等协议</li>
 *   <li>认证与鉴权（AuthHandler + AclManager）</li>
 *   <li>限流检查</li>
 *   <li>根据连接模式（长连接/短连接）分发：
 *     <ul>
 *       <li>LONG：创建持久会话，通过 TcpRelayHandler 双向中继到 Agent</li>
 *       <li>SHORT：HTTP 转发或单次 TCP 代理</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * @author CH
 */
@Slf4j
public class TcpFrontendHandler extends ChannelInboundHandlerAdapter {
    /** 认证处理器，执行连接认证 */
    private final AuthHandler authHandler;
    /** ACL 管理器，检查客户端对目标的访问权限 */
    private final AclManager aclManager;
    /** 网关限流器 */
    private final GatewayRateLimiter rateLimiter;
    /** 目标注册表 */
    private final TargetRegistry targetRegistry;
    /** 会话管理器 */
    private final SessionManager sessionManager;
    /** Netty worker 线程组，用于建立到 Agent 的出站连接 */
    private final EventLoopGroup workerGroup;
    /** 是否已完成协议嗅探 */
    private boolean sniffed;
    /** 嗅探识别出的协议类型 */
    private Protocol protocol;
    /** 连接模式（长连接 / 短连接） */
    private ConnectionMode mode;
    /** 认证后的客户端标识 */
    /**
     * 客户端 ID
     */
    private String clientId;

    /**
     * 构造 TCP 前端处理器
     *
     * @param authHandler    认证处理器
     * @param aclManager    ACL 权限管理器
     * @param rateLimiter   限流器
     * @param targetRegistry 目标注册表
     * @param sessionManager 会话管理器
     * @param workerGroup   用于连接 Agent 的线程组
     */
    public TcpFrontendHandler(AuthHandler authHandler, AclManager aclManager,
                               GatewayRateLimiter rateLimiter, TargetRegistry targetRegistry,
                               SessionManager sessionManager, EventLoopGroup workerGroup) {
        this.authHandler = authHandler;
        this.aclManager = aclManager;
        this.rateLimiter = rateLimiter;
        this.targetRegistry = targetRegistry;
        this.sessionManager = sessionManager;
        this.workerGroup = workerGroup;
    }

    /**
     * 通道读事件。首次读取时执行协议嗅探、认证和限流，之后按模式分发。
     * <ul>
     *   <li>嗅探识别协议（SSH/HTTP/RDP/VNC 等）和连接模式（长/短）</li>
     *   <li>认证失败或限流拒绝时直接关闭连接</li>
     *   <li>长连接：建立持久会话并通过 TcpRelayHandler 双向中继</li>
     *   <li>短连接：HTTP 转发或单次 TCP 代理</li>
     * </ul>
     *
     * @param ctx 通道处理器上下文
     * @param msg 读取的消息（预期为 ByteBuf）
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf buf)) { ctx.fireChannelRead(msg); return; }
        try {
            if (!sniffed) {
                var result = ProtocolDetector.sniff(buf);
                protocol = result.getProtocol();
                mode = result.getMode();
                sniffed = true;
                log.debug("嗅探: {} mode={}", protocol, mode);
                try {
                    clientId = authHandler.authenticate(ctx, protocol);
                } catch (RuntimeException e) {
                    sendAndClose(ctx, "AUTH_FAILED");
                    return;
                }
                if (!rateLimiter.tryAcquire()) { sendAndClose(ctx, "RATE_LIMITED"); return; }
                if (mode == ConnectionMode.LONG) { handleLong(ctx, buf); }
                else handleShort(ctx, buf);
            }
 else { ctx.fireChannelRead(buf.retain()); }
        }
 finally { buf.release(); }
    }

    /**
     * 处理长连接模式：查找目标、鉴权、创建会话、建立到 Agent 的中继通道
     *
     * @param ctx      前端通道上下文
     * @param firstBuf 首次读取的缓冲区（含协议头数据）
     */
    private void handleLong(ChannelHandlerContext ctx, ByteBuf firstBuf) {
        String targetId = resolveTargetId(ctx);
        if (targetId == null) { sendAndClose(ctx, "TARGET_NOT_FOUND"); return; }
        if (!aclManager.checkPermission(clientId, targetId, protocol)) { sendAndClose(ctx, "ACCESS_DENIED"); return; }
        if (!rateLimiter.tryEnter()) { sendAndClose(ctx, "TOO_MANY_CONNECTIONS"); return; }
        TargetEntry target = targetRegistry.lookup(targetId);
        if (target == null) { sendAndClose(ctx, "TARGET_OFFLINE"); rateLimiter.release(); return; }
        GatewaySession session = sessionManager.createSession(targetId, target.getAgentId(), clientId, protocol, mode, ctx.channel());
        connectToAgent(ctx, session, target, firstBuf);
    }

    /**
     * 连接到目标 Agent，建立双向中继通道。
     * 将当前 pipeline 替换为 TcpRelayHandler 实现前端 ↔ Agent 的双向数据转发。
     *
     * @param ctx     前端通道上下文
     * @param session 已创建的网关会话
     * @param target  目标条目（含 Agent 的 host/port）
     * @param firstBuf 首次读取的缓冲区（需转发给 Agent 的数据）
     */
    private void connectToAgent(ChannelHandlerContext ctx, GatewaySession session, TargetEntry target, ByteBuf firstBuf) {
        Bootstrap b = new Bootstrap();
        b.group(workerGroup).channel(NioSocketChannel.class).option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new TcpRelayHandler(ctx.channel(), session.getSessionId(), sessionManager, rateLimiter));
                    }
                });
        b.connect(target.getHost(), target.getPort()).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                Channel agentChannel = future.channel();
                sessionManager.attachAgentChannel(session.getSessionId(), agentChannel);
                ctx.pipeline().remove(this);
                ctx.pipeline().addLast(new TcpRelayHandler(agentChannel, session.getSessionId(), sessionManager, rateLimiter));
                if (firstBuf.readableBytes() > 0) { agentChannel.writeAndFlush(Unpooled.copiedBuffer(firstBuf)); }
                log.info("Agent 通道建立: session={} {}:{}", session.getSessionId(), target.getHost(), target.getPort());
            }
 else {
                sessionManager.closeSession(session.getSessionId());
                rateLimiter.release();
                sendAndClose(ctx, "AGENT_CONNECT_FAILED");
            }
        });
    }

    /**
     * 处理短连接模式。HTTP/HTTPS 请求转发给 HttpForwardHandler，
     * 其他协议直接建立到目标 Agent 的单次连接并转发数据。
     *
     * @param ctx      前端通道上下文
     * @param firstBuf 首次读取的缓冲区
     */
    private void handleShort(ChannelHandlerContext ctx, ByteBuf firstBuf) {
        if (protocol == Protocol.HTTP || protocol == Protocol.HTTPS) {
            ctx.pipeline().addLast(new HttpServerCodec());
            ctx.pipeline().addLast(new HttpForwardHandler(targetRegistry, rateLimiter));
            ctx.pipeline().remove(this);
            ctx.fireChannelRead(Unpooled.copiedBuffer(firstBuf));
        }
 else {
            String targetId = resolveTargetId(ctx);
            if (targetId == null) { sendAndClose(ctx, "TARGET_NOT_FOUND"); return; }
            TargetEntry target = targetRegistry.lookup(targetId);
            if (target == null) { sendAndClose(ctx, "TARGET_OFFLINE"); return; }
            connectAndForward(ctx, target, firstBuf);
        }
    }

    /**
     * 建立到目标的单次 TCP 连接并转发数据（短连接非 HTTP 协议）
     *
     * @param ctx    前端通道上下文
     * @param target 目标条目
     * @param data   需要转发的数据
     */
    private void connectAndForward(ChannelHandlerContext ctx, TargetEntry target, ByteBuf data) {
        Bootstrap b = new Bootstrap();
        b.group(workerGroup).channel(NioSocketChannel.class).option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                            @Override protected void channelRead0(ChannelHandlerContext agentCtx, ByteBuf msg) { ctx.writeAndFlush(msg.retain()); }
                            @Override public void channelInactive(ChannelHandlerContext agentCtx) { ctx.close(); }
                        });
                    }
                });
        b.connect(target.getHost(), target.getPort()).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) { future.channel().writeAndFlush(data.retain()); }
            else sendAndClose(ctx, "AGENT_CONNECT_FAILED");
        });
    }

    /**
     * 解析目标 ID（当前实现为占位，由子类或后续 pipeline 处理）
     *
     * @param ctx 通道处理器上下文
     * @return 目标 ID
     */
    private String resolveTargetId(ChannelHandlerContext ctx) { return null; }
    /**
     * 发送响应消息并关闭连接
     *
     * @param ctx 通道处理器上下文
     * @param msg 响应消息文本
     */
    private void sendAndClose(ChannelHandlerContext ctx, String msg) {
        if (ctx.channel().isActive()) { ctx.writeAndFlush(Unpooled.wrappedBuffer((msg + "\n").getBytes(StandardCharsets.UTF_8)))
                    .addListener(ChannelFutureListener.CLOSE); }
    }
    /**
     * 异常捕获，记录错误日志并关闭连接
     *
     * @param ctx   通道处理器上下文
     * @param cause 异常原因
     */
    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { log.error("TCP前端异常", cause); ctx.close(); }

    /**
     * 通道断开回调，释放限流资源
     *
     * @param ctx 通道处理器上下文
     */
    @Override public void channelInactive(ChannelHandlerContext ctx) { rateLimiter.release(); ctx.fireChannelInactive(); }
}
