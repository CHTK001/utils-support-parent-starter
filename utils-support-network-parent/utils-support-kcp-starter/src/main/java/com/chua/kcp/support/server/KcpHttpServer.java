package com.chua.kcp.support.server;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.spi.annotations.Spi;
import io.jpower.kcp.netty.ChannelOptionHelper;
import io.jpower.kcp.netty.UkcpChannel;
import io.jpower.kcp.netty.UkcpChannelOption;
import io.jpower.kcp.netty.UkcpServerChannel;
import io.netty.bootstrap.UkcpServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KCP HTTP 服务器：在 KCP（UDP 可靠传输）通道上承载 HTTP/1.1。
 *
 * <p>基于 kcp-netty {@link UkcpServerBootstrap} 接收客户端，在 {@link UkcpChannel}
 * 上自实现 HTTP/1.1 请求解析（请求行 + 头部 + Content-Length 体），
 * 接入 {@link AbstractServer} 统一请求链（过滤器 / 路由 / 指标），响应通过 KCP 通道写回。</p>
 *
 * <p>特性：</p>
 * <ul>
 *   <li>KCP 快速模式（nodelay + 快速重传），UDP 无握手/backlog 限制，并发接纳能力强</li>
 *   <li>消息包累积：HTTP 请求可能跨多个 KCP 包，按 {@code \r\n\r\n} + Content-Length 完整解析</li>
 *   <li>复用 {@code registerMapping} / {@code onSubscribe} / 过滤器链</li>
 * </ul>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * ServerSetting setting = ServerSetting.defaults();
 * setting.setHost("0.0.0.0");
 * setting.setPort(19390);
 * KcpHttpServer server = new KcpHttpServer(setting);
 * server.registerMapping("/echo", (req, resp) -> resp.setResult("kcp-ok"));
 * server.start();
 * }</pre>
 *
 * @author CH
 * @since 2026/08/15
 */
@Slf4j
@Spi({"kcp-http", "kcp_http"})
public class KcpHttpServer extends AbstractServer {

    /**
     * KCP 会话编号（固定单会话，客户端须使用相同 conv）。
     */
    public static final int KCP_CONV = 0x48455054; // "HEPT" 会话标识

    /**
     * KCP MTU（字节）。
     */
    private static final int KCP_MTU = 512;

    /**
     * KCP 更新间隔（毫秒）。
     */
    private static final int KCP_INTERVAL = 20;

    /**
     * KCP 快速重传阈值。
     */
    private static final int KCP_FAST_RESEND = 2;

    private EventLoopGroup bossGroup;
    private Channel serverChannel;

    /**
     * 每通道的 HTTP 请求累积器。
     */
    private final Map<Channel, HttpAccumulator> accumulators = new ConcurrentHashMap<>();

    public KcpHttpServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    public String getProtocol() {
        return "kcp-http";
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.HTTP;
    }

    @Override
    protected void doStart() {
        setting.setProtocol("kcp-http");
        bossGroup = new NioEventLoopGroup(Math.max(1, setting.getBossThreads()));
        UkcpServerBootstrap bootstrap = new UkcpServerBootstrap();
        bootstrap.group(bossGroup)
                .channel(UkcpServerChannel.class)
                .childOption(UkcpChannelOption.UKCP_MTU, KCP_MTU)
                .childHandler(new ChannelInitializer<UkcpChannel>() {
                    @Override
                    protected void initChannel(UkcpChannel channel) {
                        ChannelPipeline pipeline = channel.pipeline();
                        pipeline.addLast(new KcpHttpServerHandler());
                    }
                });
        ChannelOptionHelper.nodelay(bootstrap, true, KCP_INTERVAL, KCP_FAST_RESEND, true);
        try {
            ChannelFuture future = bootstrap.bind(setting.getHost(), setting.getPort()).sync();
            setting.setPort(((InetSocketAddress) future.channel().localAddress()).getPort());
            serverChannel = future.channel();
            log.info("KCP HTTP Server started on {}:{} (conv={}, mtu={})",
                    setting.getHost(), setting.getPort(), KCP_CONV, KCP_MTU);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("KCP HTTP Server 启动失败", e);
        }
    }

