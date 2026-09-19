package com.chua.common.support.network.server.proxy;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * JDK HTTP 反向代理服务器（基于 {@link AbstractProxyServer} 骨架，短连接）。
 *
 * <p>复用 AbstractProxyServer 的非阻塞批量 accept + 连接限流 + 虚拟线程池；
 * {@link #handleConnection(Socket)} 内完成"读 HTTP 请求 → 解析后端 → 转发 → 回传响应 → 关闭"，
 * 一请求一响应一断。</p>
 *
 * <p>与 vertx 版 {@code VertxHttpProxyServer}（事件循环异步）对等，本实现为 JDK 阻塞版。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"http-proxy"})
public class HttpProxyServer extends AbstractProxyServer {

    protected final ProxyTargetResolver<InetSocketAddress> targetResolver;
    protected final int connectTimeoutMs;
    protected final int readTimeoutMs;

    /**
     * 后端连接池：复用 keep-alive 后端连接，消除每次请求新建 TCP 连接开销（Reactor+虚拟线程下的吞吐瓶颈）
     */
    private final java.util.Queue<Socket> backendPool = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /**
     * 连接池容量上限
    */
    private static final int BACKEND_POOL_MAX = 8;

    /**
     * 构造方法，创建 HttpProxy服务端 实例。
     *
     * @param setting 方法入参 setting
     */
    public HttpProxyServer(ServerSetting setting) {
        super(setting);
        initProxy();
        this.targetResolver = remote -> null;
        this.connectTimeoutMs = setting.getReadTimeout();
        this.readTimeoutMs = setting.getWriteTimeout();
    }

    /**
     * 构造方法，创建 HttpProxy服务端 实例。
     *
     * @param setting 方法入参 setting
     * @param targetResolver 目标Resolver，不允许为 null
     */
    public HttpProxyServer(ServerSetting setting, ProxyTargetResolver<InetSocketAddress> targetResolver) {
        super(setting);
        initProxy();
        this.targetResolver = targetResolver;
        this.connectTimeoutMs = setting.getReadTimeout();
        this.readTimeoutMs = setting.getWriteTimeout();
    }

    /**
     * 构造方法，创建 HttpProxy服务端 实例。
     *
     * @param setting 方法入参 setting
     * @param backend 方法入参 backend
     */
    public HttpProxyServer(ServerSetting setting, InetSocketAddress backend) {
        this(setting, remote -> backend);
        log.debug("HttpProxyServer created, preferNonBlockingAccept={}", preferNonBlockingAccept);
    }

    /**
     * 初始化Proxy。
     */
    private void initProxy() {
        this.preferNonBlockingAccept = false;
    }

    @Override
    public com.chua.common.support.network.ProtocolType getProtocolType() {
        return com.chua.common.support.network.ProtocolType.HTTP;
    }

    /**
     * 借出后端连接：优先复用池中空闲连接，无则新建。
     * @param backend 方法入参 backend
     * @return Socket 对象
     */
    private Socket borrowBackend(InetSocketAddress backend) throws IOException {
        Socket pooled;
        while ((pooled = backendPool.poll()) != null) {
            if (pooled.isClosed() || pooled.isInputShutdown() || pooled.isOutputShutdown()) {
                try {
                    pooled.close();
                } catch (IOException ignored) {
                }
                continue;
            }
            return pooled;
        }
        Socket s = new Socket();
        s.connect(backend, connectTimeoutMs);
        s.setSoTimeout(readTimeoutMs);
        return s;
    }

    /**
     * 归还后端连接：keep-alive 且池未满才复用，否则关闭。
     * @param socket 方法入参 socket
     * @param keepAlive keepAlive（布尔开关）
     */
    private void returnBackend(Socket socket, boolean keepAlive) {
        if (keepAlive && backendPool.size() < BACKEND_POOL_MAX
                && !socket.isClosed() && !socket.isInputShutdown() && !socket.isOutputShutdown()) {
            backendPool.offer(socket);
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * 响应头是否 Connection: close（连接不可复用）。
     * @param header 请求头，不允许为 null
     * @return 是否成功（true 表示成功）
     */
    private boolean connectionClose(byte[] header) {
        String head = new String(header, java.nio.charset.StandardCharsets.ISO_8859_1);
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase().startsWith("connection:")) {
                return line.toLowerCase().contains("close");
            }
        }
        return false;
    }

