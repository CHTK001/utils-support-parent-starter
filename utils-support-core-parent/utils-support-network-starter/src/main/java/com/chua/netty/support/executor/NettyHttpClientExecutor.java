package com.chua.netty.support.executor;

import com.chua.common.support.network.client.ClientRequest;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.MultipartBody;
import com.chua.common.support.network.client.spi.HttpClientExecutor;
import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpVersion;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.ConditionalOnClass;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 基于 Netty 4 的 HTTP 客户端执行器（SPI 名称：{@code netty-httpclient}）。
 *
 * <p>使用 Netty NIO 模型实现高性能 HTTP 客户端，支持连接池复用、异步非阻塞 I/O。</p>
 *
 * <p><b>支持的 HTTP 版本：</b>HTTP/1.1（Netty 原生 codec 基于 HTTP/1.x）</p>
 *
 * <p><b>特性说明：</b></p>
 * <ul>
 *   <li><b>连接池复用</b> — 按 host:port 维护连接池，最大 4 条/主机</li>
 *   <li><b>原生异步</b> — 基于 Netty {@link ChannelFuture} 和 {@link CompletableFuture} 实现真正的非阻塞 I/O</li>
 *   <li><b>SSL/TLS</b> — 自动识别 https:// 并配置 SSL 处理器</li>
 *   <li><b>超时控制</b> — 支持连接超时、读取超时、写入超时</li>
 *   <li><b>代理支持</b> — 支持 HTTP 代理</li>
 * </ul>
 *
 * <p><b>已知限制：</b></p>
 * <ul>
 *   <li>仅支持 HTTP/1.1（不支持 HTTP/2 和 HTTP/3）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0
 */
@Spi("netty-httpclient")
@ConditionalOnClass("io.netty.channel.Channel")
public class NettyHttpClientExecutor implements HttpClientExecutor {

    /**
     * 每个主机最大连接数
     */
    private static final int MAX_POOL_SIZE_PER_HOST = 4;

    /**
     * 最大响应体大小：10MB
     */
    private static final int MAX_CONTENT_LENGTH = 10 * 1024 * 1024;

    /**
     * 全局共享的 NIO EventLoopGroup
     */
    private static final EventLoopGroup SHARED_EVENT_LOOP = new NioEventLoopGroup(4);

    /**
     * 全局共享的 SSL 上下文（信任所有证书，适用于开发和内网场景）
     */
    private static final SslContext SHARED_SSL_CONTEXT;

    static {
        SslContext ctx;
        try {
            ctx = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();
        } catch (SSLException e) {
            ctx = null;
        }
        SHARED_SSL_CONTEXT = ctx;
    }

    /**
     * 全局连接池：host:port → 空闲通道队列
     */
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Channel>> connectionPool = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "netty-httpclient";
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("io.netty.channel.Channel");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public int getOrder() {
        return 2;
    }

    @Override
    public List<HttpVersion> supportedVersions() {
        return List.of(HttpVersion.HTTP_1_1);
    }

    @Override
    public ClientResponse execute(ClientRequest request) throws Exception {
        return executeAsync(request).get();
    }

    @Override
    public CompletableFuture<ClientResponse> executeAsync(ClientRequest request) {
        // 版本兼容性检查：Netty HTTP 客户端仅支持 HTTP/1.1
        HttpVersion version = request.getVersion();
        if (version != null && !supportedVersions().contains(version)) {
            CompletableFuture<ClientResponse> failed = new CompletableFuture<>();
            failed.completeExceptionally(new UnsupportedOperationException(
                    "Netty HTTP 客户端不支持 " + version + "，仅支持 " + supportedVersions()));
            return failed;
        }
        int maxRetries = request.getMaxRetries();
        CompletableFuture<ClientResponse> resultFuture = new CompletableFuture<>();
        executeWithRetry(request, 0, maxRetries, resultFuture);
        return resultFuture;
    }

