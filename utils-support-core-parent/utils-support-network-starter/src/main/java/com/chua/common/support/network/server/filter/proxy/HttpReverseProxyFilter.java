package com.chua.common.support.network.server.filter.proxy;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.ServerAttribute;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.filter.ServerFilterConfig;
import com.chua.common.support.network.server.filter.ReactiveServerFilter;
import com.chua.common.support.network.server.filter.ReactiveFilterChain;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * HTTP 反向代理过滤器，将请求转发到后端服务器。
 *
 * <p>后端地址由 {@link ServerAttribute#getBackendDiscovery(ServerRequest)} 决定，
 * 通常由 {@link com.chua.common.support.network.server.filter.discovery.ServiceDiscoveryServerFilter}
 * 在请求进入时设置。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * HttpReverseProxyFilter proxy = new HttpReverseProxyFilter();
 * server.addFilter(proxy);
 * }</pre>
 *
 * @author CH
 * @since 2026/07/24
 * @see com.chua.common.support.network.server.filter.discovery.ServiceDiscoveryServerFilter
 */
@Slf4j
public class HttpReverseProxyFilter implements ServerFilter, ReactiveServerFilter {

    private EventLoopGroup proxyEventLoopGroup;

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE - 50;
    }

    @Override
    public String supportPath() {
        return null;
    }

    @Override
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.HTTP};
    }

    @Override
    public void init(ServerFilterConfig config) throws Exception {
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        this.proxyEventLoopGroup = new NioEventLoopGroup(threads);
        log.info("HttpReverseProxyFilter 初始化完成, eventLoopThreads={}", threads);
    }

    @Override
    public void destroy() {
        if (proxyEventLoopGroup != null) {
            proxyEventLoopGroup.shutdownGracefully(1, 5, TimeUnit.SECONDS);
        }
    }

    @Override
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        Discovery discovery = ServerAttribute.getBackendDiscovery(request);
        if (discovery == null) {
            chain.doFilter(request, response);
            return;
        }

        String host = discovery.getHost();
        int port = discovery.getPort();
        String path = extractPath(request);

        byte[] reqBody = request.getBody();
        proxyAsync(host, port, path, request.getMethod(),
                request.getHeaders(), reqBody, response);
    }

    @Override
    public java.util.concurrent.CompletionStage<Void> doFilter(ServerRequest request, ServerResponse response,
                                                               ReactiveFilterChain chain) {
        Discovery discovery = ServerAttribute.getBackendDiscovery(request);
        if (discovery == null) {
            return chain.doFilter(request, response);
        }

        String host = discovery.getHost();
        int port = discovery.getPort();
        String path = extractPath(request);

        byte[] reqBody = request.getBody();
        CompletableFuture<Void> future = new CompletableFuture<>();
        proxyAsyncWithFuture(host, port, path, request.getMethod(),
                request.getHeaders(), reqBody, response, future);
        return future;
    }

    private String extractPath(ServerRequest request) {
        String path = request.getPath();
        if (path == null) {
            path = "/";
        }
        String uri = request.getUri();
        if (uri != null && uri.contains("?")) {
            path = uri;
        }
        return path;
    }

    private void proxyAsync(String host, int port, String uri,
                            HttpMethod method,
                            com.chua.common.support.network.http.HttpHeader headers,
                            byte[] body, ServerResponse response) {
        proxyAsyncWithFuture(host, port, uri, method, headers, body, response, null);
    }

    private void proxyAsyncWithFuture(String host, int port, String uri,
                                      HttpMethod method,
                                      com.chua.common.support.network.http.HttpHeader headers,
                                      byte[] body, ServerResponse response,
                                      CompletableFuture<Void> completionFuture) {
        Bootstrap b = new Bootstrap();
        b.group(proxyEventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpClientCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(10 * 1024 * 1024));
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpResponse>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse msg) {
                                try {
                                    if (!response.isEnded()) {
                                        response.setStatus(msg.status().code());
                                        for (String name : msg.headers().names()) {
                                            String lower = name.toLowerCase();
                                            if (!"transfer-encoding".equals(lower)
                                                    && !"content-encoding".equals(lower)
                                                    && !"content-length".equals(lower)) {
                                                response.setHeader(name, msg.headers().get(name));
                                            }
                                        }
                                        ByteBuf contentBuf = msg.content();
                                        byte[] responseBytes = new byte[contentBuf.readableBytes()];
                                        contentBuf.getBytes(contentBuf.readerIndex(), responseBytes);
                                        response.setBody(responseBytes);
                                        response.end();
                                    }
                                } finally {
                                    msg.release();
                                    ctx.close();
                                    if (completionFuture != null) {
                                        completionFuture.complete(null);
                                    }
                                }
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                log.warn("HTTP 反向代理后端异常: {}", cause.getMessage());
                                sendError(response, 502, "Bad Gateway");
                                ctx.close();
                                if (completionFuture != null) {
                                    completionFuture.completeExceptionally(cause);
                                }
                            }
                        });
                    }
                });

        b.connect(host, port).addListener((ChannelFutureListener) connectFuture -> {
            if (!connectFuture.isSuccess()) {
                log.warn("HTTP 反向代理连接失败: {}:{}: {}", host, port, connectFuture.cause().getMessage());
                sendError(response, 502, "Bad Gateway: connection failed");
                if (completionFuture != null) {
                    completionFuture.completeExceptionally(connectFuture.cause());
                }
                return;
            }

            Channel channel = connectFuture.channel();
            io.netty.handler.codec.http.HttpMethod nettyMethod =
                    io.netty.handler.codec.http.HttpMethod.valueOf(method.name());
            ByteBuf content = body != null && body.length > 0
                    ? Unpooled.wrappedBuffer(body) : Unpooled.EMPTY_BUFFER;
            DefaultFullHttpRequest proxyReq = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, nettyMethod, uri, content);
            if (headers != null) {
                for (java.util.Map.Entry<String, String> entry : headers.toMap().entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        String lower = entry.getKey().toLowerCase();
                        if (!"host".equals(lower) && !"connection".equals(lower)) {
                            proxyReq.headers().set(entry.getKey(), entry.getValue());
                        }
                    }
                }
            }
            proxyReq.headers()
                    .set(HttpHeaderNames.HOST, host + ":" + port)
                    .setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
                    .set(HttpHeaderNames.CONNECTION, "close");

            channel.writeAndFlush(proxyReq).addListener((ChannelFutureListener) writeFuture -> {
                if (!writeFuture.isSuccess()) {
                    log.warn("HTTP 反向代理写入失败: {}", writeFuture.cause().getMessage());
                    sendError(response, 502, "Bad Gateway");
                    channel.close();
                    if (completionFuture != null) {
                        completionFuture.completeExceptionally(writeFuture.cause());
                    }
                }
            });
        });
    }

    private void sendError(ServerResponse response, int code, String msg) {
        if (!response.isEnded()) {
            response.setStatus(code);
            response.setBody(msg.getBytes(StandardCharsets.UTF_8));
            response.end();
        }
    }
}