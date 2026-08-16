package com.chua.kcp.support.client;

import com.chua.kcp.support.server.KcpHttpServer;
import io.jpower.kcp.netty.ChannelOptionHelper;
import io.jpower.kcp.netty.UkcpChannel;
import io.jpower.kcp.netty.UkcpChannelOption;
import io.jpower.kcp.netty.UkcpClientChannel;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * KCP HTTP 客户端：通过 kcp-netty（UDP 可靠传输）与 {@link KcpHttpServer} 通信。
 *
 * <p>应用层为标准 HTTP/1.1 请求/响应，传输层走 KCP 通道（conv 与服务端匹配）。
 * 支持 GET / POST / PUT / DELETE 等任意方法，响应按 Content-Length 跨包累积解析。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * try (KcpHttpClient client = new KcpHttpClient("127.0.0.1", 19390)) {
 *     client.connect();
 *     KcpHttpClient.HttpResponse resp = client.request("GET", "/echo", null, null);
 *     System.out.println(resp.getStatus());   // 200
 *     System.out.println(resp.getBodyString()); // kcp-ok
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class KcpHttpClient implements AutoCloseable {

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

    /**
     * 服务端主机。
     */
    private final String host;

    /**
     * 服务端端口。
     */
    private final int port;

    /**
     * 请求超时（毫秒）。
     */
    private final long timeoutMs;

    /**
     * KCP 事件循环。
     */
    private EventLoopGroup group;

    /**
     * KCP 通道。
     */
    private UkcpChannel channel;

    /**
     * 是否已连接。
     */
    private volatile boolean connected;

    /**
     * 收到完整响应的等待器。
     */
    private volatile CountDownLatch responseLatch;

    /**
     * 最近一次响应体累积。
     */
    private final AtomicReference<HttpResponse> lastResponse = new AtomicReference<>();

    /**
     * 构造 KCP HTTP 客户端。
     *
     * @param host 服务端主机
     * @param port 服务端端口
     */
    public KcpHttpClient(String host, int port) {
        this(host, port, 10000L);
    }

    /**
     * 构造 KCP HTTP 客户端。
     *
     * @param host      服务端主机
     * @param port      服务端端口
     * @param timeoutMs 请求超时（毫秒）
     */
    public KcpHttpClient(String host, int port, long timeoutMs) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
    }

    /**
     * 连接到 KCP HTTP 服务端。
     */
    public void connect() throws Exception {
        if (connected) {
            return;
        }
        group = new NioEventLoopGroup(1);
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(UkcpClientChannel.class)
                .option(UkcpChannelOption.UKCP_MTU, KCP_MTU)
                .handler(new ChannelInitializer<UkcpChannel>() {
                    @Override
                    protected void initChannel(UkcpChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new KcpHttpClientHandler());
                    }
                });
        ChannelOptionHelper.nodelay(bootstrap, true, KCP_INTERVAL, KCP_FAST_RESEND, true);
        try {
            ChannelFuture future = bootstrap.connect(host, port).sync();
            channel = (UkcpChannel) future.channel();
            channel.conv(KcpHttpServer.KCP_CONV);
            connected = true;
            log.info("KCP HTTP 客户端已连接: {}:{}", host, port);
        } catch (Exception e) {
            connected = false;
            shutdownGroup();
            throw new RuntimeException("KCP HTTP 客户端连接失败: " + host + ":" + port, e);
        }
    }

    /**
     * 发送 HTTP 请求并等待响应。
     *
     * @param method  HTTP 方法（GET / POST / PUT / DELETE ...）
     * @param path    请求路径（如 /echo）
     * @param headers 请求头，可为 null
     * @param body    请求体，可为 null
     * @return HTTP 响应
     */
    public HttpResponse request(String method, String path, Map<String, String> headers, byte[] body) throws Exception {
        ensureConnected();
        CountDownLatch latch = new CountDownLatch(1);
        responseLatch = latch;
        lastResponse.set(null);

        byte[] reqBody = body != null ? body : new byte[0];
        StringBuilder sb = new StringBuilder(256);
        sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append("\r\n");
        sb.append("Content-Length: ").append(reqBody.length).append("\r\n");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        sb.append("\r\n");
        byte[] head = sb.toString().getBytes(StandardCharsets.US_ASCII);
        ByteBuf request = Unpooled.buffer(head.length + reqBody.length);
        request.writeBytes(head);
        request.writeBytes(reqBody);
        channel.writeAndFlush(request);

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new java.util.concurrent.TimeoutException("KCP HTTP 请求超时: " + method + " " + path);
        }
        HttpResponse resp = lastResponse.get();
        if (resp == null) {
            throw new RuntimeException("KCP HTTP 响应为空");
        }
        return resp;
    }

    /**
     * GET 快捷方法。
     */
    public HttpResponse get(String path) throws Exception {
        return request("GET", path, null, null);
    }

    /**
     * POST 快捷方法。
     */
    public HttpResponse post(String path, byte[] body) throws Exception {
        return request("POST", path, null, body);
    }

    /**
     * 判断是否已连接。
     *
     * @return true 表示已连接
     */
    public boolean isConnected() {
        return connected;
    }

    private void ensureConnected() {
        if (!connected || channel == null) {
            throw new IllegalStateException("KCP HTTP 客户端未连接，请先调用 connect()");
        }
    }

    private void shutdownGroup() {
        if (group != null) {
            group.shutdownGracefully();
            group = null;
        }
    }

    @Override
    public void close() {
        if (channel != null) {
            channel.close();
            channel = null;
        }
        shutdownGroup();
        connected = false;
    }

    /**
     * KCP HTTP 客户端通道处理器：累积字节并解析完整 HTTP 响应。
     */
    private final class KcpHttpClientHandler extends ChannelInboundHandlerAdapter {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buffer = (ByteBuf) msg;
            try {
                byte[] data = new byte[buffer.readableBytes()];
                buffer.getBytes(buffer.readerIndex(), data);
                buf.writeBytes(data);
                HttpResponse resp = tryParseResponse();
                if (resp != null) {
                    lastResponse.set(resp);
                    CountDownLatch latch = responseLatch;
                    if (latch != null) {
                        latch.countDown();
                    }
                    buf.reset();
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.debug("KCP HTTP 客户端异常: {}", cause.getMessage());
            CountDownLatch latch = responseLatch;
            if (latch != null) {
                latch.countDown();
            }
            ctx.close();
        }

        /**
         * 尝试解析完整 HTTP 响应；不完整返回 null。
         */
        private HttpResponse tryParseResponse() {
            byte[] bytes = buf.toByteArray();
            int headerEnd = indexOf(bytes, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            if (headerEnd < 0) {
                return null;
            }
            String head = new String(bytes, 0, headerEnd, StandardCharsets.ISO_8859_1);
            String[] lines = head.split("\r\n");
            if (lines.length == 0) {
                return null;
            }
            // 状态行: HTTP/1.1 200 OK
            String[] statusParts = lines[0].split(" ", 3);
            int status = 0;
            if (statusParts.length >= 2) {
                try {
                    status = Integer.parseInt(statusParts[1]);
                } catch (NumberFormatException ignored) {
                }
            }
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
            int headerBlockEnd = headerEnd + 4;
            int total = headerBlockEnd + contentLength;
            if (bytes.length < total) {
                // body 未到齐（跨包）
                return null;
            }
            byte[] body = new byte[contentLength];
            System.arraycopy(bytes, headerBlockEnd, body, 0, contentLength);
            int remaining = bytes.length - total;
            byte[] leftover = new byte[remaining];
            System.arraycopy(bytes, total, leftover, 0, remaining);
            buf.reset();
            buf.writeBytes(leftover);
            return new HttpResponse(status, headers, body);
        }

        private int indexOf(byte[] haystack, byte[] needle) {
            outer:
            for (int i = 0; i <= haystack.length - needle.length; i++) {
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
     * HTTP 响应。
     */
    public static class HttpResponse {
        private final int status;
        private final Map<String, String> headers;
        private final byte[] body;

        HttpResponse(int status, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.headers = headers != null ? new LinkedHashMap<>(headers) : new LinkedHashMap<>();
            this.body = body != null ? body : new byte[0];
        }

        /**
         * @return 状态码（200 = OK）
         */
        public int getStatus() {
            return status;
        }

        /**
         * @return 响应头
         */
        public Map<String, String> getHeaders() {
            return headers;
        }

        /**
         * @param name 响应头名称
         * @return 响应头值
         */
        public String getHeader(String name) {
            return headers.get(name);
        }

        /**
         * @return 响应体字节
         */
        public byte[] getBody() {
            return body;
        }

        /**
         * @return 响应体字符串（UTF-8）
         */
        public String getBodyString() {
            return new String(body, StandardCharsets.UTF_8);
        }

        @Override
        public String toString() {
            return "HttpResponse{status=" + status + ", body=" + getBodyString() + '}';
        }
    }
}
