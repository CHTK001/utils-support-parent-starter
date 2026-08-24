package com.chua.common.support.network.server.proxy;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.aio.AioTcpServer;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 AIO(Proactor/IOCP) 的正向 HTTP 代理(每连接虚拟线程 + Future 阻塞)。
 *
 * <p>支持:
 * <ul>
 *   <li>CONNECT 隧道(HTTPS):回 200 后原始字节双向对拷</li>
 *   <li>绝对 URI 转发(HTTP):按 Host 头定位后端,改写为源形式转发,
 *       Content-Length 请求/响应体透传,客户端 Keep-Alive 循环复用</li>
 * </ul>
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
    /** 虚拟线程池 */
    private ExecutorService executor;

    /** 活跃连接数 */
    private final AtomicInteger activeConnections = new AtomicInteger();

    /**
     * 创建 AIO HTTP 代理。
     *
     * @param setting 配置
     */
    public AioHttpProxyServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    /** Do开始 */
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
            executor = Executors.newVirtualThreadPerTaskExecutor();
            acceptLoop();
            log.info("AIO HttpProxy started on {}:{} (iocpThreads={})",
                    setting.getHost(), setting.getPort(), threads);
        } catch (Exception e) {
            throw new RuntimeException("AIO HttpProxy 启动失败", e);
        }
    }

    /**
     * 接纳循环。
     */
    private void acceptLoop() {
        Thread.ofVirtual().name("aio-httpproxy-accept", 0).start(() -> {
            while (running && serverChannel.isOpen()) {
                try {
                    AsynchronousSocketChannel client = serverChannel.accept().get();
                    handleClient(client);
                } catch (Exception e) {
                    if (!running || !serverChannel.isOpen()) {
                        return;
                    }
                    log.debug("AIO HttpProxy accept 异常: {}", e.getMessage());
                }
            }
        });
    }

    /**
     * 单连接处理:Keep-Alive 循环内逐请求转发。
     *
     * @param client 接入通道
     */
    private void handleClient(AsynchronousSocketChannel client) {
        activeConnections.incrementAndGet();
        executor.submit(() -> {
            try {
                client.setOption(StandardSocketOptions.TCP_NODELAY, true);
                InputStream in = AioTcpServer.newBlockingReader(client);
                OutputStream out = AioTcpServer.newBlockingWriter(client);
                while (running && client.isOpen()) {
                    RequestHead head = RequestHead.read(in);
                    if (head == null) {
                        return;
                    }
                    if ("CONNECT".equalsIgnoreCase(head.method)) {
                        tunnel(head.host, head.port, in, out);
                        return;
                    }
                    boolean keepAlive = forward(head, in, out);
                    if (!keepAlive) {
                        return;
                    }
                }
            } catch (Exception e) {
                log.debug("AIO HttpProxy 连接结束: {}", e.getMessage());
            } finally {
                closeQuietly(client);
                activeConnections.decrementAndGet();
            }
        });
    }

    /**
     * CONNECT 隧道:回 200 后双向原始字节对拷至任一端关闭。
     */
    private void tunnel(String host, int port, InputStream in, OutputStream out) throws Exception {
        AsynchronousSocketChannel backend = null;
        try {
            backend = connect(new InetSocketAddress(host, port));
            out.write(("HTTP/1.1 200 Connection Established\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.flush();
            final AsynchronousSocketChannel established = backend;
            Thread t1 = Thread.ofVirtual().start(() -> pipeRaw(in, wrapWriter(established)));
            pipeRaw(wrapReader(established), out);
            t1.join();
        } finally {
            closeQuietly(backend);
        }
    }

    /**
     * 普通请求转发:绝对 URI → 源形式;返回后端是否保持连接。
     */
    private boolean forward(RequestHead head, InputStream clientIn, OutputStream clientOut)
            throws Exception {
        String host = head.host;
        int port = head.port;
        String path = head.extractOriginForm();
        AsynchronousSocketChannel backend = connect(new InetSocketAddress(host, port));
        try {
            OutputStream bOut = AioTcpServer.newBlockingWriter(backend);
            StringBuilder req = new StringBuilder(128);
            req.append(head.method).append(' ').append(path).append(" HTTP/1.1\r\n");
            boolean hasHost = false;
            byte[] body = null;
            for (String line : head.headers) {
                String lower = line.toLowerCase();
                if (lower.startsWith("host:")) {
                    hasHost = true;
                    req.append("Host: ").append(host).append(':').append(port).append("\r\n");
                    continue;
                }
                if (lower.startsWith("proxy-")) {
                    continue;
                }
                if (lower.startsWith("content-length:")) {
                    int len = Integer.parseInt(line.substring(15).trim());
                    if (len > 0) {
                        body = inReadFully(clientIn, len);
                    }
                    continue;
                }
                if (lower.startsWith("connection:")) {
                    continue;
                }
                req.append(line).append("\r\n");
            }
            if (!hasHost) {
                req.append("Host: ").append(host).append(':').append(port).append("\r\n");
            }
            req.append("Connection: ").append(head.keepAlive ? "keep-alive" : "close").append("\r\n");
            if (body != null) {
                req.append("Content-Length: ").append(body.length).append("\r\n");
            }
            req.append("\r\n");
            bOut.write(req.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            if (body != null) {
                bOut.write(body);
            }
            bOut.flush();

            // 回传响应头 + Content-Length 体
            ResponseMeta meta = ResponseMeta.read(AioTcpServer.newBlockingReader(backend));
            if (meta == null) {
                return false;
            }
            clientOut.write(meta.rawHeader);
            if (meta.contentLength > 0) {
                relayN(AioTcpServer.newBlockingReader(backend), clientOut, meta.contentLength);
            } else if (!"close".equalsIgnoreCase(meta.connection)) {
                // 无长度且非 close:视为无体(常见于 204/304)
            }
            clientOut.flush();
            return head.keepAlive && !"close".equalsIgnoreCase(meta.connection)
                    && !"close".equalsIgnoreCase(head.connection);
        } finally {
            closeQuietly(backend);
        }
    }

    /**
     * 精确中继 N 字节。
     */
    private static void relayN(InputStream src, OutputStream dst, long n) throws Exception {
        byte[] buf = new byte[8192];
        long left = n;
        while (left > 0) {
            int r = src.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) {
                throw new IllegalStateException("后端提前关闭");
            }
            dst.write(buf, 0, r);
            left -= r;
        }
    }

    /**
     * 读满指定字节数。
     */
    private static byte[] inReadFully(InputStream in, int len) throws Exception {
        byte[] data = new byte[len];
        int off = 0;
        while (off < len) {
            int r = in.read(data, off, len - off);
            if (r < 0) {
                throw new IllegalStateException("客户端提前关闭");
            }
            off += r;
        }
        return data;
    }

    /**
     * 建立后端连接。
     */
    private AsynchronousSocketChannel connect(InetSocketAddress addr) throws Exception {
        AsynchronousSocketChannel ch = AsynchronousSocketChannel.open(group);
        ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
        ch.connect(addr).get(Math.max(setting.getReadTimeout(), 10_000L),
                TimeUnit.MILLISECONDS);
        return ch;
    }

    /**
     * 原始字节对拷。
     */
    private static void pipeRaw(InputStream in, OutputStream out) {
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 通道包装为阻塞读流。
     */
    private static InputStream wrapReader(AsynchronousSocketChannel ch) {
        return AioTcpServer.newBlockingReader(ch);
    }

    /**
     * 通道包装为阻塞写流。
     */
    private static OutputStream wrapWriter(AsynchronousSocketChannel ch) {
        return AioTcpServer.newBlockingWriter(ch);
    }

    /**
     * 请求头解析结果。
     */
    private static final class RequestHead {
        /** 方法 */
        final String method;
        /** 目标主机 */
        final String host;
        /** 目标端口 */
        final int port;
        /** 原始请求行+头部行 */
        final java.util.List<String> headers;
        /** 请求目标(原始) */
        final String target;
        /** 客户端 Connection 值 */
        final String connection;
        /** 是否 keep-alive */
        final boolean keepAlive;

        private RequestHead(String method, String host, int port,
                            java.util.List<String> headers, String target,
                            String connection, boolean keepAlive) {
            this.method = method;
            this.host = host;
            this.port = port;
            this.headers = headers;
            this.target = target;
            this.connection = connection;
            this.keepAlive = keepAlive;
        }

        /**
         * 从流中读取并解析请求头(读到空行为止)。
         *
         * @param in 输入流
         * @return 头信息,null 表示 EOF
         */
        static RequestHead read(InputStream in) throws Exception {
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return null;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 3) {
                throw new IllegalStateException("非法请求行: " + requestLine);
            }
            String method = parts[0];
            String target = parts[1];
            java.util.List<String> headers = new java.util.ArrayList<>();
            String host = null;
            int port = 80;
            String connection = "";
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                headers.add(line);
                String lower = line.toLowerCase();
                if (lower.startsWith("host:")) {
                    String v = line.substring(5).trim();
                    int idx = v.lastIndexOf(':');
                    if (idx > 0) {
                        host = v.substring(0, idx);
                        port = Integer.parseInt(v.substring(idx + 1));
                    } else {
                        host = v;
                    }
                } else if (lower.startsWith("connection:")) {
                    connection = line.substring(11).trim();
                }
            }
            if (host == null) {
                if (target.startsWith("http://")) {
                    java.net.URI u = java.net.URI.create(target);
                    host = u.getHost();
                    port = u.getPort() > 0 ? u.getPort() : 80;
                } else {
                    host = "127.0.0.1";
                }
            }
            boolean ka = "HTTP/1.1".equalsIgnoreCase(parts[2])
                    ? !"close".equalsIgnoreCase(connection)
                    : "keep-alive".equalsIgnoreCase(connection);
            return new RequestHead(method, host, port, headers, target, connection, ka);
        }

        /**
         * 提取源形式路径(绝对 URI → path?query)。
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

        /**
         * 读取一行(CRLF/LF 兼容)。
         */
        private static String readLine(InputStream in) throws Exception {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(96);
            int prev = -1;
            int c;
            while ((c = in.read()) >= 0) {
                if (c == '\n') {
                    byte[] data = buf.toByteArray();
                    int end = data.length;
                    if (end > 0 && data[end - 1] == '\r') {
                        end--;
                    }
                    return new String(data, 0, end,
                            java.nio.charset.StandardCharsets.ISO_8859_1);
                }
                prev = c;
                buf.write(c);
            }
            return buf.size() == 0 ? null
                    : new String(buf.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * 响应元数据(仅解析状态行与关键头)。
     */
    private static final class ResponseMeta {
        /** 原始头字节(原样回传) */
        final byte[] rawHeader;
        /** 体长度,-1 表示无体 */
        final long contentLength;
        /** 后端 Connection 值 */
        final String connection;

        private ResponseMeta(byte[] rawHeader, long contentLength, String connection) {
            this.rawHeader = rawHeader;
            this.contentLength = contentLength;
            this.connection = connection;
        }

        /**
         * 从流读取响应头块。
         *
         * @param in 输入流
         * @return 元数据,null 表示 EOF
         */
        static ResponseMeta read(InputStream in) throws Exception {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
            long contentLength = -1;
            String connection = "";
            boolean first = true;
            String line;
            while ((line = readLine(in)) != null) {
                buf.write(line.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                buf.write('\r');
                buf.write('\n');
                if (line.isEmpty()) {
                    break;
                }
                if (first) {
                    first = false;
                    if (!line.startsWith("HTTP/")) {
                        return null;
                    }
                    continue;
                }
                String lower = line.toLowerCase();
                if (lower.startsWith("content-length:")) {
                    contentLength = Long.parseLong(line.substring(15).trim());
                } else if (lower.startsWith("connection:")) {
                    connection = line.substring(11).trim();
                }
            }
            return line == null ? null : new ResponseMeta(buf.toByteArray(), contentLength, connection);
        }

        /**
         * 读取一行。
         */
        private static String readLine(InputStream in) throws Exception {
            ByteArrayOutputStream b = new ByteArrayOutputStream(64);
            int c;
            while ((c = in.read()) >= 0) {
                if (c == '\n') {
                    byte[] d = b.toByteArray();
                    int end = d.length;
                    if (end > 0 && d[end - 1] == '\r') {
                        end--;
                    }
                    return new String(d, 0, end, java.nio.charset.StandardCharsets.ISO_8859_1);
                }
                b.write(c);
            }
            return b.size() == 0 ? null
                    : new String(b.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * 静默关闭通道。
     */
    private static void closeQuietly(AsynchronousSocketChannel ch) {
        if (ch != null) {
            try {
                ch.close();
            } catch (Exception ignored) {
            }
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
        if (executor != null) {
            executor.shutdownNow();
        }
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
}