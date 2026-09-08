package com.chua.common.support.network.server.impl;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.crypto.AesGcmUtils;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.tcp.TcpServer;
import com.chua.common.support.network.tcp.callback.TcpServerHandler;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 JDK NIO 多 Selector Reactor 的 TCP 服务器实现。
 *
 * <p>采用「一个接收线程 + 多个 IO Selector 线程 + Worker 线程池」的 Reactor 模式：</p>
 * <ul>
 *   <li>接收线程只处理 {@code OP_ACCEPT}，接受连接后轮询注册到某个 IO Selector，分散读压力</li>
 *   <li>IO 线程只分发读事件与拼帧，业务处理交由 Worker 线程池，避免业务慢操作阻塞 IO 事件循环</li>
 * </ul>
 *
 * <p>同时实现 {@link TcpServer} 长度帧协议（4 字节大端长度头 + 消息体），
 * 兼容既有流式 {@link TcpHandler} / {@link TcpMethod} / 默认回显处理。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // 帧式协议（实现 TcpServer，RPC 等场景）
 * JdkTcpServer server = new JdkTcpServer(ServerSetting.defaults())
 *         .setHandler(bytes -> bytes);   // 帧处理
 * server.start();
 *
 * // 流式协议（兼容既有 TcpHandler / TcpMethod / 回显）
 * JdkTcpServer server = new JdkTcpServer(setting)
 *         .registerHandler("*", (in, out) -> {
 *             byte[] buffer = new byte[1024];
 *             int read = in.read(buffer);
 *             if (read > 0) {
 *                 out.write(("echo:" + new String(buffer, 0, read)).getBytes());
 *                 out.flush();
 *             }
 *         });
 * }</pre>
 *
 * @author CH
 * @since 2026/07/26
 */
@Slf4j
@Spi({"jdk-tcp", "tcp"})
public class JdkTcpServer extends AbstractServer implements TcpServer {

    /**
     * 长度头字节数
     */
    private static final int HEADER_SIZE = 4;

    /**
     * 消息体长度上限（字节），默认 8MB
     */
    private static final int MAX_BODY_SIZE = 8 * 1024 * 1024;

    /**
     * 回显缓冲（ThreadLocal 复用，避免每连接分配 8KB）
     */
    private static final ThreadLocal<byte[]> THREAD_LOCAL_BUFFER =
            ThreadLocal.withInitial(() -> new byte[8192]);

    /**
     * 服务器通道
     */
    private ServerSocketChannel serverChannel;

    /**
     * 接收连接用 Selector（专用线程）
     */
    private Selector acceptSelector;

    /**
     * IO Selector 数组（多线程分发读事件）
     */
    private Selector[] ioSelectors;

    /**
     * IO Selector 线程数组
     */
    private Thread[] ioThreads;

    /**
     * 下一个 IO Selector 分配游标（轮询注册新连接）
     */
    private final AtomicInteger ioCursor = new AtomicInteger();

    /**
     * IO Selector 线程数
     */
    private int ioThreadsCount = 0;

    /**
     * 接收连接专用线程
     */
    private Thread acceptThread;

    /**
     * 流式处理虚拟线程池（流式协议模式）
     */
    private ExecutorService virtualPool;

    /**
     * 帧式处理器（{@link TcpServer} 接口）
     */
    private volatile TcpServerHandler frameHandler;

    /**
     * 流加密密钥（doStart 时由 encryptKey 派生缓存；null 表示未启用加密，连接路径零开销）
     */
    private volatile SecretKeySpec encryptKeySpec;

    /**
     * 流式处理器表（兼容既有 API）
     */
    private final Map<String, TcpHandler> handlers = new ConcurrentHashMap<>();

    /**
     * 粘包处理配置：是否启用固定长度帧解析
     */
    private boolean fixedLengthEnabled = false;

    /**
     * 固定长度帧大小（字节）
     */
    private int fixedLength = 0;

    /**
     * 长度字段偏移量（从 0 开始）
     */
    private int lengthFieldOffset = 0;

    /**
     * 长度字段占用字节数（1, 2, 4, 8）
     */
    private int lengthFieldLength = 4;

