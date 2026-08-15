package com.chua.netty.support.server;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.network.ssl.SslUtils;
import com.chua.common.support.spi.annotations.Spi;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3FrameToHttpObjectCodec;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInitializer;
import io.netty.handler.codec.http3.Http3ServerConnectionHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicServerCodecBuilder;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.KeyManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 基于 QUIC (HTTP/3) 的 HTTP 服务器实现。
 *
 * <p>使用 netty-codec-http3（QUIC over UDP）承载 HTTP/3 请求，
 * 复用 {@link AbstractServer} 统一请求链（过滤器 / 路由 / 指标）。
 * 基于 k6 / curl 等标准 HTTP/3 客户端（ALPN h3）即可访问。</p>
 *
 * <p>特性：</p>
 * <ul>
 *   <li>QUIC 0-RTT/1-RTT 握手（TLS 1.3），UDP 传输无 TCP 队头阻塞</li>
 *   <li>多路复用（HTTP/3 QUIC 流），单连接并发多请求</li>
 *   <li>自签名证书一键生成（{@code selfSignedAuto}）或 KeyStore/PEM 加载</li>
 * </ul>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * ServerSetting setting = ServerSetting.defaults();
 * setting.setHost("0.0.0.0");
 * setting.setPort(19443);
 * setting.getSsl().setSelfSignedAuto(true); // 自签名证书
 * QuicHttpServer server = new QuicHttpServer(setting);
 * server.registerMapping("/echo", (req, resp) -> resp.setResult("quic-ok"));
 * server.start();
 * }</pre>
 *
 * @author CH
 * @since 2026/08/15
 */
@Slf4j
@Spi({"quic-http", "http3", "quic"})
public class QuicHttpServer extends AbstractServer {

    private EventLoopGroup group;
    private io.netty.channel.Channel serverChannel;

    public QuicHttpServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    public String getProtocol() {
        return "quic-http";
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.HTTP;
    }