    @Override
    protected void handleConnection(Socket clientSocket) {
        log.debug("handleConnection: {}", clientSocket.getRemoteSocketAddress());
        try (clientSocket) {
            clientSocket.setSoTimeout(readTimeoutMs);
            // BufferedInputStream 包装:readHeader 逐字节读但走内存缓冲,避免原生 read 系统调用开销
            InputStream in = new java.io.BufferedInputStream(clientSocket.getInputStream(), 8192);
            OutputStream out = clientSocket.getOutputStream();

            // keep-alive 循环：同一连接上处理多个 HTTP 请求
            while (true) {
                // 读 HTTP 请求头（直到空行）
                byte[] header = readHeader(in);
                if (header == null || header.length == 0) {
                    return;
                }
                // 解析请求行（method path HTTP/1.1）
                String headText = new String(header, java.nio.charset.StandardCharsets.ISO_8859_1);
                int lineEnd = headText.indexOf("\r\n");
                if (lineEnd <= 0) {
                    return;
                }
                String requestLine = headText.substring(0, lineEnd);
                String[] parts = requestLine.split(" ");
                if (parts.length < 2) {
                    return;
                }
                String method = parts[0];
                String path = parts[1];

                // 客户端是否要求 keep-alive（HTTP/1.1 默认 keep-alive）
                boolean clientKeepAlive = !connectionClose(header);

                InetSocketAddress backend = targetResolver.resolve(null);
                if (backend == null || backend.getPort() <= 0) {
                    writeSimple(out, 502, "Bad Gateway: backend not resolved");
                    return;
                }

                Socket backendSocket = null;
                // 复用池连接可能已被后端关闭（半死连接）：IO 失败时剔除并重试一次新连接
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        backendSocket = borrowBackend(backend);
OutputStream backOut = backendSocket.getOutputStream();
                    InputStream backIn = backendSocket.getInputStream();

                        // 转发请求头（保留 method/path/版本，透传其余头）+ body
                        backOut.write(header);
                        byte[] body = readBody(in, headText);
                        if (body.length > 0) {
                            backOut.write(body);
                        }
                        backOut.flush();

                        // 回传响应
                        byte[] respHeader = readHeader(backIn);
                        boolean backendKeepAlive = false;
                        if (respHeader != null) {
                            out.write(respHeader);
                            out.flush();
                            if (isChunked(respHeader)) {
                                pipeChunked(backIn, out);
                                backendKeepAlive = !connectionClose(respHeader);
                            } else {
                                int cl = contentLength(respHeader);
                                if (cl > 0) {
                                    pipeN(backIn, out, cl);
                                    backendKeepAlive = !connectionClose(respHeader);
                                } else {
                                    // 无长度（如 204/close-delimited）：读到 EOF，连接不可复用
                                    pipeRaw(backIn, out);
                                }
                            }
                        }
                        out.flush();
                        // 响应体完整读完后归还（keep-alive 复用）或关闭
                        returnBackend(backendSocket, backendKeepAlive);
                        backendSocket = null;
                        break;
                    } catch (IOException e) {
                        // 复用连接失败（坏连接）：关闭并重试一次（新连接）
                        if (backendSocket != null) {
                            try {
                                backendSocket.close();
                            } catch (IOException ignored) {
                            }
                            backendSocket = null;
                        }
                        if (attempt == 0) {
                            continue;
                        }
                        throw e;
                    } finally {
                        if (backendSocket != null) {
                            try {
                                backendSocket.close();
                            } catch (IOException ignored) {
                            }
                        }
                    }
                }
                log.debug("http-proxy: {} {} -> {}:{}", method, path,
                        backend.getHostString(), backend.getPort());

                // 客户端不要求 keep-alive 或连接已关闭，退出循环
                if (!clientKeepAlive) {
                    break;
                }
            }
        } catch (IOException e) {
            log.debug("http-proxy 连接异常: {}", e.getMessage());
        } finally {
            activeConnections.decrementAndGet();
        }
    }

    /**
     * 读 HTTP 头（直到 \r\n\r\n），BufferedInputStream 包装后逐字节读已足够快且不吞 body。
     * @param in 方法入参 in
     * @return 结果值
     */
    private byte[] readHeader(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);
        int prevPrev = -1;
        int prev = -1;
        int b;
        while ((b = in.read()) != -1) {
            bos.write(b);
            // HTTP 头结束 = 空行（\r\n\r\n：检测 \n 前是 \r、\r 前是 \n）
            if (b == '\n' && prev == '\r' && prevPrev == '\n') {
                break;
            }
            prevPrev = prev;
            prev = b;
            if (bos.size() > 1 << 20) {
                throw new IOException("HTTP 头过大");
            }
        }
        return bos.size() == 0 ? null : bos.toByteArray();
    }

    /**
     * 读取请求体（按 Content-Length 或 chunked）。
     * @param in 方法入参 in
     * @param headText 头部文本，不允许为 null
     * @return 结果值
     */
    private byte[] readBody(InputStream in, String headText) throws IOException {
        int len = contentLength(headText);
        if (len > 0) {
            byte[] body = new byte[len];
            int read = 0;
            while (read < len) {
                int r = in.read(body, read, len - read);
                if (r == -1) {
                    break;
                }
                read += r;
            }
            return body;
        }
        return new byte[0];
    }

    /**
     * 是否Chunked。
     *
     * @param header 请求头，不允许为 null
     * @return 是否成功（true 表示成功）
     */
    private boolean isChunked(byte[] header) {
        String text = new String(header, java.nio.charset.StandardCharsets.ISO_8859_1);
        return text.toLowerCase().contains("transfer-encoding: chunked");
    }

    /**
     * 内容长度。
     *
     * @param headText 头部文本，不允许为 null
     * @return 结果数值
     */
    private int contentLength(String headText) {
        for (String line : headText.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                try {
                    return Integer.parseInt(line.substring("content-length:".length()).trim());
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    /**
     * 响应头中的 Content-Length（用于判断是否转发 body）。
     * @param header 请求头，不允许为 null
     * @return 结果数值
     */
    private int contentLength(byte[] header) {
        return contentLength(new String(header, java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    /**
     * 原样泵送响应体（chunked 或定长）。
     * @param in 方法入参 in
     * @param out 方法入参 out
     */
    private void pipeRaw(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
            out.flush();
        }
    }

    /**
     * 精确读取并转发 {@code length} 字节（Content-Length 响应体，避免 keep-alive 连接阻塞到超时）。
     * @param in 方法入参 in
     * @param out 方法入参 out
     * @param length 长度，不允许为 null
     */
    private void pipeN(InputStream in, OutputStream out, int length) throws IOException {
        byte[] buffer = new byte[8192];
        int remaining = length;
        while (remaining > 0) {
            int n = in.read(buffer, 0, Math.min(buffer.length, remaining));
            if (n == -1) {
                return;
            }
            out.write(buffer, 0, n);
            remaining -= n;
        }
        out.flush();
    }

    /**
     * 按 chunked 编码解析并转发响应体（直到 0 长度 chunk 后的终止 CRLF）。
     * @param in 方法入参 in
     * @param out 方法入参 out
     */
    private void pipeChunked(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        while (true) {
            // 读 chunk 大小行（hex + CRLF）
            int size = 0;
            boolean sizeParsed = false;
            while (!sizeParsed) {
                int b = in.read();
                if (b == -1) {
                    return;
                }
                if (b == '\r') {
                    in.read(); // \n
                    sizeParsed = true;
                } else if (b >= '0' && b <= '9') {
                    size = size * 16 + (b - '0');
                } else if (b >= 'a' && b <= 'f') {
                    size = size * 16 + (b - 'a' + 10);
                } else if (b >= 'A' && b <= 'F') {
                    size = size * 16 + (b - 'A' + 10);
                }
            }
            if (size <= 0) {
                // 0 长度 chunk：读终止 CRLF
                in.read();
                in.read();
                return;
            }
            // 转发 chunk 数据
            int remaining = size;
            while (remaining > 0) {
                int n = in.read(buffer, 0, Math.min(buffer.length, remaining));
                if (n == -1) {
                    return;
                }
                out.write(buffer, 0, n);
                remaining -= n;
            }
            in.read(); // chunk 后的 CR
            in.read(); // LF
            out.flush();
        }
    }

    /**
     * 写入Simple。
     *
     * @param out 方法入参 out
     * @param code 编码，不允许为 null
     * @param msg 消息，不允许为 null
     * @throws IOException 当执行过程不满足前置条件时
     */
    private void writeSimple(OutputStream out, int code, String msg) throws IOException {
        String body = msg == null ? "" : msg;
        String resp = "HTTP/1.1 " + code + " " + (code == 502 ? "Bad Gateway" : "Error") + "\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: " + body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + "\r\n"
                + "Connection: close\r\n\r\n" + body;
        out.write(resp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.flush();
    }
}
