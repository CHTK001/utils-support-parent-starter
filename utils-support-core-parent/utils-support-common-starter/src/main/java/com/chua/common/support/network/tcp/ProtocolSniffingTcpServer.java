package com.chua.common.support.network.tcp;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.tcp.callback.TcpServerHandler;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 通用协议嗅探 TCP 服务器：单端口监听，根据连接头部字节识别协议并分流到注册的协议处理器。
 *
 * <p>典型应用：sip 认证信令与 frp 内网穿透数据平面共用同一公网端口——
 * 连接建立后先窥探头部，命中 {@code AUTH|} / {@code CONNECT|} 前缀即交给
 * {@code SipProtocolSniffHandler} 处理（sip 认证 + 内网穿透隧道），
 * 其他协议可继续注册各自的嗅探处理器共存于同一端口。</p>
 *
 * <p>识别流程：</p>
 * <ol>
 *   <li>接受连接后读取最多 {@code peekSize} 字节作为头部（读取期间受
 *       {@code detectTimeoutMs} 超时保护，防止空连接占资源）</li>
 *   <li>按注册顺序查找首个 {@link ProtocolSniffHandler#matches(byte[])} 命中的处理器</li>
 *   <li>若当前数据不足但某处理器 {@link ProtocolSniffHandler#isPrefix(byte[])} 判定为
 *       其前缀，则继续读取直至可判定或达到嗅探上限</li>
 *   <li>将已窥探字节回推到输入流、恢复连接读写超时，交给命中的处理器独占处理</li>
 *   <li>未识别协议时关闭连接（或交由 {@link #setFallbackHandler} 兜底处理）</li>
 * </ol>
 *
 * <p>使用方式：</p>
 * <pre>{@code
 * ProtocolSniffingTcpServer server = new ProtocolSniffingTcpServer("0.0.0.0", 19460)
 *         .register(new SipProtocolSniffHandler(sipServer))
 *         .register(new HttpProtocolSniffHandler(...));
 * server.start();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
*/
@Slf4j
public class ProtocolSniffingTcpServer extends AbstractServer implements TcpServer {

    /**
    * 默认头部窥探字节上限
    */
    private static final int DEFAULT_PEEK_SIZE = 64;

    /**
    * 默认协议识别超时（毫秒）
    */
    private static final int DEFAULT_DETECT_TIMEOUT_MS = 3000;

    /**
    * 已注册的协议嗅探处理器（按注册顺序匹配，先注册者优先）
    */
    private final List<ProtocolSniffHandler> handlers = new CopyOnWriteArrayList<>();

    /**
    * 底层监听 Socket
    */
    private ServerSocket serverSocket;

    /**
    * 接受连接线程
    */
    private Thread acceptThread;

    /**
    * 连接处理虚拟线程池
    */
    private ExecutorService virtualPool;

    /**
    * 头部窥探字节上限
    */
    private int peekSize = DEFAULT_PEEK_SIZE;

    /**
    * 协议识别超时（毫秒）
    */
    private int detectTimeoutMs = DEFAULT_DETECT_TIMEOUT_MS;

    /**
    * 未识别协议时的兜底处理器（为 null 时直接关闭连接）
    */
    private ProtocolSniffHandler fallbackHandler;

    /**
    * 使用默认配置创建协议嗅探 TCP 服务器。
    */
    public ProtocolSniffingTcpServer() {
        this(ServerSetting.defaults());
    }

    /**
    * 使用指定监听地址创建协议嗅探 TCP 服务器。
    *
    * @param host 监听主机
    * @param port 监听端口
    */
    public ProtocolSniffingTcpServer(String host, int port) {
        ServerSetting setting = ServerSetting.defaults();
        setting.setHost(host);
        setting.setPort(port);
        setting.setProtocol("tcp");
        this(setting);
    }

    /**
    * 使用指定配置创建协议嗅探 TCP 服务器。
    *
    * @param setting 服务器配置
    */
    public ProtocolSniffingTcpServer(ServerSetting setting) {
        super(setting);
    }

    /**
    * 注册协议嗅探处理器，按注册顺序匹配（先注册者优先）。
    *
    * @param handler 协议处理处理器
    * @return 当前服务器实例，支持链式调用
    */
    public ProtocolSniffingTcpServer register(ProtocolSniffHandler handler) {
        if (handler != null) {
            handlers.add(handler);
        }
        return this;
    }

    /**
    * 设置头部窥探字节上限。
    *
    * @param peekSize 头部窥探字节上限，应不小于各协议识别前缀长度
    * @return 当前服务器实例，支持链式调用
    */
    public ProtocolSniffingTcpServer setPeekSize(int peekSize) {
        this.peekSize = Math.max(1, peekSize);
        return this;
    }

    /**
    * 设置协议识别超时（毫秒）。
    *
    * @param detectTimeoutMs 超时毫秒数，连接超时未发数据则关闭
    * @return 当前服务器实例，支持链式调用
    */
    public ProtocolSniffingTcpServer setDetectTimeoutMs(int detectTimeoutMs) {
        this.detectTimeoutMs = detectTimeoutMs;
        return this;
    }

    /**
    * 设置未识别协议时的兜底处理器。
    *
    * @param handler 兜底处理处理器，为 null 时直接关闭未识别连接
    * @return 当前服务器实例，支持链式调用
    */
    public ProtocolSniffingTcpServer setFallbackHandler(ProtocolSniffHandler handler) {
        this.fallbackHandler = handler;
        return this;
    }

    /**
    * 启动服务器的具体逻辑：绑定监听端口并启动接受线程。
    */
    @Override
    protected void doStart() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(setting.getHost(), setting.getPort()),
                    Math.max(setting.getBacklog(), 128));
            setting.setPort(serverSocket.getLocalPort());
            virtualPool = Executors.newVirtualThreadPerTaskExecutor();
            acceptThread = ThreadUtils.newThread(this::acceptLoop, "protocol-sniff-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            log.info("协议嗅探 TCP 服务器启动成功: {}:{} (peek={}, timeout={}ms, handlers={})",
                    setting.getHost(), setting.getPort(), peekSize, detectTimeoutMs, handlers.size());
        } catch (IOException e) {
            throw new RuntimeException("协议嗅探 TCP 服务器监听失败: " + setting.getPort(), e);
        }
    }

    /**
    * 停止服务器的具体逻辑：关闭监听与连接处理线程池。
    */
    @Override
    protected void doStop() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        ThreadUtils.closeQuietly(virtualPool);
        log.info("协议嗅探 TCP 服务器已停止");
    }

    /**
    * 帧处理器注册（TcpServer 接口，协议嗅探服务器使用流式协议，此处无操作）。
    *
    * @param handler 帧处理器
    * @return 当前实例
    */
    @Override
    public ProtocolSniffingTcpServer setHandler(TcpServerHandler handler) {
        return this;
    }

    /**
    * 获取协议类型。
    *
    * @return 协议类型
    */
    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.TCP;
    }

    /**
    * 接受连接循环：每个连接交给虚拟线程执行协议嗅探与分流。
    */
    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                virtualPool.execute(() -> handleConnection(socket));
            } catch (IOException e) {
                if (running) {
                    log.debug("协议嗅探服务器接受连接异常: {}", e.getMessage());
                }
            }
        }
    }

    /**
    * 处理一条连接：窥探头部、识别协议、回推字节后交给目标处理器独占处理。
    *
    * @param socket 连接
    */
    private void handleConnection(Socket socket) {
        try (InputStream rawIn = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {
            PushbackInputStream in = new PushbackInputStream(rawIn, peekSize);
            socket.setSoTimeout(detectTimeoutMs);
            byte[] head = new byte[peekSize];
            int headLen = readHeader(in, head);
            ProtocolSniffHandler matched = headLen > 0 ? match(Arrays.copyOf(head, headLen)) : null;
            if (matched == null) {
                handleUnknown(socket, in, out, head, headLen);
                return;
            }
            if (headLen > 0) {
                in.unread(head, 0, headLen);
            }
            socket.setSoTimeout(0);
            matched.handle(in, out);
        } catch (SocketTimeoutException e) {
            log.debug("协议嗅探超时，关闭连接: remote={}", socket.getRemoteSocketAddress());
        } catch (IOException e) {
            log.debug("协议嗅探连接处理异常: {}", e.getMessage());
        } catch (Exception e) {
            log.debug("协议处理器执行异常: {}", e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
    * 读取并识别协议头部：持续读取直到命中某个处理器或判定未知，避免 TCP 分段导致误判。
    *
    * @param in   输入流（含识别超时）
    * @param head 头部缓冲
    * @return 已读取的头部字节数
    * @throws IOException IO 异常
    */
    private int readHeader(PushbackInputStream in, byte[] head) throws IOException {
        int headLen = 0;
        while (headLen < peekSize) {
            int read = in.read(head, headLen, head.length - headLen);
            if (read == -1) {
                break;
            }
            headLen += read;
            byte[] partial = Arrays.copyOf(head, headLen);
            if (match(partial) != null) {
                break;
            }
            if (!hasPendingPrefix(partial)) {
                break;
            }
        }
        return headLen;
    }

    /**
    * 处理未识别协议：有兜底处理器则转交，否则关闭连接。
    *
    * @param socket  连接
    * @param in      输入流（含已窥探头部）
    * @param out     输出流
    * @param head    已窥探头部字节
    * @param headLen 已窥探头部长度
    */
    private void handleUnknown(Socket socket, InputStream in, OutputStream out, byte[] head, int headLen) {
        if (fallbackHandler != null) {
            try {
                socket.setSoTimeout(0);
                fallbackHandler.handle(in, out);
                return;
            } catch (Exception e) {
                log.debug("兜底处理器异常: {}", e.getMessage());
            }
        }
        log.warn("未识别协议，关闭连接: remote={}, head={}", socket.getRemoteSocketAddress(),
                new String(head, 0, headLen, StandardCharsets.UTF_8));
    }

    /**
    * 按注册顺序查找首个匹配的协议嗅探处理器。
    *
    * @param head 已窥探的头部字节
    * @return 匹配的处理器，未匹配返回 null
    */
    private ProtocolSniffHandler match(byte[] head) {
        for (ProtocolSniffHandler handler : handlers) {
            if (handler.matches(head)) {
                return handler;
            }
        }
        return null;
    }

    /**
    * 判断当前头部字节是否为某个已注册协议前缀的前缀（数据不足，仍需继续读取）。
    *
    * @param head 已窥探的头部字节
    * @return true 表示仍可能是某个协议的前缀，可继续读取
    */
    private boolean hasPendingPrefix(byte[] head) {
        for (ProtocolSniffHandler handler : handlers) {
            if (handler.isPrefix(head)) {
                return true;
            }
        }
        return false;
    }
}
