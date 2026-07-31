package com.chua.remote.support.gateway.transport.http;

import com.chua.remote.support.gateway.core.ratelimit.GatewayRateLimiter;
import com.chua.remote.support.gateway.core.router.TargetEntry;
import com.chua.remote.support.gateway.core.router.TargetRegistry;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;

/**
 * HTTP 请求转发处理器
 *
 * <p>接收客户端 HTTP 请求，根据 {@code X-Target-Id} 头部将其转发到
 * 注册的后端目标服务。支持 Full HTTP 请求的聚合和响应回写。
 *
 * <p>当后端目标不可达或转发失败时，返回 502 Bad Gateway 错误。
 *
 * @author CH
 * @since 4.0.0.41
 */
@Slf4j
public class HttpForwardHandler extends SimpleChannelInboundHandler<HttpObject> {
    /** 目标注册表，用于根据 ID 查找后端地址 */
    private final TargetRegistry targetRegistry;
    /** 网关限流器 */
    private final GatewayRateLimiter rateLimiter;
    /** 后端连接 Worker 线程组 */
    private final EventLoopGroup workerGroup = new io.netty.channel.nio.NioEventLoopGroup(1);
    /** 当前正在处理的 HTTP 请求 */
    private HttpRequest currentRequest;
    /** 当前请求的 body 累积缓冲区 */
    private ByteBuf currentContent;

    /**
     * 构造 HTTP 转发处理器
     *
     * @param tr 目标注册表
     * @param rl 网关限流器
     */
    public HttpForwardHandler(TargetRegistry tr, GatewayRateLimiter rl) { this.targetRegistry = tr; this.rateLimiter = rl; }

    /**
     * 接收并聚合 HTTP 消息，收到 LastHttpContent 后触发转发
     *
     * @param ctx Netty 上下文
     * @param msg HTTP 对象（请求/内容）
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
        if (msg instanceof HttpRequest req) {
            currentRequest = req;
            currentContent = Unpooled.buffer();
        }
        if (msg instanceof HttpContent c) {
            if (currentContent != null) {
                currentContent.writeBytes(c.content());
            }
        }
        if (msg instanceof LastHttpContent) {
            forward(ctx);
        }
    }

    /**
     * 执行 HTTP 请求转发
     *
     * <p>从请求头中获取 {@code X-Target-Id}，通过 {@link TargetRegistry} 查找后端地址，
     * 建立新的 TCP 连接并发送完整的 HTTP 请求。
     *
     * @param ctx Netty 上下文
     */
    private void forward(ChannelHandlerContext ctx) {
        String targetId = currentRequest.headers().get("X-Target-Id") != null ? currentRequest.headers().get("X-Target-Id").toString() : null;
        if (targetId == null) { sendErr(ctx, HttpResponseStatus.BAD_REQUEST, "Missing X-Target-Id"); return; }
        TargetEntry target = targetRegistry.lookup(targetId);
        if (target == null) { sendErr(ctx, HttpResponseStatus.BAD_GATEWAY, "Target offline"); return; }
        Bootstrap b = new Bootstrap();
        b.group(workerGroup).channel(NioSocketChannel.class).option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpClientCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(1048576));
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpResponse>() {
                            /**
                             * 收到后端响应后写回客户端
                             */
                            @Override protected void channelRead0(ChannelHandlerContext ac, FullHttpResponse resp) {
                                FullHttpResponse cr = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, resp.status(), resp.content().retain());
                                cr.headers().set(resp.headers());
                                cr.headers().set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(resp.content().readableBytes()));
                                ctx.writeAndFlush(cr).addListener(ChannelFutureListener.CLOSE);
                            }
                            @Override public void exceptionCaught(ChannelHandlerContext ac, Throwable cause) { sendErr(ctx, HttpResponseStatus.BAD_GATEWAY, "forward error"); }
                        });
                    }
                });
        b.connect(target.getHost(), target.getPort()).addListener((ChannelFutureListener) f -> {
            if (f.isSuccess()) {
                DefaultFullHttpRequest fr = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.valueOf(currentRequest.method().name().toString()), currentRequest.uri().toString(), currentContent.retain());
                fr.headers().set(currentRequest.headers());
                f.channel().writeAndFlush(fr);
            } else sendErr(ctx, HttpResponseStatus.BAD_GATEWAY, "connect failed");
        });
    }

    /**
     * 向客户端发送错误响应
     *
     * @param ctx    Netty 上下文
     * @param status HTTP 状态码
     * @param msg    错误描述文本
     */
    private void sendErr(ChannelHandlerContext ctx, HttpResponseStatus status, String msg) {
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(msg, java.nio.charset.StandardCharsets.UTF_8));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(resp.content().readableBytes()));
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * 异常处理：关闭连接
     *
     * @param ctx   Netty 上下文
     * @param cause 异常
     */
    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { ctx.close(); }
}