    @Override
    protected void doStart() {
        try {
            QuicSslContext sslContext = createQuicSslContext();
            group = new NioEventLoopGroup(Math.max(1, setting.getBossThreads()));

            QuicServerCodecBuilder builder = new QuicServerCodecBuilder()
                    .sslContext(sslContext)
                    .maxIdleTimeout(10000, TimeUnit.MILLISECONDS)
                    .initialMaxData(10_000_000)
                    .initialMaxStreamDataBidirectionalLocal(1_000_000)
                    .initialMaxStreamDataBidirectionalRemote(1_000_000)
                    .initialMaxStreamsBidirectional(100)
                    .streamHandler(new Http3ServerConnectionHandler(new Http3RequestStreamInitializer() {
                        @Override
                        protected void initRequestStream(QuicStreamChannel ch) {
                            ch.pipeline().addLast(new Http3FrameToHttpObjectCodec(false));
                            ch.pipeline().addLast(new QuicHttpServerHandler());
                        }
                    }));

            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioDatagramChannel.class)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .handler(builder.build());

            ChannelFuture future = bootstrap.bind(setting.getHost(), setting.getPort()).sync();
            serverChannel = future.channel();
            // 回填实际端口（port=0 时由系统分配）
            if (setting.getPort() == 0 && serverChannel.localAddress() instanceof InetSocketAddress addr) {
                setting.setPort(addr.getPort());
            }
            log.info("QUIC HTTP Server started on {}:{} (HTTP/3 over UDP)",
                    setting.getHost(), setting.getPort());
        } catch (Exception e) {
            throw new RuntimeException("QUIC HTTP Server 启动失败", e);
        }
    }

    @Override
    protected void doStop() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (group != null) {
            group.shutdownGracefully().syncUninterruptibly();
        }
        log.info("QUIC HTTP Server stopped");
    }

    /**
     * 创建 QUIC SSL 上下文（HTTP/3 强制 TLS 1.3）。
     */
    private QuicSslContext createQuicSslContext() throws Exception {
        ServerSetting.SslConfig ssl = setting.getSsl();
        if (ssl != null && ssl.isSelfSignedAuto()) {
            ssl.setEnabled(true);
            ssl.setSelfSigned(true);
        }
        // 1) KeyStore / PEM / 自签名统一走 SslUtils 生成 KeyManagerFactory
        KeyManagerFactory kmf = SslUtils.createKeyManagerFactory(ssl);
        return QuicSslContextBuilder.forServer(kmf, null)
                .earlyData(true)
                .build();
    }

    /**
     * QUIC 流 HTTP 处理器：HttpObject（Http3FrameToHttpObjectCodec 转换后）→ 统一请求链 → HTTP/3 帧响应。
     */
    private final class QuicHttpServerHandler extends ChannelInboundHandlerAdapter {
        private final ByteArrayOutputStream bodyBuf = new ByteArrayOutputStream(4096);
        private HttpRequest currentRequest;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof HttpRequest request) {
                currentRequest = request;
                bodyBuf.reset();
            } else if (msg instanceof LastHttpContent last) {
                // 请求体补全
                ByteBuf content = last.content();
                if (content.isReadable()) {
                    byte[] data = new byte[content.readableBytes()];
                    content.getBytes(content.readerIndex(), data);
                    bodyBuf.writeBytes(data);
                }
                if (currentRequest != null) {
                    handleQuicRequest(ctx, currentRequest);
                }
                currentRequest = null;
                bodyBuf.reset();
            } else if (msg instanceof io.netty.handler.codec.http.HttpContent content) {
                ByteBuf buf = content.content();
                if (buf.isReadable()) {
                    byte[] data = new byte[buf.readableBytes()];
                    buf.getBytes(buf.readerIndex(), data);
                    bodyBuf.writeBytes(data);
                }
            }
        }

        /**
         * 走 AbstractServer 统一请求链并写回 HTTP/3 帧。
         */
        private void handleQuicRequest(ChannelHandlerContext ctx, HttpRequest request) {
            QuicServerRequest serverRequest = new QuicServerRequest(request, bodyBuf.toByteArray());
            QuicServerResponse serverResponse = new QuicServerResponse(ctx);
            try {
                handleRequest(serverRequest, serverResponse);
            } catch (Exception e) {
                log.warn("QUIC 请求处理异常: {}", e.getMessage(), e);
                if (!serverResponse.isCommitted()) {
                    serverResponse.sendError(500, "Internal Server Error");
                }
            } finally {
                serverResponse.complete();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.debug("QUIC 连接异常: {}", cause.getMessage());
            ctx.close();
        }
    }

    /**
     * QUIC HTTP 请求实现。
     */
    private static final class QuicServerRequest implements ServerRequest {
        private final HttpRequest request;
        private final byte[] body;
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();

        QuicServerRequest(HttpRequest request, byte[] body) {
            this.request = request;
            this.body = body != null ? body : new byte[0];
        }

        @Override public String getUri() { return request.uri(); }
        @Override public String getPath() {
            String uri = request.uri();
            int idx = uri.indexOf('?');
            return idx > 0 ? uri.substring(0, idx) : uri;
        }
        @Override public HttpMethod getMethod() {
            try { return HttpMethod.valueOf(request.method().name()); }
            catch (Exception e) { return HttpMethod.GET; }
        }
        @Override public String getHeader(String name) {
            return request.headers().get(name);
        }
        @Override public HttpHeader getHeaders() {
            HttpHeader h = HttpHeader.create();
            request.headers().forEach(e -> h.add(e.getKey(), e.getValue()));
            return h;
        }
        @Override public Map<String, String> getParams() { return Collections.emptyMap(); }
        @Override public String getParam(String name) { return null; }
        @Override public String getContentType() {
            return request.headers().get(HttpHeaderNames.CONTENT_TYPE);
        }
        @Override public long getContentLength() { return body.length; }
        @Override public byte[] getBody() { return body; }
        @Override public String getBodyString() { return new String(body, StandardCharsets.UTF_8); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        @Override public String getRemoteAddress() { return "quic-client"; }
        @Override public int getRemotePort() { return 0; }
        @Override public Map<String, Object> getAttributes() { return attributes; }
        @Override public Object getAttribute(String name) { return attributes.get(name); }
        @Override public void setAttribute(String name, Object value) { attributes.put(name, value); }
    }

    /**
     * QUIC HTTP 响应实现：构造 HTTP/3 帧（HeadersFrame + DataFrame）写回。
     */
    private static final class QuicServerResponse implements ServerResponse {
        private final ChannelHandlerContext ctx;
        private int status = 200;
        private byte[] body;
        private String contentType;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private boolean committed;
        private boolean ended;
        private Object result;

        QuicServerResponse(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override public int getStatus() { return status; }
        @Override public ServerResponse setStatus(int statusCode) { if (!committed) status = statusCode; return this; }
        @Override public ServerResponse setHeader(String name, String value) { if (!committed) headers.put(name, value); return this; }
        @Override public String getHeader(String name) { return headers.get(name); }
        @Override public HttpHeader getHeaders() { HttpHeader h = HttpHeader.create(); headers.forEach(h::add); return h; }
        @Override public ServerResponse setContentType(String ct) { this.contentType = ct; return this; }
        @Override public String getContentType() { return contentType; }
        @Override public ServerResponse setBody(byte[] b) { if (!committed) this.body = b; return this; }
        @Override public ServerResponse setBody(String b) { if (!committed) this.body = b != null ? b.getBytes(StandardCharsets.UTF_8) : null; return this; }
        @Override public byte[] getBody() { return body; }
        @Override public ServerResponse setResult(Object result) { if (!committed) this.result = result; return this; }
        @Override public Object getResult() { return result; }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public ServerResponse sendRedirect(String location) { setStatus(302); headers.put("Location", location); end(); return this; }
        @Override public ServerResponse sendError(int code, String message) {
            if (ended) return this;
            setStatus(code);
            setBody(message);
            end();
            return this;
        }
        @Override public void flush() { }
        @Override public boolean isCommitted() { return committed; }
        @Override public boolean isEnded() { return ended; }
        @Override public void end() {
            if (ended) return;
            ended = true;
            writeResponse();
        }
        @Override public ServerResponse reset() {
            if (!committed) { status = 200; body = null; headers.clear(); contentType = null; ended = false; }
            return this;
        }
        @Override public void writeRaw(byte[] bytes) { if (!committed) setBody(bytes); }

        void complete() {
            if (!ended) {
                end();
            }
        }

        private void writeResponse() {
            if (committed) return;
            committed = true;
            byte[] data = body != null ? body : new byte[0];

            // 响应头帧（:status 伪头 + 自定义头）
            io.netty.handler.codec.http3.Http3Headers responseHeaders =
                    new io.netty.handler.codec.http3.DefaultHttp3Headers();
            responseHeaders.status(String.valueOf(status));
            if (contentType != null) {
                responseHeaders.set(HttpHeaderNames.CONTENT_TYPE, contentType);
            } else {
                responseHeaders.set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN);
            }
            responseHeaders.set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(data.length));
            for (Map.Entry<String, String> e : headers.entrySet()) {
                responseHeaders.set(e.getKey(), e.getValue());
            }
            ctx.write(new DefaultHttp3HeadersFrame(responseHeaders));
            if (data.length > 0) {
                ctx.write(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(data)));
            }
            ctx.flush();
        }
    }
}