    @Override
    protected void doStop() {
        for (Channel ch : accumulators.keySet()) {
            try {
                ch.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        accumulators.clear();
        if (serverChannel != null) {
            try {
                serverChannel.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
        }
        log.info("KCP HTTP Server stopped");
    }

    /**
     * KCP 通道 HTTP 处理器：累积字节 → 完整解析 → 走统一请求链。
     */
    private final class KcpHttpServerHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            // conv 需在连接建立时设置，initChannel 阶段设置会导致 UDP 包因 conv 不匹配被丢弃
            UkcpChannel kcpChannel = (UkcpChannel) ctx.channel();
            kcpChannel.conv(KCP_CONV);
            log.info("KCP HTTP 连接建立: {}", ctx.channel().remoteAddress());
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buffer = (ByteBuf) msg;
            try {
                log.debug("KCP HTTP 收到 {} 字节", buffer.readableBytes());
                HttpAccumulator acc = accumulators.computeIfAbsent(ctx.channel(),
                        k -> new HttpAccumulator());
                byte[] data = new byte[buffer.readableBytes()];
                buffer.getBytes(buffer.readerIndex(), data);
                acc.append(data);
                // 循环解析：一个 KCP 包可能包含多个请求（或一个请求跨多个包）
                while (true) {
                    ParsedRequest parsed = acc.tryParse();
                    if (parsed == null) {
                        break;
                    }
                    log.info("KCP HTTP 请求: {} {}", parsed.method(), parsed.uri());
                    KcpHttpServerRequest request = new KcpHttpServerRequest(parsed);
                    KcpHttpServerResponse response = new KcpHttpServerResponse(ctx);
                    try {
                        handleRequest(request, response);
                    } catch (Exception e) {
                        log.warn("KCP HTTP 请求处理异常: {}", e.getMessage(), e);
                        if (!response.isCommitted()) {
                            response.sendError(500, "Internal Server Error");
                        }
                    } finally {
                        response.complete();
                    }
                }
            } finally {
                ReferenceCountUtil.release(buffer);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            accumulators.remove(ctx.channel());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.debug("KCP HTTP 连接异常: {}", cause.getMessage());
            accumulators.remove(ctx.channel());
            ctx.close();
        }
    }

    /**
     * HTTP 请求累积器：跨包累积字节并解析请求。
     */
    private static final class HttpAccumulator {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);

        void append(byte[] data) {
            buf.writeBytes(data);
        }

        /**
         * 尝试从累积字节中解析一个完整 HTTP 请求；不完整返回 null。
         */
        ParsedRequest tryParse() {
            byte[] bytes = buf.toByteArray();
            // 1) 找请求头结束 \r\n\r\n
            int headerEnd = indexOf(bytes, 0, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            if (headerEnd < 0) {
                return null;
            }
            String head = new String(bytes, 0, headerEnd, StandardCharsets.ISO_8859_1);
            String[] lines = head.split("\r\n");
            if (lines.length == 0) {
                // 丢弃空头
                buf.reset();
                return null;
            }
            // 2) 解析请求行: METHOD SP URI SP HTTP/x.y
            String[] parts = lines[0].split(" ");
            if (parts.length < 2) {
                buf.reset();
                return null;
            }
            String method = parts[0];
            String uri = parts[1];
            String version = parts.length > 2 ? parts[2] : "HTTP/1.1";

            // 3) 解析头部
            Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            int contentLength = 0;
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    String name = lines[i].substring(0, colon).trim();
                    String value = lines[i].substring(colon + 1).trim();
                    headers.put(name, value);
                    if ("content-length".equalsIgnoreCase(name)) {
                        try {
                            contentLength = Integer.parseInt(value);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
            // 4) 请求体：Content-Length 决定
            int headerBlockEnd = headerEnd + 4; // 含 \r\n\r\n
            int total = headerBlockEnd + contentLength;
            if (bytes.length < total) {
                return null; // body 未到齐
            }
            byte[] body = new byte[contentLength];
            System.arraycopy(bytes, headerBlockEnd, body, 0, contentLength);
            // 5) 消费已解析字节
            int remaining = bytes.length - total;
            byte[] leftover = new byte[remaining];
            System.arraycopy(bytes, total, leftover, 0, remaining);
            buf.reset();
            buf.writeBytes(leftover);
            return new ParsedRequest(method, uri, version, headers, body);
        }

        private static int indexOf(byte[] haystack, int from, byte[] needle) {
            outer:
            for (int i = from; i <= haystack.length - needle.length; i++) {
                for (int j = 0; j < needle.length; j++) {
                    if (haystack[i + j] != needle[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }
    }

    /**
     * 解析完成的 HTTP 请求。
     */
    record ParsedRequest(String method, String uri, String version,
                         Map<String, String> headers, byte[] body) {
    }

    /**
     * KCP HTTP 请求实现。
     */
    private static final class KcpHttpServerRequest implements ServerRequest {
        private final ParsedRequest parsed;
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();

        KcpHttpServerRequest(ParsedRequest parsed) {
            this.parsed = parsed;
        }

        @Override public String getUri() { return parsed.uri(); }
        @Override public String getPath() {
            String uri = parsed.uri();
            int idx = uri.indexOf('?');
            return idx > 0 ? uri.substring(0, idx) : uri;
        }
        @Override public HttpMethod getMethod() {
            try { return HttpMethod.valueOf(parsed.method().toUpperCase()); }
            catch (Exception e) { return HttpMethod.GET; }
        }
        @Override public String getHeader(String name) { return parsed.headers().get(name); }
        @Override public HttpHeader getHeaders() {
            HttpHeader h = HttpHeader.create();
            parsed.headers().forEach(h::add);
            return h;
        }
        @Override public Map<String, String> getParams() { return Collections.emptyMap(); }
        @Override public String getParam(String name) { return null; }
        @Override public String getContentType() { return parsed.headers().get("Content-Type"); }
        @Override public long getContentLength() { return parsed.body().length; }
        @Override public byte[] getBody() { return parsed.body(); }
        @Override public String getBodyString() {
            return new String(parsed.body(), StandardCharsets.UTF_8);
        }
        @Override public InputStream getInputStream() {
            return new ByteArrayInputStream(parsed.body());
        }
        @Override public String getRemoteAddress() { return "kcp-client"; }
        @Override public int getRemotePort() { return 0; }
        @Override public Map<String, Object> getAttributes() { return attributes; }
        @Override public Object getAttribute(String name) { return attributes.get(name); }
        @Override public void setAttribute(String name, Object value) { attributes.put(name, value); }
    }

    /**
     * KCP HTTP 响应实现：构造 HTTP 报文通过 KCP 通道写回。
     */
    private static final class KcpHttpServerResponse implements ServerResponse {
        private final ChannelHandlerContext ctx;
        private int status = 200;
        private byte[] body;
        private String contentType;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private boolean committed;
        private boolean ended;
        private Object result;

        KcpHttpServerResponse(ChannelHandlerContext ctx) {
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
            StringBuilder sb = new StringBuilder(256);
            sb.append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n");
            if (contentType != null) {
                sb.append("Content-Type: ").append(contentType).append("\r\n");
            } else {
                sb.append("Content-Type: text/plain; charset=utf-8\r\n");
            }
            sb.append("Content-Length: ").append(data.length).append("\r\n");
            for (Map.Entry<String, String> e : headers.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
            sb.append("\r\n");
            byte[] head = sb.toString().getBytes(StandardCharsets.US_ASCII);
            ByteBuf response = Unpooled.buffer(head.length + data.length);
            response.writeBytes(head);
            response.writeBytes(data);
            ctx.writeAndFlush(response);
        }

        private static String reasonPhrase(int code) {
            return switch (code) {
                case 200 -> "OK";
                case 201 -> "Created";
                case 204 -> "No Content";
                case 301 -> "Moved Permanently";
                case 302 -> "Found";
                case 400 -> "Bad Request";
                case 404 -> "Not Found";
                case 405 -> "Method Not Allowed";
                case 500 -> "Internal Server Error";
                case 503 -> "Service Unavailable";
                default -> "Unknown";
            };
        }
    }
}
