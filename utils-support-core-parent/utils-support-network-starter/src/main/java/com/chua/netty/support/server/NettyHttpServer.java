package com.chua.netty.support.server;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.ssl.SslUtils;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.spi.annotations.Spi;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import lombok.extern.slf4j.Slf4j;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Netty 4.2.15.Final 的 HTTP 服务器实现。
 *
 * <p>使用 Netty NIO 模型，支持虚拟线程处理请求。
 * Linux 下自动使用 Epoll 传输，提高吞吐量。
 * 流水线: {@link HttpServerCodec} → {@link HttpObjectAggregator} → 业务 Handler。
 * SSL 支持 KeyStore（JKS/PKCS12）和 PEM 证书文件两种模式。</p>
 *
 * @author CH
 * @since 2026/07/26
 */
@Slf4j
@Spi({"netty", "netty-http"})
public class NettyHttpServer extends AbstractServer {

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ExecutorService requestExecutor;
    private Channel serverChannel;
    private SslContext sslContext;
    private boolean useEpoll;

    public NettyHttpServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    protected void doStart() {
        try {
            InetSocketAddress addr = new InetSocketAddress(setting.getHost(), setting.getPort());
            this.useEpoll = Epoll.isAvailable();

            int bossThreads = Math.max(setting.getBossThreads(), 1);
            int workerThreads = Math.max(setting.getWorkerThreads(), Runtime.getRuntime().availableProcessors() * 2);

            if (useEpoll) {
                bossGroup = new EpollEventLoopGroup(bossThreads);
                workerGroup = new EpollEventLoopGroup(workerThreads);
                log.info("Netty 使用 Epoll 传输（Linux 原生）");
            } else {
                bossGroup = new NioEventLoopGroup(bossThreads);
                workerGroup = new NioEventLoopGroup(workerThreads);
            }

            ServerSetting.SslConfig ssl = setting.getSsl();
            if (SslUtils.autoPrepare(ssl)) {
                sslContext = createSslContext(ssl);
            }

            requestExecutor = Executors.newVirtualThreadPerTaskExecutor();

            int backlog = Math.max(setting.getBacklog(), 1024);
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(useEpoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, backlog)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.TCP_NODELAY, setting.isTcpNoDelay())
                    .childOption(ChannelOption.SO_REUSEADDR, setting.isSoReuseAddr())
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                            new WriteBufferWaterMark(32 * 1024, 64 * 1024))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            if (sslContext != null) {
                                pipeline.addLast(sslContext.newHandler(ch.alloc(),
                                        setting.getHost(), setting.getPort()));
                            }
                            pipeline.addLast(new HttpServerCodec());
                            pipeline.addLast(new HttpObjectAggregator((int) setting.getMaxRequestSize()));
                            pipeline.addLast(new NettyHttpServerHandler());
                        }
                    });

            ChannelFuture future = b.bind(addr).sync();
            serverChannel = future.channel();
            // 回填实际端口（port=0 时由系统分配）
            if (setting.getPort() == 0) {
                setting.setPort(((InetSocketAddress) serverChannel.localAddress()).getPort());
            }
            log.info("Netty HTTP Server started on {}:{} (epoll={}, backlog={}, bossThreads={}, workerThreads={})",
                    setting.getHost(), setting.getPort(), useEpoll, backlog, bossThreads, workerThreads);
        } catch (Exception e) {
            throw new RuntimeException("Netty HTTP Server 启动失败", e);
        }
    }

    @Override
    protected void doStop() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (requestExecutor != null) {
            requestExecutor.shutdown();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, setting.getShutdownQuietPeriod(), TimeUnit.SECONDS).syncUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, setting.getShutdownQuietPeriod(), TimeUnit.SECONDS).syncUninterruptibly();
        }
        log.info("Netty HTTP Server stopped");
    }

    @Override
    public boolean supportsReactor() {
        return true;
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.HTTP;
    }

    private SslContext createSslContext(ServerSetting.SslConfig ssl) throws Exception {
        // PEM 证书：优先使用 Netty 原生 SslContextBuilder.forServer(File, File)
        if (ssl.getCertPath() != null && ssl.getKeyPath() != null) {
            return SslContextBuilder.forServer(
                    new FileInputStream(ssl.getCertPath()),
                    new FileInputStream(ssl.getKeyPath()))
                    .build();
        }
        // KeyStore 文件或自签名证书：通过 SslUtils 统一加载
        javax.net.ssl.KeyManagerFactory kmf = SslUtils.createKeyManagerFactory(ssl);
        return SslContextBuilder.forServer(kmf).build();
    }

    private class NettyHttpServerHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof FullHttpRequest request) {
                requestExecutor.execute(() -> {
                    NettyServerRequest serverRequest = new NettyServerRequest(request, ctx);
                    NettyServerResponse serverResponse = new NettyServerResponse(ctx, request);
                    try {
                        handleRequest(serverRequest, serverResponse);
                    } finally {
                        serverResponse.complete();
                        request.release();
                    }
                });
            } else {
                ctx.fireChannelRead(msg);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Netty HTTP 处理异常", cause);
            ctx.close();
        }
    }

    static class NettyServerRequest implements ServerRequest {

        private final FullHttpRequest request;
        private final ChannelHandlerContext ctx;
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();

        NettyServerRequest(FullHttpRequest request, ChannelHandlerContext ctx) {
            this.request = request;
            this.ctx = ctx;
        }

        @Override
        public String getUri() {
            return request.uri();
        }

        @Override
        public String getPath() {
            String uri = request.uri();
            int idx = uri.indexOf('?');
            return idx > 0 ? uri.substring(0, idx) : uri;
        }

        @Override
        public com.chua.common.support.network.http.HttpMethod getMethod() {
            try {
                return com.chua.common.support.network.http.HttpMethod.valueOf(request.method().name());
            } catch (Exception e) {
                return com.chua.common.support.network.http.HttpMethod.GET;
            }
        }

        @Override
        public String getHeader(String name) {
            return request.headers().get(name);
        }

        @Override
        public com.chua.common.support.network.http.HttpHeader getHeaders() {
            com.chua.common.support.network.http.HttpHeader header = com.chua.common.support.network.http.HttpHeader.create();
            request.headers().forEach(entry -> header.add(entry.getKey(), entry.getValue()));
            return header;
        }

        @Override
        public Map<String, String> getParams() {
            return java.util.Collections.emptyMap();
        }

        @Override
        public String getParam(String name) {
            return null;
        }

        @Override
        public String getContentType() {
            return request.headers().get(HttpHeaderNames.CONTENT_TYPE);
        }

        @Override
        public long getContentLength() {
            return request.content().readableBytes();
        }

        @Override
        public byte[] getBody() {
            ByteBuf content = request.content();
            byte[] bytes = new byte[content.readableBytes()];
            content.readBytes(bytes);
            return bytes;
        }

        @Override
        public String getBodyString() {
            ByteBuf content = request.content();
            if (content.hasArray()) {
                return new String(content.array(), content.arrayOffset() + content.readerIndex(),
                        content.readableBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            return new String(getBody(), java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public java.io.InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(getBody());
        }

        @Override
        public String getRemoteAddress() {
            if (ctx != null && ctx.channel() != null
                    && ctx.channel().remoteAddress() instanceof InetSocketAddress addr) {
                return addr.getAddress().getHostAddress();
            }
            return "127.0.0.1";
        }

        @Override
        public int getRemotePort() {
            if (ctx != null && ctx.channel() != null
                    && ctx.channel().remoteAddress() instanceof InetSocketAddress addr) {
                return addr.getPort();
            }
            return 0;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public Object getAttribute(String name) {
            return attributes.get(name);
        }

        @Override
        public void setAttribute(String name, Object value) {
            attributes.put(name, value);
        }
    }

    static class NettyServerResponse implements ServerResponse {

        private final ChannelHandlerContext ctx;
        private final FullHttpRequest request;
        private int status = 200;
        private byte[] body;
        private Object result;
        private String contentType;
        private final ConcurrentHashMap<String, String> headers = new ConcurrentHashMap<>();
        private boolean committed;
        private boolean ended;

        NettyServerResponse(ChannelHandlerContext ctx, FullHttpRequest request) {
            this.ctx = ctx;
            this.request = request;
        }

        @Override
        public ServerResponse setStatus(int statusCode) {
            if (!committed) {
                this.status = statusCode;
            }
            return this;
        }

        @Override
        public int getStatus() {
            return status;
        }

        @Override
        public ServerResponse setHeader(String name, String value) {
            if (!committed) {
                headers.put(name, value);
            }
            return this;
        }

        @Override
        public String getHeader(String name) {
            return headers.get(name);
        }

        @Override
        public com.chua.common.support.network.http.HttpHeader getHeaders() {
            com.chua.common.support.network.http.HttpHeader h = com.chua.common.support.network.http.HttpHeader.create();
            headers.forEach(h::add);
            return h;
        }

        @Override
        public ServerResponse setContentType(String ct) {
            this.contentType = ct;
            return this;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public ServerResponse setBody(byte[] b) {
            if (!committed) {
                this.body = b;
            }
            return this;
        }

        @Override
        public ServerResponse setBody(String b) {
            if (!committed) {
                this.body = b != null ? b.getBytes(java.nio.charset.StandardCharsets.UTF_8) : null;
            }
            return this;
        }

        @Override
        public byte[] getBody() {
            return body;
        }

        @Override
        public ServerResponse setResult(Object result) {
            if (!committed) {
                this.result = result;
            }
            return this;
        }

        @Override
        public Object getResult() {
            return result;
        }

        @Override
        public java.io.OutputStream getOutputStream() {
            return new java.io.ByteArrayOutputStream();
        }

        @Override
        public ServerResponse sendRedirect(String location) {
            setStatus(302);
            headers.put("Location", location);
            ended = true;
            writeResponse();
            return this;
        }

        @Override
        public ServerResponse sendError(int code, String msg) {
            if (ended) {
                return this;
            }
            setStatus(code);
            setBody(msg);
            ended = true;
            writeResponse();
            return this;
        }

        @Override
        public void flush() {
        }

        @Override
        public boolean isCommitted() {
            return committed;
        }

        @Override
        public boolean isEnded() {
            return ended;
        }

        @Override
        public void end() {
            if (ended) {
                return;
            }
            ended = true;
            writeResponse();
        }

        @Override
        public ServerResponse reset() {
            if (!committed) {
                status = 200;
                body = null;
                headers.clear();
                contentType = null;
                ended = false;
            }
            return this;
        }

        @Override
        public void writeRaw(byte[] bytes) {
            if (committed) {
                return;
            }
            setBody(bytes);
        }

        void complete() {
            if (!ended) {
                end();
            }
        }

        private void writeResponse() {
            if (committed) {
                return;
            }
            committed = true;

            HttpResponseStatus nettyStatus = HttpResponseStatus.valueOf(status);
            FullHttpResponse response;
            if (body != null && body.length > 0) {
                ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer(body.length);
                buf.writeBytes(body);
                response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, nettyStatus, buf);
            } else {
                response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, nettyStatus);
            }

            HttpHeaders nettyHeaders = response.headers();
            if (contentType != null) {
                nettyHeaders.set(HttpHeaderNames.CONTENT_TYPE, contentType);
            } else {
                nettyHeaders.set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=utf-8");
            }
            nettyHeaders.setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                nettyHeaders.set(entry.getKey(), entry.getValue());
            }

            ctx.writeAndFlush(response).addListener(future -> {
                if (!future.isSuccess()) {
                    log.error("写入响应失败", future.cause());
                }
            });
        }
    }
}