    /**
     * 长度调整值（帧长度 = 读取的长度 + 此值）
     */
    private int lengthAdjustment = 0;

    /**
     * 初始跳过字节数
     */
    private int lengthIncludesHeaderCount = 1;

    /**
     * 创建 JdkTcpServer 实例
     * @param setting setting
     */
    public JdkTcpServer(ServerSetting setting) {
        super(setting);
    }

    /**
     * 设置 IO Selector 线程数。
     *
     * @param ioThreads IO Selector 线程数，小于等于 0 时使用默认 CPU 核数
     * @return 当前服务器实例，支持链式调用
     */
    public JdkTcpServer setIoThreads(int ioThreads) {
        this.ioThreadsCount = ioThreads;
        return this;
    }

    /**
     * 设置固定长度帧解析。
     *
     * @param length 每条消息的固定字节数
     * @return 当前服务器实例，支持链式调用
     */
    public JdkTcpServer setPacketLength(int length) {
        this.fixedLengthEnabled = true;
        this.fixedLength = length;
        return this;
    }

    /**
     * 设置长度字段偏移量。
     *
     * @param offset 长度字段在帧中的起始位置（从 0 开始）
     * @return 当前服务器实例，支持链式调用
     */
    public JdkTcpServer setLengthFieldOffset(int offset) {
        this.lengthFieldOffset = offset;
        return this;
    }

    /**
     * 设置长度字段占用字节数。
     *
     * @param length 长度字段字节数（1, 2, 4, 8）
     * @return 当前服务器实例，支持链式调用
     */
    public JdkTcpServer setLengthFieldLength(int length) {
        this.lengthFieldLength = length;
        return this;
    }

    /**
     * 设置长度调整值。
     *
     * @param adjustment 帧长度 = 读取的长度 + 此值
     * @return 当前服务器实例，支持链式调用
     */
    public JdkTcpServer setLengthAdjustment(int adjustment) {
        this.lengthAdjustment = adjustment;
        return this;
    }

    /**
     * 设置初始跳过字节数。
     *
     * @param count 初始跳过的字节数
     * @return 当前服务器实例，支持链式调用
     */
    public JdkTcpServer setLengthIncludesHeaderCount(int count) {
        this.lengthIncludesHeaderCount = count;
        return this;
    }

    @Override
    /** 设置Handler */
    public JdkTcpServer setHandler(TcpServerHandler handler) {
        this.frameHandler = handler;
        String p = System.getProperty("java.io.tmpdir") + "\\tcp-debug.log";
        try { java.nio.file.Files.writeString(java.nio.file.Paths.get(p), "setHandler called, frameHandler=" + (handler != null) + " this=" + System.identityHashCode(this) + "\n", java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING); } catch (Exception ignored) {}
        return this;
    }

