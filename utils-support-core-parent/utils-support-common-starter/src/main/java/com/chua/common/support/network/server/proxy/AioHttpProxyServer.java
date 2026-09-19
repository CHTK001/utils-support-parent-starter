package com.chua.common.support.network.server.proxy;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AIO 正向 HTTP 代理 —— 纯非阻塞响应式实现(Proactor/IOCP)。
 * 支持 CONNECT 隧道、绝对 URI 转发、Content-Length 体、Keep-Alive 循环。
 * 限制:v1 不支持 chunked 体。
 * @author CH
 * @since 2026/08/24
 */
@Slf4j
@Spi({"aio-http-proxy"})
public class AioHttpProxyServer extends AbstractServer {

    /** 监听通道 */
    private AsynchronousServerSocketChannel serverChannel;
    /** IOCP 线程组 */
    private AsynchronousChannelGroup group;
    /** 活跃连接数 */
    private final AtomicInteger activeConnections = new AtomicInteger();

    /**
     * 后端空闲连接池(target -> 空闲通道列表,复用避免每次 connect)
     */
    private final java.util.concurrent.ConcurrentHashMap<String,
            ArrayDeque<AsynchronousSocketChannel>> BACKEND_POOL =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 从池中取或新建后端连接 */
    private void acquireBackend(String hostKey, InetSocketAddress addr,
                                java.util.function.Consumer<AsynchronousSocketChannel> onDone,
                                java.util.function.Consumer<Throwable> onError) {
        var q = BACKEND_POOL.get(hostKey);
        if (q != null) {
            AsynchronousSocketChannel ch;
            while ((ch = q.poll()) != null) {
                if (ch.isOpen()) {
                    onDone.accept(ch);
                    return;
                }
                closeQuietly(ch);
            }
        }
        connectAsync(addr, onDone, onError);
    }

    /**
     * 用完归还到池(仅健康时)
     * @param hostKey 主机键，不允许为 null
     * @param ch 方法入参 ch
     */
    private void returnBackend(String hostKey, AsynchronousSocketChannel ch) {
        if (ch != null && ch.isOpen()) {
            BACKEND_POOL.computeIfAbsent(hostKey, k -> new ArrayDeque<>())
                    .offer(ch);
        } else {
            closeQuietly(ch);
        }
    }

