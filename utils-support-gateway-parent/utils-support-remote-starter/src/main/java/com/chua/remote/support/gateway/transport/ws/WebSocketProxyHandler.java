package com.chua.remote.support.gateway.transport.ws;

import com.chua.remote.support.gateway.core.ratelimit.GatewayRateLimiter;
import com.chua.remote.support.gateway.core.session.SessionManager;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import lombok.extern.slf4j.Slf4j;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.codec.http.FullHttpRequest;

/**
 * WebSocket 代理处理器
 *
 * <p>处理 WebSocket 握手及后续帧（Binary / Text / Ping / Close）的代理转发。
 * 支持 WebSocket 压缩扩展，最大帧大小 10MB。
 *
 * <p>握手阶段通过 {@link GatewayRateLimiter} 限流，
 * 数据帧直接回写到同一连接（Echo），适用于远程桌面等双向代理场景。
 *
 * @author CH
 * @since 4.0.0.41
 */
@Slf4j
public class WebSocketProxyHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    /** 会话管理器 */
    private final SessionManager sessionManager;
    /** 网关限流器 */
    private final GatewayRateLimiter rateLimiter;
    /** WebSocket 握手器 */
    private WebSocketServerHandshaker handshaker;

    /**
     * 构造 WebSocket 代理处理器
     *
     * @param sm 会话管理器
     * @param rl 网关限流器
     */
    public WebSocketProxyHandler(SessionManager sm, GatewayRateLimiter rl) {
        this.sessionManager = sm;
        this.rateLimiter = rl;
    }

    /**
     * 通道激活时日志记录
     *
     * @param ctx Netty 上下文
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.debug("WS连接: {}", ctx.channel().remoteAddress());
    }

    /**
     * 处理 WebSocket 握手请求
     *
     * <p>从 HTTP Upgrade 请求中构建握手响应，支持压缩扩展。
     * 握手失败时直接关闭连接。
     *
     * @param ctx Netty 上下文
     * @param req HTTP 升级请求
     */
    public void handleHandshake(ChannelHandlerContext ctx, io.netty.handler.codec.http.FullHttpRequest req) {
        if (!rateLimiter.tryAcquire()) {
            ctx.close();
            return;
        }
        WebSocketServerHandshakerFactory f = new WebSocketServerHandshakerFactory(getLocation(req), null, true, 10485760);
        handshaker = f.newHandshaker(req);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
        }
        else handshaker.handshake(ctx.channel(), req).addListener((ChannelFutureListener) f2 -> {
            if (!f2.isSuccess()) {
                ctx.close();
            }
        });
    }

    /**
     * 处理收到的 WebSocket 帧
     *
     * <p>Close 帧：关闭连接；Ping 帧：回复 Pong；
     * Binary/Text 帧：直接 Echo 回客户端。
     *
     * @param ctx   Netty 上下文
     * @param frame WebSocket 帧
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof CloseWebSocketFrame) {
            handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain());
            return;
        }
        if (frame instanceof PingWebSocketFrame) {
            ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
            return;
        }
        if (frame instanceof BinaryWebSocketFrame || frame instanceof TextWebSocketFrame) {
            ByteBuf data = frame.content().retain();
            log.debug("WS数据: remote={} len={}", ctx.channel().remoteAddress(), data.readableBytes());
            ctx.writeAndFlush(new BinaryWebSocketFrame(data));
        }
    }

    /**
     * 从 HTTP 请求中提取 WebSocket 服务地址
     *
     * @param req HTTP 请求
     * @return ws://host 格式的地址
     */
    private static String getLocation(io.netty.handler.codec.http.FullHttpRequest req) {
        CharSequence host = req.headers().get(HttpHeaderNames.HOST);
        return "ws://" + (host != null ? host.toString() : "localhost");
    }

    /**
     * 初始化 HTTP 升级前的 Pipeline
     *
     * <p>添加日志、HTTP 编解码器和聚合器，用于接收完整的升级请求。
     *
     * @return 初始 ChannelHandler 数组
     */
    public static ChannelHandler[] initialPipeline() {
        return new ChannelHandler[]{
                new LoggingHandler(LogLevel.DEBUG), new HttpServerCodec(65536, 8192, 8192),
                new HttpObjectAggregator(65536)
        };
    }

    /**
     * 异常处理：关闭连接
     *
     * @param ctx   Netty 上下文
     * @param cause 异常
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