    /**
     * 带重试的异步执行。
     */
    private void executeWithRetry(ClientRequest request, int attempt, int maxRetries,
                                  CompletableFuture<ClientResponse> resultFuture) {
        doExecuteAsync(request).whenComplete((response, error) -> {
            if (error == null) {
                resultFuture.complete(response);
            } else if (attempt < maxRetries) {
                // 递增退避后重试
                SHARED_EVENT_LOOP.next().schedule(
                        () -> executeWithRetry(request, attempt + 1, maxRetries, resultFuture),
                        100L * (attempt + 1), TimeUnit.MILLISECONDS);
            } else {
                resultFuture.completeExceptionally(
                        new RuntimeException("Netty HTTP执行失败(重试" + maxRetries + "次后): " + request.getUrl(), error));
            }
        });
    }

    /**
     * 单次异步执行请求。
     */
    private CompletableFuture<ClientResponse> doExecuteAsync(ClientRequest request) {
        CompletableFuture<ClientResponse> responseFuture = new CompletableFuture<>();
        try {
            URI uri = URI.create(request.getUrl());
            boolean isSsl = "https".equalsIgnoreCase(uri.getScheme());
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : (isSsl ? 443 : 80);

            // 尝试从连接池获取
            Channel pooledChannel = acquireFromPool(host, port);
            if (pooledChannel != null && pooledChannel.isActive()) {
                sendRequest(pooledChannel, request, uri, isSsl, host, port, responseFuture);
            } else {
                connectAndSend(request, uri, isSsl, host, port, responseFuture);
            }
        } catch (Exception e) {
            responseFuture.completeExceptionally(e);
        }
        return responseFuture;
    }