    /**
     * 构造方法，创建 AioHttpProxy服务端 实例。
     *
     * @param setting 方法入参 setting
     */
    public AioHttpProxyServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    protected void doStart() {
        try {
            int threads = setting.getEventLoops() > 0
                    ? setting.getEventLoops() : Runtime.getRuntime().availableProcessors();
            group = AsynchronousChannelGroup.withFixedThreadPool(threads, r -> {
                Thread t = new Thread(r, "aio-httpproxy-iocp");
                t.setDaemon(true);
                return t;
            });
            serverChannel = AsynchronousServerSocketChannel.open(group);
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, setting.isSoReuseAddr());
            serverChannel.bind(new InetSocketAddress(setting.getHost(), setting.getPort()),
                    Math.max(setting.getBacklog(), 65536));
            setting.setPort(((InetSocketAddress) serverChannel.getLocalAddress()).getPort());
            issueAccept();
            log.info("AIO HttpProxy started on {}:{} (iocpThreads={}, reactive=true)",
                    setting.getHost(), setting.getPort(), threads);
        } catch (Exception e) {
            throw new RuntimeException("AIO HttpProxy 启动失败", e);
        }
    }

    /**
     * issueAccept。
     */
    private void issueAccept() {
        // 不检查 running:start() 模板在 doStart 返回后才置位,否则首挂 accept 永不发生
        if (serverChannel == null || !serverChannel.isOpen()) {
            return;
        }
        serverChannel.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
            @Override
            public void completed(AsynchronousSocketChannel channel, Void attachment) {
                issueAccept();
                handleClient(channel);
            }

            @Override
            public void failed(Throwable exc, Void attachment) {
                if (running && serverChannel != null && serverChannel.isOpen()) {
                    issueAccept();
                }
            }
        });
    }

    /**
     * 处理客户端。
     *
     * @param channel 方法入参 channel
     */
    private void handleClient(AsynchronousSocketChannel channel) {
        activeConnections.incrementAndGet();
        try {
            channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
        } catch (Exception ignored) {
        }
        ClientCtx ctx = new ClientCtx(channel);
        issueClientRead(ctx);
    }

    /**
     * issue客户端读取。
     *
     * @param ctx 上下文，不允许为 null
     */
    private void issueClientRead(ClientCtx ctx) {
        if (!running || ctx.closed) {
            finishClient(ctx);
            return;
        }
        ctx.clientBuf.clear();
        ctx.client.read(ctx.clientBuf, ctx.clientBuf,
                new CompletionHandler<Integer, ByteBuffer>() {
                    @Override
                    public void completed(Integer n, ByteBuffer buf) {
                        if (n == null || n < 0 || !running) {
                            finishClient(ctx);
                            return;
                        }
                        buf.flip();
                        HeadParser.Result r = ctx.parser.feed(buf);
                        if (r == HeadParser.Result.NEED_MORE) {
                            buf.compact();
                            issueClientRead(ctx);
                            return;
                        }
                        byte[] leftover = new byte[buf.remaining()];
                        buf.get(leftover);
                        route(ctx, ctx.parser.head(), leftover);
                    }

                    @Override
                    public void failed(Throwable exc, ByteBuffer attachment) {
                        finishClient(ctx);
                    }
                });
    }

    /**
     * route。
     *
     * @param ctx 上下文，不允许为 null
     * @param head 头部，不允许为 null
     * @param leftover 方法入参 leftover
     */
    private void route(ClientCtx ctx, RequestHead head, byte[] leftover) {
        if ("CONNECT".equalsIgnoreCase(head.method)) {
            tunnel(ctx, head);
        } else {
            forward(ctx, head, leftover);
        }
    }

    /**
     * 无限双向泵(隧道用):任一方向终止即关两端并触发一次 onClose。
     */
    private final class Pump implements CompletionHandler<Integer, Void> {

        /** 源通道 */
        private final AsynchronousSocketChannel src;
        /** 目标通道 */
        private final AsynchronousSocketChannel dst;
        /** 中转缓冲 */
        private final ByteBuffer buf = ByteBuffer.allocateDirect(16384);
        /** 共享去重标志 */
        private final java.util.concurrent.atomic.AtomicBoolean fired;
        /** 关闭回调 */
        private final Runnable onClose;

        Pump(AsynchronousSocketChannel src, AsynchronousSocketChannel dst,
             java.util.concurrent.atomic.AtomicBoolean fired, Runnable onClose) {
            this.src = src;
            this.dst = dst;
            this.fired = fired;
            this.onClose = onClose;
        }

        /**
         * 启动读取。
         */
        void start() {
            if (!running || !src.isOpen()) {
                finish();
                return;
            }
            buf.clear();
            src.read(buf, null, this);
        }

        @Override
        public void completed(Integer n, Void attachment) {
            if (n == null || n < 0) {
                finish();
                return;
            }
            buf.flip();
            dst.write(buf, null, new CompletionHandler<Integer, Void>() {
                @Override
                public void completed(Integer w, Void attachment) {
                    if (buf.hasRemaining()) {
                        dst.write(buf, null, this);
                        return;
                    }
                    Pump.this.start();
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    finish();
                }
            });
        }

        @Override
        public void failed(Throwable exc, Void attachment) {
            finish();
        }

        private void finish() {
            if (fired.compareAndSet(false, true)) {
                closeQuietly(src);
                closeQuietly(dst);
                activeConnections.decrementAndGet();
                onClose.run();
            }
        }
    }
    // ==================== CONNECT 隧道 ====================

    /**
     * CONNECT 隧道:异步连目标 → 回 200 → 双向异步泵。
     * @param ctx 上下文，不允许为 null
     * @param head 头部，不允许为 null
     */
    private void tunnel(ClientCtx ctx, RequestHead head) {
        connectAsync(new InetSocketAddress(head.host, head.port),
                backend -> {
                    writeAll(ctx.client, ByteBuffer.wrap(
                            "HTTP/1.1 200 Connection Established\r\n\r\n"
                                    .getBytes(StandardCharsets.US_ASCII)),
                            () -> {
                                java.util.concurrent.atomic.AtomicBoolean fired =
                                        new java.util.concurrent.atomic.AtomicBoolean(false);
                                Runnable onClose = () -> finishClient(ctx);
                                new Pump(ctx.client, backend, fired, onClose).start();
                                new Pump(backend, ctx.client, fired, onClose).start();
                            },
                            err -> finishClient(ctx));
                },
                err -> respondErrorAndClose(ctx, 502, "Bad Gateway"));
    }

    // ==================== 普通请求转发 ====================

    /**
     * 转发请求到后端。
     * @param ctx 上下文，不允许为 null
     * @param head 头部，不允许为 null
     * @param leftover 方法入参 leftover
     */
    private void forward(ClientCtx ctx, RequestHead head, byte[] leftover) {
        connectAsync(new InetSocketAddress(head.host, head.port),
                backend -> sendRequest(ctx, backend, head, leftover),
                err -> {
                    log.debug("连后端失败: {}", err.getMessage());
                    respondErrorAndClose(ctx, 502, "Bad Gateway");
                });
    }

    /**
     * 发送改写后的请求头与体前缀。
     */
    private void sendRequest(ClientCtx ctx, AsynchronousSocketChannel backend,

                             RequestHead head, byte[] leftover) {
        try {
        ctx.backend = backend;
        ctx.lastKeepAlive = head.keepAlive;
        ByteArrayOutputStream req = new ByteArrayOutputStream(256);
        req.write((head.method + " " + head.extractOriginForm()
                + " HTTP/1.1\r\n").getBytes(StandardCharsets.US_ASCII));
        boolean hasHost = false;
        for (String line : head.headers) {
            String lower = line.toLowerCase();
            if (lower.startsWith("host:")) {
                hasHost = true;
                req.write(("Host: " + head.host + ":" + head.port
                        + "\r\n").getBytes(StandardCharsets.US_ASCII));
            } else if (lower.startsWith("proxy-")
                    || lower.startsWith("connection:")
                    || lower.startsWith("content-length:")) {
                // 剥离逐跳头与长度头(长度单独补)
            } else {
                req.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
            }
        }
        if (!hasHost) {
            req.write(("Host: " + head.host + ":" + head.port
                    + "\r\n").getBytes(StandardCharsets.US_ASCII));
        }
        long bodyLen = Math.max(0, head.contentLength);
        req.write(("Connection: " + (head.keepAlive ? "keep-alive" : "close")
                + "\r\n").getBytes(StandardCharsets.US_ASCII));
        if (bodyLen > 0) {
            req.write(("Content-Length: " + bodyLen
                    + "\r\n").getBytes(StandardCharsets.US_ASCII));
        }
        req.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        if (leftover != null && leftover.length > 0) {
            int take = (int) Math.min(leftover.length, bodyLen);
            req.write(leftover, 0, take);
            ctx.reqBodySent = take;
        }
        ctx.reqBodyLeft = bodyLen - ctx.reqBodySent;
        writeAll(backend, ByteBuffer.wrap(req.toByteArray()),
                () -> afterRequestHeadSent(ctx, backend),
                err -> {
                    finishClient(ctx);
                    closeQuietly(backend);
                });
        } catch (java.io.IOException e) {
            log.debug("AIO HttpProxy 发送请求失败: {}", e.getMessage());
            finishClient(ctx);
            closeQuietly(backend);
        }
    }

    /**
     * 请求头已发:补齐剩余请求体后进入响应阶段。
     * @param ctx 上下文，不允许为 null
     * @param backend 方法入参 backend
     */
    private void afterRequestHeadSent(ClientCtx ctx, AsynchronousSocketChannel backend) {
        if (ctx.reqBodyLeft > 0) {
            new LimitedRelay(ctx.client, backend, ctx.reqBodyLeft,
                    () -> readBackendResponse(ctx, backend),
                    () -> readBackendResponse(ctx, backend),
                    err -> finishClient(ctx)).start();
            return;
        }
        readBackendResponse(ctx, backend);
    }

    /**
     * 异步读后端响应头并中继响应体。
     * @param ctx 上下文，不允许为 null
     * @param backend 方法入参 backend
     */
    private void readBackendResponse(ClientCtx ctx, AsynchronousSocketChannel backend) {
        ResponseParser parser = new ResponseParser();
        ByteBuffer bbuf = ByteBuffer.allocateDirect(16384);
        pumpParse(backend, bbuf, parser,
                () -> onResponseHead(ctx, backend, parser.meta(), bbuf),
                () -> finishClient(ctx),
                err -> finishClient(ctx));
    }

    /**
     * 循环解析直至响应头就绪。
     */
    private void pumpParse(AsynchronousSocketChannel ch, ByteBuffer buf,
                           ResponseParser parser, Runnable onDone,
                           Runnable onEof, java.util.function.Consumer<Throwable> onError) {
        buf.clear();
        ch.read(buf, buf, new CompletionHandler<Integer, ByteBuffer>() {
            @Override
            public void completed(Integer n, ByteBuffer b) {
                if (n == null || n < 0) {
                    onEof.run();
                    return;
                }
                b.flip();
                HeadParser.Result r = parser.feed(b);
                if (r == HeadParser.Result.NEED_MORE) {
                    b.compact();
                    pumpParse(ch, b, parser, onDone, onEof, onError);
                    return;
                }
                onDone.run();
            }

            @Override
            public void failed(Throwable exc, ByteBuffer attachment) {
                onError.accept(exc);
            }
        });
    }

    /**
     * 响应头就绪:回传头与体前缀,限量中继剩余体。
     */
    private void onResponseHead(ClientCtx ctx, AsynchronousSocketChannel backend,
                                ResponseMeta meta, ByteBuffer leftoverBuf) {
        byte[] prefix = new byte[leftoverBuf.remaining()];
        leftoverBuf.get(prefix);
        long cl = Math.max(0, meta.contentLength);
        int take = (int) Math.min(prefix.length, cl);
        byte[] bodyPart = new byte[take];
        System.arraycopy(prefix, 0, bodyPart, 0, take);
        byte[] first = concat(meta.rawHeader, bodyPart);
        long remaining = cl - take;

        writeAll(ctx.client, ByteBuffer.wrap(first),
                () -> {
                    if (remaining <= 0) {
                        afterResponse(ctx, backend, meta);
                        return;
                    }
                    new LimitedRelay(backend, ctx.client, remaining,
                            () -> afterResponse(ctx, backend, meta),
                            () -> finishClient(ctx),
                            err -> finishClient(ctx)).start();
                },
                err -> finishClient(ctx));
    }

    /**
     * 响应完成:keep-alive 则重置解析器继续下一请求。
     */
    private void afterResponse(ClientCtx ctx, AsynchronousSocketChannel backend,
                               ResponseMeta meta) {
        closeQuietly(backend);
        if (meta.close || !ctx.lastKeepAlive || !running) {
            finishClient(ctx);
            return;
        }
        ctx.parser.reset();
        issueClientRead(ctx);
    }

    /**
     * concat。
     *
     * @param a 方法入参 a
     * @param b 方法入参 b
     * @return 结果值
     */
    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
    /**
     * 精确中继 N 字节(src→dst,纯异步回调链)。
     */
    private final class LimitedRelay implements CompletionHandler<Integer, Void> {

        /** 源通道 */
        private final AsynchronousSocketChannel src;
        /** 目标通道 */
        private final AsynchronousSocketChannel dst;
        /** 中转缓冲 */
        private final ByteBuffer buf = ByteBuffer.allocateDirect(16384);
        /** 完成回调 */
        private final Runnable onDone;
        /** 对端提前关闭回调 */
        private final Runnable onEof;
        /** 异常回调 */
        private final java.util.function.Consumer<Throwable> onError;
        /** 剩余字节数 */
        private long left;

        /**
         * 创建限量中继。
         *
         * @param src    源
         * @param dst    目标
         * @param n      总字节数
         * @param onDone 完成
         * @param onEof  提前 EOF
         * @param onError 异常
         */
        LimitedRelay(AsynchronousSocketChannel src, AsynchronousSocketChannel dst,
                     long n, Runnable onDone, Runnable onEof,
                     java.util.function.Consumer<Throwable> onError) {
            this.src = src;
            this.dst = dst;
            this.left = n;
            this.onDone = onDone;
            this.onEof = onEof;
            this.onError = onError;
        }

        /**
         * 启动中继。
         */
        void start() {
            if (left <= 0 || !running) {
                onDone.run();
                return;
            }
            buf.clear();
            src.read(buf, null, this);
        }

        @Override
        public void completed(Integer nRead, Void attachment) {
            if (nRead == null || nRead < 0) {
                onEof.run();
                return;
            }
            buf.flip();
            dst.write(buf, null, new CompletionHandler<Integer, Void>() {
                @Override
                public void completed(Integer w, Void attachment) {
                    if (buf.hasRemaining()) {
                        dst.write(buf, null, this);
                        return;
                    }
                    left -= buf.limit();
                    if (left <= 0) {
                        onDone.run();
                    } else {
                        LimitedRelay.this.start();
                    }
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    onError.accept(exc);
                }
            });
        }

        @Override
        public void failed(Throwable exc, Void attachment) {
            onError.accept(exc);
        }
    }

    // ==================== 客户端上下文与生命周期 ====================

    /**
     * 单客户端连接上下文。
     */
    private static final class ClientCtx {

        /** 客户端通道 */
        final AsynchronousSocketChannel client;
        /** 客户端读缓冲 */
        final ByteBuffer clientBuf = ByteBuffer.allocateDirect(32768);
        /** 增量头解析器 */
        final HeadParser parser = new HeadParser();
        /** 后端通道 */
        volatile AsynchronousSocketChannel backend;
        /** 已发出的请求体字节数 */
        long reqBodySent;
        /** 尚待转发的请求体字节数 */
        long reqBodyLeft;
        /** 最近一次请求的 keep-alive 意图 */
        boolean lastKeepAlive = true;
        /** 关闭标志 */
        boolean closed;

        ClientCtx(AsynchronousSocketChannel client) {
            this.client = client;
        }
    }

    /**
     * 结束客户端连接(幂等):关通道并扣减计数。
     * @param ctx 上下文，不允许为 null
     */
    private void finishClient(ClientCtx ctx) {
        if (ctx.closed) {
            return;
        }
        ctx.closed = true;
        closeQuietly(ctx.client);
        closeQuietly(ctx.backend);
        activeConnections.decrementAndGet();
    }

    /**
     * 回错误并关闭。
     * @param ctx 上下文，不允许为 null
     * @param code 编码，不允许为 null
     * @param reason 方法入参 reason
     */
    private void respondErrorAndClose(ClientCtx ctx, int code, String reason) {
        byte[] resp = ("HTTP/1.1 " + code + " " + reason
                + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        writeAll(ctx.client, ByteBuffer.wrap(resp),
                () -> finishClient(ctx),
                e -> finishClient(ctx));
    }

    /**
     * 异步建立后端连接。
     */
    private void connectAsync(InetSocketAddress addr,
                              java.util.function.Consumer<AsynchronousSocketChannel> onDone,
                              java.util.function.Consumer<Throwable> onError) {
        AsynchronousSocketChannel ch;
        try {
            ch = AsynchronousSocketChannel.open(group);
            ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
        } catch (Exception e) {
            onError.accept(e);
            return;
        }
        ch.connect(addr, ch, new CompletionHandler<Void, AsynchronousSocketChannel>() {
            @Override
            public void completed(Void result, AsynchronousSocketChannel channel) {
                onDone.accept(channel);
            }

            @Override
            public void failed(Throwable exc, AsynchronousSocketChannel channel) {
                closeQuietly(channel);
                onError.accept(exc);
            }
        });
    }

    /**
     * 异步写尽整个缓冲。
     */
    private void writeAll(AsynchronousSocketChannel ch, ByteBuffer src,
                          Runnable onDone, java.util.function.Consumer<Throwable> onError) {
        ch.write(src, null, new CompletionHandler<Integer, Void>() {
            @Override
            public void completed(Integer n, Void attachment) {
                if (src.hasRemaining()) {
                    ch.write(src, null, this);
                    return;
                }
                onDone.run();
            }

            @Override
            public void failed(Throwable exc, Void attachment) {
                onError.accept(exc);
            }
        });
    }

    /**
     * 静默关闭通道。
     * @param ch 方法入参 ch
     */
    private static void closeQuietly(AsynchronousSocketChannel ch) {
        if (ch != null) {
            try {
                ch.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ==================== 增量头解析器 ====================

    /**
     * HTTP 头增量解析器(peek 式消费,头后剩余字节保留在缓冲中作为体前缀)。
     */
    static final class HeadParser {

        /** 解析结果 */
        enum Result {
            /** 需要更多数据 */
            NEED_MORE,
            /** 头就绪(head 可用) */
            DONE,
            /** 非法报文 */
            ERROR
        }

        private final ByteArrayOutputStream acc = new ByteArrayOutputStream(512);
        private RequestHead head;

        /**
         * 喂入一段字节。
         *
         * @param in 输入缓冲
         * @return 结果;DONE 后缓冲剩余即体前缀
         */
        Result feed(ByteBuffer in) {
            while (in.hasRemaining()) {
                int b = in.get() & 0xFF;
                acc.write(b);
                if (b == '\n' && endsWithHeaderEnd()) {
                    head = parse(new String(acc.toByteArray(),
                            StandardCharsets.ISO_8859_1));
                    return head == null ? Result.ERROR : Result.DONE;
                }
                if (acc.size() > 65536) {
                    return Result.ERROR;
                }
            }
            return Result.NEED_MORE;
        }

        /**
         * 获取解析出的头。
         *
         * @return 头信息
         */
        RequestHead head() {
            return head;
        }

        /**
         * 重置以复用于同连接下一请求。
         */
        void reset() {
            acc.reset();
            head = null;
        }

        private boolean endsWithHeaderEnd() {
            byte[] d = acc.toByteArray();
            int n = d.length;
            return n >= 4 && d[n - 4] == '\r' && d[n - 3] == '\n'
                    && d[n - 2] == '\r' && d[n - 1] == '\n';
        }

        private RequestHead parse(String raw) {
            String[] lines = raw.split("\r\n");
            if (lines.length < 1) {
                return null;
            }
            String[] parts = lines[0].split(" ");
            if (parts.length < 3) {
                return null;
            }
            String host = null;
            int port = 80;
            long cl = 0;
            String connection = "";
            java.util.List<String> headers = new java.util.ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                String h = lines[i];
                if (h.isEmpty()) {
                    continue;
                }
                headers.add(h);
                String lower = h.toLowerCase();
                if (lower.startsWith("host:")) {
                    String v = h.substring(5).trim();
                    int idx = v.lastIndexOf(':');
                    if (idx > 0 && idx < v.length() - 1) {
                        host = v.substring(0, idx);
                        try {
                            port = Integer.parseInt(v.substring(idx + 1));
                        } catch (NumberFormatException ignored) {
                            port = 80;
                        }
                    } else {
                        host = v;
                    }
                } else if (lower.startsWith("content-length:")) {
                    try {
                        cl = Long.parseLong(h.substring(15).trim());
                    } catch (NumberFormatException ignored) {
                    }
                } else if (lower.startsWith("connection:")) {
                    connection = h.substring(11).trim();
                }
            }
            if (host == null && parts[1].startsWith("http://")) {
                java.net.URI u = java.net.URI.create(parts[1]);
                host = u.getHost();
                port = u.getPort() > 0 ? u.getPort() : 80;
            }
            if (host == null) {
                return null;
            }
            boolean ka = "HTTP/1.1".equalsIgnoreCase(parts[2])
                    ? !"close".equalsIgnoreCase(connection)
                    : "keep-alive".equalsIgnoreCase(connection);
            return new RequestHead(parts[0], parts[1], host, port,
                    headers, cl, connection, ka);
        }
    }

    /**
     * 请求头信息。
     */
    static final class RequestHead {
        /** 方法 */
        final String method;
        /** 原始目标 */
        final String target;
        /** 目标主机 */
        final String host;
        /** 目标端口 */
        final int port;
        /** 头部行 */
        final java.util.List<String> headers;
        /** 体长度 */
        final long contentLength;
        /** Connection 值 */
        final String connection;
        /** 是否保持连接 */
        final boolean keepAlive;

        RequestHead(String method, String target, String host, int port,
                    java.util.List<String> headers, long contentLength,
                    String connection, boolean keepAlive) {
            this.method = method;
            this.target = target;
            this.host = host;
            this.port = port;
            this.headers = headers;
            this.contentLength = contentLength;
            this.connection = connection;
            this.keepAlive = keepAlive;
        }

        /**
         * 绝对 URI → 源形式路径。
         */
        String extractOriginForm() {
            if (target.startsWith("http://") || target.startsWith("https://")) {
                java.net.URI u = java.net.URI.create(target);
                String p = u.getRawPath();
                return (p == null || p.isEmpty() ? "/" : p)
                        + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
            }
            return target;
        }
    }

    /**
     * 响应头增量解析器(结构同 HeadParser,提取 CL/Connection)。
     */
    static final class ResponseParser {

        private final ByteArrayOutputStream acc = new ByteArrayOutputStream(256);
        private ResponseMeta meta;

        /**
         * 喂入后端响应字节。
         *
         * @param in 缓冲
         * @return 结果
         */
        HeadParser.Result feed(ByteBuffer in) {
            while (in.hasRemaining()) {
                int b = in.get() & 0xFF;
                acc.write(b);
                if (b == '\n' && endsWithHeaderEnd()) {
                    meta = parse(acc.toByteArray());
                    return meta == null ? HeadParser.Result.ERROR : HeadParser.Result.DONE;
                }
                if (acc.size() > 65536) {
                    return HeadParser.Result.ERROR;
                }
            }
            return HeadParser.Result.NEED_MORE;
        }

        /**
         * 获取响应元数据。
         *
         * @return 元数据
         */
        ResponseMeta meta() {
            return meta;
        }

        private boolean endsWithHeaderEnd() {
            byte[] d = acc.toByteArray();
            int n = d.length;
            return n >= 4 && d[n - 4] == '\r' && d[n - 3] == '\n'
                    && d[n - 2] == '\r' && d[n - 1] == '\n';
        }

        private ResponseMeta parse(byte[] raw) {
            String text = new String(raw, StandardCharsets.ISO_8859_1);
            if (!text.startsWith("HTTP/")) {
                return null;
            }
            long cl = -1;
            boolean close = false;
            for (String line : text.split("\r\n")) {
                String lower = line.toLowerCase();
                if (lower.startsWith("content-length:")) {
                    try {
                        cl = Long.parseLong(line.substring(15).trim());
                    } catch (NumberFormatException ignored) {
                    }
                } else if (lower.startsWith("connection:")
                        && lower.contains("close")) {
                    close = true;
                }
            }
            return new ResponseMeta(raw, cl, close);
        }
    }

    /**
     * 响应元数据。
     */
    static final class ResponseMeta {
        /** 原始头(含结尾空行) */
        final byte[] rawHeader;
        /** 体长度,-1 表示未声明 */
        final long contentLength;
        /** 后端是否声明 close */
        final boolean close;

        ResponseMeta(byte[] rawHeader, long contentLength, boolean close) {
            this.rawHeader = rawHeader;
            this.contentLength = contentLength;
            this.close = close;
        }
    }

    @Override
    /** Do停止Accepting */
    protected void doStopAccepting() {
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    /** Do停止 */
    protected void doStop() {
        if (group != null) {
            try {
                group.shutdownNow();
            } catch (Exception ignored) {
            }
        }
        log.info("AIO HttpProxy stopped");
    }

    @Override
    /** 获取ProtocolType */
    public ProtocolType getProtocolType() {
        return ProtocolType.HTTP;
    }

    /**
     * 获取活跃连接数。
     *
     * @return 活跃连接数
     */
    public int getActiveConnections() {
        return activeConnections.get();
    }

}