    @Override
    /** Do开始 */
    protected void doStart() {
        try {
            InetSocketAddress addr = new InetSocketAddress(setting.getHost(), setting.getPort());
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, setting.isSoReuseAddr());
            serverChannel.bind(addr, Math.max(setting.getBacklog(), 2048));
            // 回填实际端口（port=0 时由系统分配）
            setting.setPort(serverChannel.socket().getLocalPort());

            // 流加密密钥：启动时派生一次并缓存，避免每连接重复 SHA-256
            if (setting.isEncrypt()) {
                String encryptKey = setting.getEncryptKey();
                if (encryptKey == null || encryptKey.isBlank()) {
                    log.warn("TCP 加密已启用但未配置 encryptKey，连接将按明文处理");
                } else {
                    encryptKeySpec = AesGcmUtils.deriveKey(encryptKey);
                    if (frameHandler != null) {
                        log.warn("TCP 加密仅支持流式协议，当前为帧式(NIO)协议，加密未生效");
                    }
                }
            }

            acceptSelector = Selector.open();
            serverChannel.register(acceptSelector, SelectionKey.OP_ACCEPT);

            // 必须先置运行标志再启动线程，否则 IO/接收线程读到 false 立即退出
            running = true;

            // IO Selector 线程数：优先显式 setIoThreads，其次按 CPU 核数扩展(至少4,充分并行读/拼帧)
            int ioCount = ioThreadsCount > 0 ? ioThreadsCount : setting.getIoThreads();
            if (ioCount <= 0) {
                ioCount = Math.max(4, Runtime.getRuntime().availableProcessors());
            }
            ioThreadsCount = ioCount;

            ioSelectors = new Selector[ioThreadsCount];
            ioThreads = new Thread[ioThreadsCount];
            for (int i = 0; i < ioThreadsCount; i++) {
                final Selector ioSelector = Selector.open();
                ioSelectors[i] = ioSelector;
                final int idx = i;
                ioThreads[i] = new Thread(() -> ioEventLoop(ioSelector), "jdk-tcp-io-" + idx);
                ioThreads[i].setDaemon(true);
                ioThreads[i].start();
            }

            // 虚拟线程池处理连接/请求业务（高并发，JDK21+）
            virtualPool = Executors.newVirtualThreadPerTaskExecutor();

            acceptThread = new Thread(this::acceptLoop, "jdk-tcp-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            log.info("JDK TcpServer started on {}:{} (ioThreads={}, workers={}, nio=true)",
                    setting.getHost(), setting.getPort(), ioThreadsCount, setting.getWorkerThreads());
        } catch (IOException e) {
            throw new RuntimeException("TCP 服务器启动失败", e);
        }
    }

    @Override
    /** Do停止 */
    protected void doStop() {
        running = false;
        try {
            if (acceptSelector != null) {
                acceptSelector.wakeup();
                acceptSelector.close();
            }
        } catch (IOException ignored) {
        }
        if (ioSelectors != null) {
            for (Selector ioSelector : ioSelectors) {
                try {
                    if (ioSelector != null) {
                        ioSelector.wakeup();
                        ioSelector.close();
                    }
                } catch (IOException ignored) {
                }
            }
        }
        try {
            if (serverChannel != null) {
                serverChannel.close();
            }
        } catch (IOException ignored) {
        }
        ThreadUtils.closeQuietly(virtualPool);
        log.info("JDK TcpServer stopped");
    }

    @Override
    /** 获取ProtocolType */
    public ProtocolType getProtocolType() {
        return ProtocolType.TCP;
    }

    /**
     * 接收连接专用线程：accept 后根据处理模式分发。
     * <p>帧式协议（注册了 {@link TcpServerHandler}）走 NIO Reactor 拼帧；
     * 流式协议（{@link TcpHandler} / 默认回显）走虚拟线程阻塞处理。</p>
     */
    private void acceptLoop() {
        while (running) {
            try {
                acceptSelector.select(1000);
                Set<SelectionKey> keys = acceptSelector.selectedKeys();
                Iterator<SelectionKey> it = keys.iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isAcceptable()) {
                        doAccept(key);
                    }
                }
            } catch (ClosedSelectorException e) {
                break;
            } catch (IOException e) {
                if (running) {
                    log.error("接受连接异常", e);
                }
            }
        }
    }

    /**
     * IO Selector 线程：只负责读事件分发与拼帧，业务处理交给 Worker 线程池。
     *
     * @param ioSelector 该线程专属的 Selector
     */
    private void ioEventLoop(Selector ioSelector) {
        while (running) {
            try {
                ioSelector.select(1000);
                Set<SelectionKey> keys = ioSelector.selectedKeys();
                Iterator<SelectionKey> it = keys.iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isReadable()) {
                        doRead(key);
                    }
                }
            } catch (ClosedSelectorException e) {
                break;
            } catch (IOException e) {
                log.error("IO selector error", e);
            }
        }
    }

    /** DoAccept */
    private void doAccept(SelectionKey key) throws IOException {
        SocketChannel sc = ((ServerSocketChannel) key.channel()).accept();
        if (sc == null) {
            return;
        }
        String debugPath = System.getProperty("java.io.tmpdir") + "\\tcp-debug.log";
        try { java.nio.file.Files.writeString(java.nio.file.Paths.get(debugPath), "doAccept frameHandler=" + (frameHandler != null) + " this=" + System.identityHashCode(this) + "\n", java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND); } catch (Exception ignored) {}
        if (frameHandler != null) {
            // 帧式协议：注册到 IO Selector，NIO 拼帧（加密告警已在 doStart 统一提示）
            sc.configureBlocking(false);
            Selector ioSelector = ioSelectors[Math.floorMod(ioCursor.getAndIncrement(), ioSelectors.length)];
            sc.register(ioSelector, SelectionKey.OP_READ, new Attachment(ioSelector));
        } else {
            // 流式协议：虚拟线程阻塞处理
            Socket socket = sc.socket();
            socket.setTcpNoDelay(setting.isTcpNoDelay());
            try {
                virtualPool.submit(() -> handleConnection(socket));
            } catch (Exception e) {
                log.warn("TCP 任务被拒绝: {}", e.getMessage());
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Do读取 */
    private void doRead(SelectionKey key) throws IOException {
        SocketChannel sc = (SocketChannel) key.channel();
        Attachment att = (Attachment) key.attachment();
        if (readFrame(sc, att)) {
            // 拷贝 body 数据后 reset:避免同一连接下一帧复用 bodyBuf 底层数组覆盖上一帧数据
            byte[] body = Arrays.copyOf(att.bodyBuf.array(), att.bodyLen);
            att.reset();
            virtualPool.execute(() -> processRequest(sc, body, att));
        }
    }

    /** 读取Frame */
    private boolean readFrame(SocketChannel sc, Attachment att) throws IOException {
        if (att.state == State.HEADER) {
            // 只在起始位置清空 header，避免半包场景下把已读字节清掉导致数据丢失
            if (att.headerBuf.position() == 0) {
                att.headerBuf.clear();
            }
            int r = sc.read(att.headerBuf);
            if (r == -1) {
                closeChannel(keyFor(sc, att));
                return false;
            }
            if (att.headerBuf.position() < HEADER_SIZE) {
                return false;
            }
            att.headerBuf.flip();
            att.bodyLen = att.headerBuf.getInt();
            if (att.bodyLen <= 0 || att.bodyLen > MAX_BODY_SIZE) {
                closeChannel(keyFor(sc, att));
                throw new IOException("Invalid body length: " + att.bodyLen);
            }
            att.bodyBuf = ByteBuffer.allocate(att.bodyLen);
            att.state = State.BODY;
        }
        int r = sc.read(att.bodyBuf);
        if (r == -1) {
            closeChannel(keyFor(sc, att));
            return false;
        }
        return !att.bodyBuf.hasRemaining();
    }

    /** KeyFor */
    private SelectionKey keyFor(SocketChannel sc, Attachment att) {
        return sc.keyFor(att.ioSelector);
    }

    /** 处理Request */
    private void processRequest(SocketChannel sc, byte[] reqData, Attachment att) {
        // 写日志验证本方法是否被调用（使用绝对路径避免工作目录问题）
        String debugPath = System.getProperty("java.io.tmpdir") + "\\tcp-debug.log";
        try { java.nio.file.Files.writeString(java.nio.file.Paths.get(debugPath), "processRequest called\n", java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING); } catch (Exception e) { System.err.println("WRITE_ERR: " + e); }
        boolean hasUrlMapping = urlMappingFilter != null && urlMappingFilter.getFactory().routeCount() > 0;
        try { java.nio.file.Files.writeString(java.nio.file.Paths.get(debugPath), "hasUrlMapping=" + hasUrlMapping + " frameHandler=" + (frameHandler != null) + "\n", java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND); } catch (Exception ignored) {}
        if (frameHandler == null || !hasUrlMapping) {
            // 无 URL 路由时走旧帧式路径（零开销）
            try { java.nio.file.Files.writeString(java.nio.file.Paths.get(debugPath), "[OLD_PATH]\n", java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND); } catch (Exception ignored) {}
            try {
                byte[] respData = frameHandler.handle(reqData);
                if (respData != null) {
                    writeResponse(sc, respData);
                }
            } catch (Exception e) {
                log.error("Process request error", e);
                closeChannel(keyFor(sc, att));
            }
            return;
        }
        // URL 路由模式：将帧体解析为 HTTP 请求，走完整 Filter Chain
        try { java.nio.file.Files.writeString(java.nio.file.Paths.get("tcp-debug.log"), "[URL_PATH] entering\n", java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND); } catch (Exception ignored) {}
        InetSocketAddress remoteAddr = null;
        try {
            java.net.Socket socket = sc.socket();
            remoteAddr = (InetSocketAddress) socket.getRemoteSocketAddress();
        } catch (Exception ignored) {
        }
        TcpServerRequest request = new TcpServerRequest(reqData, remoteAddr, StandardCharsets.UTF_8);
        TcpServerResponse response = new TcpServerResponse();
        try {
            handleRequest(request, response);
        } catch (Exception e) {
            log.error("Process request error", e);
            closeChannel(keyFor(sc, att));
            return;
        }
        if (!response.isEnded()) {
            response.end();
        }
        byte[] respFrame = response.getReadyBytes();
        if (respFrame != null && respFrame.length > 0) {
            writeResponse(sc, respFrame);
        } else {
            writeEmptyResponse(sc);
        }
    }

    /**
     * 写回空响应（200 + Content-Length: 0）。
     */
    private void writeEmptyResponse(SocketChannel sc) {
        try {
            String resp = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n";
            byte[] bytes = resp.getBytes(StandardCharsets.US_ASCII);
            ByteBuffer buf = ByteBuffer.allocate(4 + bytes.length);
            buf.putInt(bytes.length);
            buf.put(bytes);
            buf.flip();
            while (buf.hasRemaining()) {
                int written = sc.write(buf);
                if (written <= 0) {
                    Thread.yield();
                }
            }
        } catch (Exception e) {
            log.debug("写空响应失败: {}", e.getMessage());
        }
    }

    /**
     * 将响应帧完整写入通道。通道为非阻塞模式，必须循环写入直至缓冲区耗尽，避免粘包/半包。
     *
     * @param sc       目标通道
     * @param respData 响应帧字节（不含长度头）
     */
    private void writeResponse(SocketChannel sc, byte[] respData) {
        try {
            if (respData.length > MAX_BODY_SIZE) {
                log.warn("Response too large: {}", respData.length);
                return;
            }
            ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + respData.length);
            buf.putInt(respData.length);
            buf.put(respData);
            buf.flip();
            while (buf.hasRemaining()) {
                int written = sc.write(buf);
                if (written <= 0) {
                    // 非阻塞模式下无更多空间，短暂让步避免忙等
                    Thread.yield();
                }
            }
        } catch (Exception e) {
            log.error("Write response error", e);
        }
    }

    /** 关闭Channel */
    private void closeChannel(SelectionKey key) {
        if (key != null) {
            try {
                key.channel().close();
            } catch (IOException ignored) {
            }
            key.cancel();
        }
    }

    /** 处理Connection */
    private void handleConnection(Socket socket) {
        String clientKey = socket.getRemoteSocketAddress().toString();
        log.debug("TCP 连接: {}", clientKey);

        try (InputStream rawIn = socket.getInputStream();
             OutputStream rawOut = socket.getOutputStream()) {
            InputStream in = rawIn;
            OutputStream out = rawOut;
            // 快路径：未启用加密时仅一次 null 判断，零额外开销
            if (encryptKeySpec != null) {
                in = AesGcmUtils.decrypting(rawIn, encryptKeySpec);
                out = AesGcmUtils.encrypting(rawOut, encryptKeySpec);
            }

            TcpHandler handler = findHandler(clientKey);
            if (handler != null) {
                if (fixedLengthEnabled && handler instanceof TcpMethod) {
                    ((TcpMethod) handler).handle(in, out);
                } else {
                    handler.handle(in, out);
                }
            } else {
                // 默认处理：回显（去 flush + ThreadLocal 缓冲复用，避免每次写刷 OS 缓冲与每连接分配）
                byte[] buffer = THREAD_LOCAL_BUFFER.get();
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                out.flush();
            }
        } catch (Exception e) {
            log.debug("TCP 连接处理异常: {}", e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 查找Handler */
    private TcpHandler findHandler(String clientKey) {
        TcpHandler handler = handlers.get(clientKey);
        if (handler != null) {
            return handler;
        }
        for (Map.Entry<String, TcpHandler> entry : handlers.entrySet()) {
            if ("*".equals(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 注册 TCP 处理器。
     *
     * @param name    处理器名称（"*" 表示匹配所有连接）
     * @param handler 处理器
     * @return 当前服务器实例，支持链式调用
     */
    public JdkTcpServer registerHandler(String name, TcpHandler handler) {
        handlers.put(name, handler);
        return this;
    }

    /**
     * 接收固定长度的数据帧（处理粘包）。
     *
     * @param in   输入流
     * @param size 期望的帧大小
     * @return 完整的帧数据，如果流结束返回 null
     * @throws IOException IO 异常
     */
    public static byte[] receiveFrame(InputStream in, int size) throws IOException {
        byte[] frame = new byte[size];
        int offset = 0;
        while (offset < size) {
            int read = in.read(frame, offset, size - offset);
            if (read == -1) {
                return null;
            }
            offset += read;
        }
        return frame;
    }

    /**
     * 静态重载：使用显式配置参数接收带长度字段的帧（接口默认方法可用）。
     *
     * @param in                       输入流
     * @param lengthFieldOffset        长度字段偏移
     * @param lengthFieldLength        长度字段长度
     * @param lengthAdjustment         长度调整
     * @param lengthIncludesHeaderCount 是否包含头部长度（1/0）
     * @return 完整的帧数据
     * @throws IOException IO 异常
     */
    public static byte[] receiveFrame(InputStream in, int lengthFieldOffset, int lengthFieldLength,
                                       int lengthAdjustment, int lengthIncludesHeaderCount) throws IOException {
        byte[] header = new byte[lengthFieldOffset + lengthFieldLength];
        int offset = 0;
        while (offset < header.length) {
            int read = in.read(header, offset, header.length - offset);
            if (read == -1) {
                return null;
            }
            offset += read;
        }

        int frameLength = parseLength(header, lengthFieldOffset, lengthFieldLength);
        frameLength += lengthAdjustment;

        int dataLength = frameLength - lengthIncludesHeaderCount;
        if (dataLength <= 0) {
            return null;
        }

        byte[] data = new byte[dataLength];
        int read = in.read(data);
        while (read < dataLength) {
            int n = in.read(data, read, dataLength - read);
            if (n == -1) {
                break;
            }
            read += n;
        }
        if (lengthIncludesHeaderCount == 1) {
            byte[] full = new byte[header.length + data.length];
            System.arraycopy(header, 0, full, 0, header.length);
            System.arraycopy(data, 0, full, header.length, data.length);
            return full;
        }
        return data;
    }

    /**
     * 接收带长度字段的帧（处理粘包）。
     *
     * @param in 输入流
     * @return 完整的帧数据，如果流结束返回 null
     * @throws IOException IO 异常
     */
    public byte[] receiveFrame(InputStream in) throws IOException {
        // 读取长度字段
        int headerSize = lengthIncludesHeaderCount;
        byte[] header = new byte[lengthFieldOffset + lengthFieldLength];
        int offset = 0;
        while (offset < header.length) {
            int read = in.read(header, offset, header.length - offset);
            if (read == -1) {
                return null;
            }
            offset += read;
        }

        // 解析长度字段
        int frameLength = parseLength(header, lengthFieldOffset, lengthFieldLength);
        frameLength += lengthAdjustment;

        // 读取帧数据（减去头部）
        int dataLength = frameLength - headerSize;
        if (dataLength <= 0) {
            return null;
        }

        byte[] data = new byte[dataLength];
        offset = 0;
        while (offset < dataLength) {
            int read = in.read(data, offset, dataLength - offset);
            if (read == -1) {
                return null;
            }
            offset += read;
        }

        // 返回完整帧（含头部）
        ByteBuffer buffer = ByteBuffer.allocate(headerSize + dataLength);
        buffer.put(header);
        buffer.put(data);
        return buffer.array();
    }

    /**
     * 解析长度字段。
     *
     * @param bytes       包含长度字节的数组
     * @param offset      长度字段偏移量
     * @param fieldLength 长度字段字节数
     * @return 解析出的长度值
     */
    private static int parseLength(byte[] bytes, int offset, int fieldLength) {
        switch (fieldLength) {
            case 1:
                return bytes[offset] & 0xFF;
            case 2:
                return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
            case 4:
                return ((bytes[offset] & 0xFF) << 24) |
                        ((bytes[offset + 1] & 0xFF) << 16) |
                        ((bytes[offset + 2] & 0xFF) << 8) |
                        (bytes[offset + 3] & 0xFF);
            default:
                throw new IllegalArgumentException("Unsupported length field length: " + fieldLength);
        }
    }

    /**
     * 注册 TCP 处理器（链式调用）。
     *
     * @param handler 处理器
     * @return 当前服务器实例
     */
    public JdkTcpServer handler(TcpHandler handler) {
        return registerHandler("*", handler);
    }

    /**
     * TCP 处理器接口。
     */
    @FunctionalInterface
    public interface TcpHandler {
        /**
         * 处理 TCP 连接。
         *
         * @param in  输入流
         * @param out 输出流
         */
        void handle(InputStream in, OutputStream out) throws Exception;
    }

    /**
     * TCP 帧处理方法（支持粘包处理）。
     *
     * <p>当启用了粘包处理时，使用此接口代替 TcpHandler。
     * 可以通过 {@link #receiveFrame} 或 {@link #receiveFrame(InputStream, int)} 方法获取完整的数据帧。</p>
     *
     * <h3>示例：固定长度协议</h3>
     * <pre>{@code
     * server.setPacketLength(10)
     *       .handler(new TcpMethod() {
     *           @Override
     *           public void handle(InputStream in, OutputStream out) throws Exception {
     *               byte[] msg;
     *               while ((msg = receiveFrame(in, 10)) != null) {
     *                   out.write(msg);
     *                   out.flush();
     *               }
     *           }
     *       });
     * }</pre>
     *
     * <h3>示例：长度字段协议</h3>
     * <pre>{@code
     * server.setLengthFieldOffset(0)
     *       .setLengthFieldLength(2)
     *       .handler(new TcpMethod() {
     *           @Override
     *           public void handle(InputStream in, OutputStream out) throws Exception {
     *               byte[] frame;
     *               while ((frame = receiveFrame(in)) != null) {
     *                   // frame 包含头部 + 数据
     *                   out.write(frame);
     *                   out.flush();
     *               }
     *           }
     *       });
     * }</pre>
     */
    public interface TcpMethod extends TcpHandler {
        /**
         * 接收固定长度的数据帧。
         *
         * @param in   输入流
         * @param size 期望的帧大小
         * @return 完整的帧数据
         * @throws IOException IO 异常
         */
        default byte[] receiveFrame(InputStream in, int size) throws IOException {
            return JdkTcpServer.receiveFrame(in, size);
        }

        /**
         * 接收带长度字段的帧。
         *
         * @param in 输入流
         * @return 完整的帧数据
         * @throws IOException IO 异常
         */
        default byte[] receiveFrame(InputStream in) throws IOException {
            return JdkTcpServer.receiveFrame(in, 0, 4, 0, 1);
        }
    }

    private enum State { HEADER, BODY }

    /**
     * 连接级粘包拼帧上下文。
     */
    private static class Attachment {

        /**
         * 长度头缓冲区
         */
        final ByteBuffer headerBuf = ByteBuffer.allocate(HEADER_SIZE);

        /**
         * 归属的 IO Selector（用于 keyFor 关闭连接时定位注册表）
         */
        final Selector ioSelector;

        /**
         * 当前拼帧阶段
         */
        State state = State.HEADER;

        /**
         * 消息体长度
         */
        int bodyLen;

        /**
         * 消息体缓冲区
         */
        ByteBuffer bodyBuf;

        /**
         * 创建连接级拼帧上下文。
         *
         * @param ioSelector 归属的 IO Selector
         */
        Attachment(Selector ioSelector) {
            this.ioSelector = ioSelector;
        }

        /**
         * 重置拼帧上下文，等待下一帧请求。
         */
        void reset() {
            headerBuf.clear();
            state = State.HEADER;
            bodyBuf = null;
        }
    }
}