    /**
     * 创建新连接并发送请求。
     */
    private void connectAndSend(ClientRequest request, URI uri, boolean isSsl,
                                String host, int port,
                                CompletableFuture<ClientResponse> responseFuture) {
        String proxyHost = request.getProxyHost();
        String connectHost = (proxyHost != null && !proxyHost.isEmpty()) ? proxyHost : host;
        int connectPort = (proxyHost != null && !proxyHost.isEmpty()) ? request.getProxyPort() : port;

        Bootstrap bootstrap = new Bootstrap()
                .group(SHARED_EVENT_LOOP)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) request.getConnectTimeout())
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        if (isSsl && SHARED_SSL_CONTEXT != null) {
                            pipeline.addLast("ssl", SHARED_SSL_CONTEXT.newHandler(
                                    ch.alloc(), host, port));
                        }
                        if (request.getReadTimeout() > 0) {
                            pipeline.addLast("readTimeout",
                                    new ReadTimeoutHandler(request.getReadTimeout(), TimeUnit.MILLISECONDS));
                        }
                        if (request.getWriteTimeout() > 0) {
                            pipeline.addLast("writeTimeout",
                                    new WriteTimeoutHandler(request.getWriteTimeout(), TimeUnit.MILLISECONDS));
                        }
                        pipeline.addLast("httpCodec", new HttpClientCodec());
                        pipeline.addLast("aggregator", new HttpObjectAggregator(MAX_CONTENT_LENGTH));
                        pipeline.addLast("decompressor", new HttpContentDecompressor());
                        pipeline.addLast("handler", new NettyHttpResponseHandler(responseFuture, request, host, port, connectionPool));
                    }
                });

        bootstrap.connect(connectHost, connectPort).addListener((ChannelFutureListener) cf -> {
            if (cf.isSuccess()) {
                Channel channel = cf.channel();
                sendRequest(channel, request, uri, isSsl, host, port, responseFuture);
            } else {
                responseFuture.completeExceptionally(cf.cause());
            }
        });
    }

    /**
     * 在已建立的连接上发送 HTTP 请求。
     */
    private void sendRequest(Channel channel, ClientRequest request, URI uri,
                             boolean isSsl, String host, int port,
                             CompletableFuture<ClientResponse> responseFuture) {
        // 如果 handler 已存在且是新 future，更新它
        NettyHttpResponseHandler handler = channel.pipeline().get(NettyHttpResponseHandler.class);
        if (handler != null) {
            handler.reset(responseFuture, request);
        }

        try {
            FullHttpRequest nettyRequest = buildNettyRequest(request, uri, host, port);
            channel.writeAndFlush(nettyRequest).addListener((ChannelFutureListener) wf -> {
                if (!wf.isSuccess()) {
                    responseFuture.completeExceptionally(wf.cause());
                }
            });
        } catch (Exception e) {
            responseFuture.completeExceptionally(e);
        }
    }

    /**
     * 构建 Netty HTTP 请求对象。
     */
    private FullHttpRequest buildNettyRequest(ClientRequest request, URI uri, String host, int port) {
        // 请求路径
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String query = uri.getRawQuery();
        if (query != null && !query.isEmpty()) {
            path = path + "?" + query;
        }

        // 请求体
        ByteBuf content = resolveBody(request);
        HttpMethod method = HttpMethod.valueOf(request.getMethod().name());

        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(
                io.netty.handler.codec.http.HttpVersion.HTTP_1_1, method, path, content);

        // 设置 Host 头
        boolean defaultPort = (!"https".equalsIgnoreCase(uri.getScheme()) && port == 80)
                || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443);
        nettyRequest.headers().set(HttpHeaderNames.HOST,
                defaultPort ? host : host + ":" + port);

        // 设置连接保持
        nettyRequest.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);

        // 设置 Accept
        if (request.getHeaders().get("Accept") == null) {
            nettyRequest.headers().set(HttpHeaderNames.ACCEPT, "*/*");
        }

        // 复制请求头
        if (request.getHeaders() != null) {
            for (Map.Entry<String, String> entry : request.getHeaders().toMap().entrySet()) {
                nettyRequest.headers().set(entry.getKey(), entry.getValue());
            }
        }

        // 设置 Content-Length
        nettyRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());

        return nettyRequest;
    }

    /**
     * 解析请求体为 ByteBuf。
     *
     * <p>支持以下请求体类型：</p>
     * <ul>
     *   <li>{@code null} — 空体（GET/DELETE 等无体请求）</li>
     *   <li>{@code byte[]} — 二进制数据</li>
     *   <li>{@link String} — 文本数据</li>
     *   <li>{@link MultipartBody} — multipart/form-data，自动序列化并设置 Content-Type 头</li>
     *   <li>其他对象 — 调用 {@link Object#toString()} 转为字符串</li>
     * </ul>
     */
    private ByteBuf resolveBody(ClientRequest request) {
        Object body = request.getBody();
        if (body == null) {
            return Unpooled.EMPTY_BUFFER;
        }
        if (body instanceof byte[] bytes) {
            return Unpooled.wrappedBuffer(bytes);
        }
        if (body instanceof String str) {
            return Unpooled.copiedBuffer(str, StandardCharsets.UTF_8);
        }
        if (body instanceof MultipartBody multipart) {
            // 设置 Content-Type 头（含 boundary 分隔符），若尚未设置
            if (request.getHeaders().get("Content-Type") == null) {
                request.getHeaders().add("Content-Type", multipart.getContentType());
            }
            return Unpooled.wrappedBuffer(multipart.toBytes());
        }
        return Unpooled.copiedBuffer(body.toString(), StandardCharsets.UTF_8);
    }

    /**
     * 从连接池获取空闲通道。
     */
    private Channel acquireFromPool(String host, int port) {
        String key = host + ":" + port;
        ConcurrentLinkedDeque<Channel> pool = connectionPool.get(key);
        if (pool == null) {
            return null;
        }
        Channel channel;
        while ((channel = pool.pollFirst()) != null) {
            if (channel.isActive()) {
                return channel;
            }
        }
        return null;
    }

    /**
     * 将通道归还连接池。
     */
    private void returnToPool(String host, int port, Channel channel) {
        // 检查通道是否处于活跃状态，若不活跃则直接返回，不进行归还操作
        if (!channel.isActive()) {
            return;
        }
        // 拼接主机和端口作为连接池的唯一标识键
        String key = host + ":" + port;
        // 根据键获取对应的连接池，若不存在则创建一个新的并发双端队列并放入Map中
        ConcurrentLinkedDeque<Channel> pool = connectionPool.computeIfAbsent(key,
                k -> new ConcurrentLinkedDeque<>());
        // 判断当前连接池大小是否小于每个主机允许的最大连接数
        if (pool.size() < MAX_POOL_SIZE_PER_HOST) {
            // 若未达到上限，将通道添加到连接池的末尾以供复用
            pool.offerLast(channel);
        } else {
            // 若已达到上限，则关闭该通道释放资源
            channel.close();
        }
    }

    /**
     * 关闭并清空连接池，释放所有连接资源。
     */
    @Override
    public void close() {
        // 关闭所有池中的连接
        connectionPool.values().forEach(pool -> {
            Channel ch;
            while ((ch = pool.pollFirst()) != null) {
                ch.close();
            }
        });
        connectionPool.clear();
    }

    // ==================== 内部响应处理器 ====================

    /**
     * Netty HTTP 响应处理器，收集完整 HTTP 响应并转换为 {@link ClientResponse}。
     *
     * <p>由于使用了 {@link HttpObjectAggregator}，响应体会被聚合为单个 {@link FullHttpResponse}，
     * 无需手动处理分块传输。</p>
     */
    private static class NettyHttpResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {

        private CompletableFuture<ClientResponse> responseFuture;
        private ClientRequest request;
        private final String host;
        private final int port;
        private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Channel>> connectionPool;

        NettyHttpResponseHandler(CompletableFuture<ClientResponse> responseFuture,
                                 ClientRequest request, String host, int port,
                                 ConcurrentHashMap<String, ConcurrentLinkedDeque<Channel>> pool) {
            this.responseFuture = responseFuture;
            this.request = request;
            this.host = host;
            this.port = port;
            this.connectionPool = pool;
        }

        /**
         * 重置响应Future和请求对象
         */
        void reset(CompletableFuture<ClientResponse> newFuture, ClientRequest newRequest) {
            this.responseFuture = newFuture;
            this.request = newRequest;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse msg) {
            ClientResponse response = new ClientResponse();
            response.setStatusCode(msg.status().code());
            response.setMessage(msg.status().reasonPhrase());

            // 响应体
            ByteBuf content = msg.content();
            byte[] body = new byte[content.readableBytes()];
            content.readBytes(body);
            response.setBody(body);

            // 响应头
            HttpHeader headers = HttpHeader.create();
            for (Map.Entry<String, String> entry : msg.headers()) {
                headers.add(entry.getKey(), entry.getValue());
            }
            response.setHeaders(headers);
            response.setVersion(HttpVersion.HTTP_1_1);

            // 判断是否保持连接
            boolean keepAlive = HttpUtil.isKeepAlive(msg);

            // 先完成 future
            responseFuture.complete(response);

            // 归还连接到池或关闭
            if (keepAlive) {
                Channel channel = ctx.channel();
                ConcurrentHashMap<String, ConcurrentLinkedDeque<Channel>> pool = connectionPool;
                channel.eventLoop().execute(() -> {
                    String key = host + ":" + port;
                    ConcurrentLinkedDeque<Channel> deque = pool.computeIfAbsent(key,
                            k -> new ConcurrentLinkedDeque<>());
                    if (deque.size() < MAX_POOL_SIZE_PER_HOST && channel.isActive()) {
                        deque.offerLast(channel);
                    } else {
                        channel.close();
                    }
                });
            } else {
                ctx.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            responseFuture.completeExceptionally(cause);
            ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (!responseFuture.isDone()) {
                responseFuture.completeExceptionally(
                        new RuntimeException("Netty连接意外关闭: " + host + ":" + port));
            }
        }
    }
}
