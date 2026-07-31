package com.chua.remote.support.gateway.transport.tcp;

import com.chua.remote.support.gateway.core.ratelimit.GatewayRateLimiter;
import com.chua.remote.support.gateway.core.session.SessionManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

/**
 * TCP 中继处理器
 *
 * <p>负责在两条 TCP 连接之间透明转发数据。
 * 当一端收到数据时，直接写入对端 channel；当一端断开时，
 * 主动关闭对端并清理会话。
 *
 * <p>同时支持在断开时释放限流器资源。
 *
 * @author CH
 * @since 4.0.0.41
 */
@Slf4j
public class TcpRelayHandler extends ChannelInboundHandlerAdapter {
    /** 对端 Channel，数据将转发至此 */
    private final Channel peer;
    /** 当前中继所属的会话 ID */
    /**
     * 会话 ID
     */
    private final String sessionId;
    /** 会话管理器，用于关闭会话时清理 */
    private final SessionManager sessionManager;
    /** 网关限流器，连接关闭时释放许可 */
    private final GatewayRateLimiter rateLimiter;

    /**
     * 构造 TCP 中继处理器
     *
     * @param peer           对端 Channel
     * @param sessionId      会话 ID
     * @param sm             会话管理器
     * @param rl             网关限流器
     */
    public TcpRelayHandler(Channel peer, String sessionId, SessionManager sm, GatewayRateLimiter rl) {
        this.peer = peer;
        this.sessionId = sessionId;
        this.sessionManager = sm;
        this.rateLimiter = rl;
    }

    /**
     * 读取到数据时转发给对端
     *
     * <p>仅处理 {@link ByteBuf} 类型消息，其他类型透传。
     *
     * @param ctx Netty 上下文
     * @param msg 收到的消息
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf buf) {
            if (peer.isActive()) { peer.writeAndFlush(buf.retain()); }

            else { buf.release(); ctx.close(); }
        } else ctx.fireChannelRead(msg);
    }

    /**
     * 通道断开时关闭对端连接并清理会话
     *
     * @param ctx Netty 上下文
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (peer.isActive()) { peer.close(); }
        if (sessionId != null) { sessionManager.closeSession(sessionId); }
        rateLimiter.release();
    }

    /**
     * 异常处理：关闭双方连接
     *
     * @param ctx   Netty 上下文
     * @param cause 异常
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("中继异常 session={}", sessionId, cause);
        if (peer.isActive()) { peer.close(); }
        ctx.close();
    }
}
