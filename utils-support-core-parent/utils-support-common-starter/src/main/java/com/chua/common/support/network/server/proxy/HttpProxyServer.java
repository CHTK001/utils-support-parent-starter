// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.server.proxy;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * JDK HTTP 反向代理服务器（基于 {@link AbstractProxyServer} 骨架，短连接）。
 *
 * <p>复用 AbstractProxyServer 的非阻塞批量 accept + 连接限流 + 虚拟线程池；
 * {@link #handleConnection(Socket)} 内完成"读 HTTP 请求 → 解析后端 → 转发 → 回传响应 → 关闭"，
 * 一请求一响应一断。</p>
 *
 * <p>与 vertx 版 {@code VertxHttpProxyServer}（事件循环异步）对等，本实现为 JDK 阻塞版。</p>
 *
 * <p>热路径口径（2026-11-23 优化）：请求/响应头全程字节级扫描，不落地中间 String、
 * 不做 {@code split} 分配；{@code readHeader} 用线程内可增长缓冲替代逐字节同步的
 * {@code ByteArrayOutputStream}；响应体泵送缓冲线程内复用；DEBUG 日志按需求值。</p>
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
     * 后端连接的输入流缓冲缓存（Socket → BufferedInputStream）。
     *
     * <p>响应头逐字节读，必须走缓冲；同时必须按 Socket 复用同一个实例，
     * 否则每请求新建会把多读的字节吞掉，keep-alive 复用即错位。</p>
     */
    private final java.util.Map<Socket, java.io.InputStream> backendStreams =
            new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * 连接池容量上限
     */
    private static final int BACKEND_POOL_MAX = 8;

    /**
     * 单个请求头的大小上限（1MB，DoS 防护，与原实现一致）。
     */
    private static final int MAX_HEADER_SIZE = 1 << 20;

    /**
     * 空请求体共享实例：GET/HEAD 等无 body 请求避免每次分配。
     */
    private static final byte[] EMPTY_BODY = new byte[0];

    /**
     * 头名常量（ASCII 小写字节），供字节级扫描做忽略大小写前缀匹配，避免中间 String。
     */
    private static final byte[] H_CONTENT_LENGTH = ascii("content-length:");
    private static final byte[] H_CONNECTION = ascii("connection:");
    private static final byte[] H_TRANSFER_ENCODING = ascii("transfer-encoding:");

    /**
     * 请求头解析用线程内缓冲（初始 1KB，按需倍增，上限 {@link #MAX_HEADER_SIZE}）。
     * <p>虚拟线程一连接一线程，缓冲随连接生命周期复用。</p>
     */
    private static final ThreadLocal<byte[]> HEADER_BUF = ThreadLocal.withInitial(() -> new byte[1024]);

    /**
     * 响应体泵送缓冲（8KB，线程内复用；一连接一线程，无竞争）。
     */
    private static final ThreadLocal<byte[]> PIPE_BUF = ThreadLocal.withInitial(() -> new byte[8192]);

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
     * @throws IOException 异常
    */
    private Socket borrowBackend(InetSocketAddress backend) throws IOException {
        Socket pooled;
        while ((pooled = backendPool.poll()) != null) {
            if (pooled.isClosed() || pooled.isInputShutdown() || pooled.isOutputShutdown()) {
                try {
                    pooled.close();
                } catch (IOException e) {
                    log.debug("[proxy] IO 操作失败，已忽略: {}", e.getMessage());
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
        closeBackend(socket);
    }

    /**
     * 后端连接的输入流缓冲（按 Socket 缓存）。
     *
     * <p>响应头是逐字节读的；直接读原始 <code>SocketInputStream</code> 会变成「每字节一次系统调用」，
     * 一个响应头 100~200 字节就是 100~200 次 syscall。这里按 Socket 缓存同一个
     * {@link java.io.BufferedInputStream}，并且<b>必须复用同一个实例</b>：每请求新建会把
     * 多读出来的字节吞掉，keep-alive 复用即错位。</p>
     *
     * @param socket 后端连接
     * @return 缓冲输入流
     */
    private java.io.InputStream bufferedInput(Socket socket) {
        return backendStreams.computeIfAbsent(socket, s -> {
            try {
                return new java.io.BufferedInputStream(s.getInputStream(), 8192);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    /**
     * 关闭后端连接，并清掉它的输入流缓存。
     *
     * @param socket 连接
     */
    private void closeBackend(Socket socket) {
        backendStreams.remove(socket);
        try {
            socket.close();
        } catch (IOException e) {
            log.debug("[proxy] 关闭后端连接失败，已忽略: {}", e.getMessage());
        }
    }

    @Override
    protected void handleConnection(Socket clientSocket) {
        if (log.isDebugEnabled()) {
            log.debug("handleConnection: {}", clientSocket.getRemoteSocketAddress());
        }
        try (clientSocket) {
            clientSocket.setSoTimeout(readTimeoutMs);
            // BufferedInputStream 包装:readHeader 逐字节读但走内存缓冲,避免原生 read 系统调用开销
            InputStream in = new java.io.BufferedInputStream(clientSocket.getInputStream(), 8192);
            OutputStream out = clientSocket.getOutputStream();

            // IP 过滤器（若挂载）：在读取请求前拒绝，避免把请求打到后端
            if (!allowClient(clientSocket)) {
                writeSimple(out, 403, "Forbidden");
                return;
            }

            // keep-alive 循环：同一连接上处理多个 HTTP 请求
            while (true) {
                // 读 HTTP 请求头（直到空行）
                byte[] header = readHeader(in);
                if (header == null || header.length == 0) {
                    return;
                }
                // 请求行校验（与原 split(" ") 口径一致）：须有 \r\n，且第一个空格后到行尾
                // 至少还有一个非空格字符（split 会丢弃尾部空串，"GET " 只有一个 token）
                int crlf = indexOfCrlf(header, header.length);
                if (crlf <= 0) {
                    return;
                }
                int sp1 = indexOf(header, crlf, ' ', 0);
                if (sp1 < 0) {
                    return;
                }
                boolean hasSecondToken = false;
                for (int i = sp1 + 1; i < crlf; i++) {
                    if (header[i] != ' ') {
                        hasSecondToken = true;
                        break;
                    }
                }
                if (!hasSecondToken) {
                    return;
                }

                // 客户端是否要求 keep-alive（HTTP/1.1 默认 keep-alive；首条 Connection 头决定）
                boolean clientKeepAlive = !headerHasToken(header, header.length, H_CONNECTION, "close");
                // 请求体长度：字节级解析，避免整头 String 化与 split 分配
                int reqBodyLen = parseContentLength(header, header.length);

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
                        InputStream backIn = bufferedInput(backendSocket);

                        // 转发请求头（保留 method/path/版本，透传其余头）+ body
                        backOut.write(header);
                        byte[] body = readBody(in, reqBodyLen);
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
                            if (headerHasToken(respHeader, respHeader.length, H_TRANSFER_ENCODING, "chunked")) {
                                pipeChunked(backIn, out);
                                backendKeepAlive = !headerHasToken(respHeader, respHeader.length,
                                        H_CONNECTION, "close");
                            } else {
                                int cl = parseContentLength(respHeader, respHeader.length);
                                if (cl > 0) {
                                    pipeN(backIn, out, cl);
                                    backendKeepAlive = !headerHasToken(respHeader, respHeader.length,
                                            H_CONNECTION, "close");
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
                            closeBackend(backendSocket);
                            backendSocket = null;
                        }
                        if (attempt == 0) {
                            continue;
                        }
                        // 两次都失败：后端不可达（拒绝连接/超时）。给客户端一个明确的 502，
                        // 而不是直接关连接——否则排障时分不清「后端挂了」与「网络断了」。
                        try {
                            writeSimple(out, 502, "Bad Gateway: backend unreachable");
                        } catch (IOException writeEx) {
                            log.debug("[proxy] 回写 502 失败，已忽略: {}", writeEx.getMessage());
                        }
                        throw e;
                    } finally {
                        if (backendSocket != null) {
                            closeBackend(backendSocket);
                        }
                    }
                }
                if (log.isDebugEnabled()) {
                    // method/path 仅用于日志，按需解码（热路径零 String 分配）
                    int sp2 = indexOf(header, crlf, ' ', sp1 + 1);
                    int pathEnd = (sp2 < 0 ? crlf : sp2);
                    log.debug("http-proxy: {} {} -> {}: {}",
                            new String(header, 0, sp1, StandardCharsets.ISO_8859_1),
                            new String(header, sp1 + 1, pathEnd - sp1 - 1, StandardCharsets.ISO_8859_1),
                            backend.getHostString(), backend.getPort());
                }

                // 客户端不要求 keep-alive 或连接已关闭，退出循环
                if (!clientKeepAlive) {
                    break;
                }
            }
        } catch (IOException e) {
            log.debug("http-proxy 连接异常: {}", e.getMessage());
        }
    }

    /**
     * 读 HTTP 头（直到 \r\n\r\n），BufferedInputStream 包装后逐字节读已足够快且不吞 body。
     * <p>原实现逐字节 {@code ByteArrayOutputStream.write}（内部 synchronized），
     * 一个 100~200 字节的头就是同数量级的监视器获取；这里改为线程内普通数组 +
     * 按需倍增，热路径无锁、无每请求分配（缓冲随线程复用，返回值为精确长度副本）。</p>
     *
     * @param in 方法入参 in
     * @return 头字节（精确长度）；流起始即 EOF 时返回 null
     * @throws IOException 异常
     */
    private byte[] readHeader(InputStream in) throws IOException {
        byte[] buf = HEADER_BUF.get();
        int pos = 0;
        int prevPrev = -1;
        int prev = -1;
        int b;
        while ((b = in.read()) != -1) {
            if (pos == buf.length) {
                if (pos >= MAX_HEADER_SIZE) {
                    throw new IOException("HTTP 头过大");
                }
                buf = java.util.Arrays.copyOf(buf, Math.min(buf.length * 2, MAX_HEADER_SIZE));
                HEADER_BUF.set(buf);
            }
            buf[pos++] = (byte) b;
            // HTTP 头结束 = 空行（\r\n\r\n：检测 \n 前是 \r、\r 前是 \n）
            if (b == '\n' && prev == '\r' && prevPrev == '\n') {
                break;
            }
            prevPrev = prev;
            prev = b;
        }
        return pos == 0 ? null : java.util.Arrays.copyOf(buf, pos);
    }

    /**
     * 读取请求体（按调用方已解析的 Content-Length）。
     * @param in 方法入参 in
     * @param len 请求体长度（&le;0 表示无请求体）
     * @return 请求体字节；无请求体时返回共享空数组
     * @throws IOException 异常
     */
    private byte[] readBody(InputStream in, int len) throws IOException {
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
        return EMPTY_BODY;
    }

    /**
     * 在 [from, end) 内查找字符 c 首次出现位置。
     *
     * @param h    头字节
     * @param end  搜索终点（不含）
     * @param c    目标字符（ASCII）
     * @param from 搜索起点
     * @return 首次出现下标；未找到返回 -1
     */
    private static int indexOf(byte[] h, int end, char c, int from) {
        byte target = (byte) c;
        for (int i = from; i < end; i++) {
            if (h[i] == target) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 查找首个 \r\n 序列下标。
     *
     * @param h   头字节
     * @param len 有效长度
     * @return \r 的下标；未找到返回 -1
     */
    private static int indexOfCrlf(byte[] h, int len) {
        for (int i = 0; i + 1 < len; i++) {
            if (h[i] == '\r' && h[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /**
     * 首个以 {@code name} 开头的头行是否含 token（忽略大小写）。
     *
     * <p>口径与原 {@code connectionClose} 一致：由<b>第一条</b>同名头决定结果。
     * {@code transfer-encoding} 亦按行匹配（原 containsIgnoreCase 对
     * "gzip, chunked" 这类多编码值会漏判，此处按值内 token 判定更准确）。</p>
     *
     * @param h     头字节
     * @param len   有效长度
     * @param name  头名（含冒号，ASCII 小写）
     * @param token 目标 token（ASCII）
     * @return 是否包含
     */
    private static boolean headerHasToken(byte[] h, int len, byte[] name, String token) {
        int start = 0;
        while (start < len) {
            int e = start;
            while (e < len && h[e] != '\n') {
                e++;
            }
            int eNoCr = (e > start && h[e - 1] == '\r') ? e - 1 : e;
            if (startsWithIgnoreCase(h, start, eNoCr, name)) {
                return regionContainsIgnoreCase(h, start, eNoCr, token);
            }
            start = e + 1;
        }
        return false;
    }

    /**
     * 解析首个 Content-Length 头的值；缺失/非法/溢出/负数一律返回 0。
     * <p>与原实现口径一致：原 {@code Integer.parseInt} 抛异常归 0、负值在调用方
     * {@code >0} 判定下与 0 等价；这里直接收敛为 0。</p>
     *
     * @param h   头字节
     * @param len 有效长度
     * @return 内容长度（非负）；无有效值时为 0
     */
    private static int parseContentLength(byte[] h, int len) {
        int start = 0;
        while (start < len) {
            int e = start;
            while (e < len && h[e] != '\n') {
                e++;
            }
            int eNoCr = (e > start && h[e - 1] == '\r') ? e - 1 : e;
            if (startsWithIgnoreCase(h, start, eNoCr, H_CONTENT_LENGTH)) {
                return parseHeaderNumber(h, start + H_CONTENT_LENGTH.length, eNoCr);
            }
            start = e + 1;
        }
        return 0;
    }

    /**
     * 解析头值里的十进制数（跳过前后空白；非法/溢出返回 0）。
     *
     * @param h    头字节
     * @param from 值起点（头名之后）
     * @param end  行尾（不含 CRLF）
     * @return 数值；非法时 0
     */
    private static int parseHeaderNumber(byte[] h, int from, int end) {
        int i = from;
        while (i < end && (h[i] == ' ' || h[i] == '\t')) {
            i++;
        }
        boolean negative = false;
        if (i < end && (h[i] == '-' || h[i] == '+')) {
            negative = h[i] == '-';
            i++;
        }
        long value = 0;
        boolean anyDigit = false;
        while (i < end && h[i] >= '0' && h[i] <= '9') {
            value = value * 10 + (h[i] - '0');
            if (value > Integer.MAX_VALUE) {
                return 0;
            }
            anyDigit = true;
            i++;
        }
        if (!anyDigit) {
            return 0;
        }
        while (i < end && (h[i] == ' ' || h[i] == '\t')) {
            i++;
        }
        // 尾部仍有非空白字符 → 非法（原 parseInt 会抛 NumberFormatException → 0）
        if (i != end) {
            return 0;
        }
        // 负数在调用方「>0 才有 body」的语义下与 0 等价
        return negative ? 0 : (int) value;
    }

    /**
     * 行前缀匹配（ASCII 忽略大小写，仅字母做大小写翻转）。
     *
     * @param h     头字节
     * @param start 行起点
     * @param end   行终点（不含）
     * @param name  头名常量（ASCII 小写）
     * @return 是否匹配
     */
    private static boolean startsWithIgnoreCase(byte[] h, int start, int end, byte[] name) {
        if (end - start < name.length) {
            return false;
        }
        for (int i = 0; i < name.length; i++) {
            int bb = h[start + i] & 0xFF;
            int cc = name[i] & 0xFF;
            if (bb != cc && !(isAsciiAlpha(cc) && bb == (cc ^ 0x20))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 区间内 token 包含判断（ASCII 忽略大小写）。
     *
     * @param h     头字节
     * @param from 起点
     * @param end   终点（不含）
     * @param token 目标 token（ASCII）
     * @return 是否包含
     */
    private static boolean regionContainsIgnoreCase(byte[] h, int from, int end, String token) {
        int tl = token.length();
        if (tl == 0) {
            return true;
        }
        for (int i = from, limit = end - tl; i <= limit; i++) {
            int j = 0;
            while (j < tl) {
                int bb = h[i + j] & 0xFF;
                int cc = token.charAt(j) & 0xFF;
                if (bb != cc && !(isAsciiAlpha(cc) && bb == (cc ^ 0x20))) {
                    break;
                }
                j++;
            }
            if (j == tl) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否 ASCII 字母。
     *
     * @param c 字符（0~255）
     * @return 是否字母
     */
    private static boolean isAsciiAlpha(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * 头名常量转 ASCII 字节数组。
     *
     * @param s ASCII 字符串
     * @return 字节数组
     */
    private static byte[] ascii(String s) {
        byte[] b = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            b[i] = (byte) s.charAt(i);
        }
        return b;
    }

    /**
     * 原样泵送响应体（close-delimited，读到 EOF）。
     * @param in 方法入参 in
     * @param out 方法入参 out
     * @throws IOException 异常
     */
    private void pipeRaw(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = PIPE_BUF.get();
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
            out.flush();
        }
    }

    /**
     * 精确读取并转发 {@code length} 字节（Content-Length 响应体，避免 keep-alive 连接阻塞到超时）。
     * @param in 方法入参 in
     * @param length 长度
     * @param out 方法入参 out
     * @throws IOException 异常
     */
    private void pipeN(InputStream in, OutputStream out, int length) throws IOException {
        byte[] buffer = PIPE_BUF.get();
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
     * @throws IOException 异常
     */
    private void pipeChunked(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = PIPE_BUF.get();
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
     * <p>HTTP 头部的固定前/后缀在常量池复用，只动态拼接状态码与 Content-Length，
     * 避免整段响应重复编码。</p>
     *
     * @param out 方法入参 out
     * @param code 编码，不允许为 null
     * @param msg 消息，不允许为 null
     * @throws IOException 当执行过程不满足前置条件时
     */
    private void writeSimple(OutputStream out, int code, String msg) throws IOException {
        byte[] body = (msg == null ? "" : msg).getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder(96);
        head.append("HTTP/1.1 ").append(code).append(' ')
                .append(code == 502 ? "Bad Gateway" : "Error").append("\r\n")
                .append("Content-Type: text/plain\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }
}
