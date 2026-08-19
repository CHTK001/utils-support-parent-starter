package com.chua.common.support.network.sip;

import com.chua.common.support.lang.algorithm.hmac.HMacUtils;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * SIP 单端口客户端：先认证换取会话 token，再持 token 建隧道数据连接。
 *
 * <p>工作流程：</p>
 * <ol>
 *   <li>{@link #connect()} 建立信令长连接并完成 {@code AUTH} 认证，串换 {@code token}</li>
 *   <li>{@link #registerTunnel(String)} 注册本端对外暴露的服务</li>
 *   <li>{@link #openTunnel(String)} 请求建立隧道，服务端分配 channelId 并通知双方</li>
 *   <li>隧道确认后自动建立 {@code CONNECT} 数据连接，进入裸字节流透传</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SipClient {

    /**
     * 默认超时时间（毫秒）
     */
    public static final long DEFAULT_TIMEOUT_MS = 5000L;

    /**
     * 连接 ID
     */
    private final String clientId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    /**
     * 服务器地址
     */
    private final String serverHost;

    /**
     * 服务器端口
     */
    private final int serverPort;

    /**
     * 认证令牌（初始共享密钥，与 SipServer 相同）
     */
    private volatile String token = "";

    /**
     * 会话令牌（认证成功后由服务端颁发）
     */
    private volatile String sessionToken;

    /**
     * 信令连接
     */
    private volatile Socket signalSocket;

    /**
     * 信令输出
     */
    private volatile PrintWriter signalWriter;

    /**
     * 待处理的隧道开启请求映射（requestId -> Future）
     */
    private final Map<String, CompletableFuture<SipTunnelSession>> pendingTunnels = new ConcurrentHashMap<>();

    /**
     * 活动隧道会话集合（channelId -> 会话）
     */
    private final Map<String, SipTunnelSession> openTunnels = new ConcurrentHashMap<>();

    /**
     * 隧道开启请求监听器列表（作为服务提供方接收：channelId, serviceName）
     */
    private final List<BiConsumer<String, String>> tunnelOpenListeners = new CopyOnWriteArrayList<>();

    /**
     * 是否已连接
     */
    private volatile boolean connected;

    /**
     * 创建 SIP 客户端。
     *
     * @param url 服务器地址，如 tcp://127.0.0.1:19460
     */
    public SipClient(String url) {
        String addr = url.replaceFirst("^\\w+://", "");
        int colon = addr.lastIndexOf(':');
        this.serverHost = colon > 0 ? addr.substring(0, colon) : "127.0.0.1";
        this.serverPort = colon > 0 ? Integer.parseInt(addr.substring(colon + 1)) : 19460;
    }

    /**
     * 创建基于 TCP 传输的 SIP 客户端。
     *
     * @param url 服务端地址，如 tcp://127.0.0.1:19460
     * @return SIP 客户端
     */
    public static SipClient tcp(String url) {
        return new SipClient(url);
    }

    /**
     * 设置认证令牌（初始共享密钥，注册与数据平面握手签名共用）。
     *
     * @param token 认证令牌
     * @return 当前客户端实例，支持链式调用
     */
    public SipClient setToken(String token) {
        this.token = token != null ? token : "";
        return this;
    }

    /**
     * 连接服务器并完成认证换 token。
     *
     * @return 当前客户端实例，支持链式调用
     */
    public SipClient connect() {
        if (connected) {
            return this;
        }
        try {
            Socket socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(serverHost, serverPort), 5000);
            this.signalSocket = socket;
            this.signalWriter = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            // 认证握手：AUTH|clientId|host|port|signature
            String signature = HMacUtils.hmacSha256Hex(token, clientId + serverHost + serverPort);
            PrintWriter writer = signalWriter;
            writer.println(SipProtocol.line(SipProtocol.PREFIX_AUTH, clientId, serverHost, String.valueOf(serverPort), signature));
            writer.flush();
            // 读响应，等待 TOKEN
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null || !line.startsWith(SipProtocol.PREFIX_TOKEN + SipProtocol.SEPARATOR)) {
                socket.close();
                throw new IllegalStateException("SIP 认证失败: " + line);
            }
            this.sessionToken = line.substring(SipProtocol.PREFIX_TOKEN.length() + 1);
            connected = true;
            startSignalReader(reader);
            log.info("SIP 客户端认证成功: {} @ {}:{}", clientId, serverHost, serverPort);
        } catch (IOException e) {
            throw new RuntimeException("SIP 客户端连接失败: " + serverHost + ":" + serverPort, e);
        }
        return this;
    }

    /**
     * 启动信令读取线程。
     *
     * @param reader 输入
     */
    private void startSignalReader(BufferedReader reader) {
        ThreadUtils.newThread(() -> {
            try {
                String line;
                while (connected && (line = reader.readLine()) != null) {
                    handleSignal(line);
                }
            } catch (IOException e) {
                if (connected) {
                    log.debug("SIP 信令读取中断: {}", e.getMessage());
                }
            } finally {
                connected = false;
            }
        }, "sip-signal-" + clientId).start();
    }

    /**
     * 处理服务端下发的信令行。
     *
     * @param line 信令行
     */
    private void handleSignal(String line) {
        try {
            if (line.startsWith(SipProtocol.PREFIX_SERVICE_OK + SipProtocol.SEPARATOR)) {
                log.debug("SIP 服务注册成功: {}", line.substring(SipProtocol.PREFIX_SERVICE_OK.length() + 1));
            } else if (line.startsWith(SipProtocol.PREFIX_OPENED + SipProtocol.SEPARATOR)) {
                handleOpened(line);
            } else if (line.startsWith(SipProtocol.PREFIX_TUNNEL_OPEN + SipProtocol.SEPARATOR)) {
                handleTunnelOpenRequest(line);
            } else if (line.startsWith(SipProtocol.PREFIX_ERROR + SipProtocol.SEPARATOR)) {
                handleError(line);
            } else if (line.startsWith(SipProtocol.PREFIX_CLOSE + SipProtocol.SEPARATOR)) {
                handleClose(line);
            } else {
                log.debug("忽略未知 SIP 信令: {}", line);
            }
        } catch (Exception e) {
            log.warn("SIP 信令解析异常: {}", e.getMessage());
        }
    }

    /**
     * 处理隧道开启成功（作为访问方收到通道标识）。
     *
     * @param line 报文内容（OPENED|requestId|channelId）
     */
    private void handleOpened(String line) {
        String[] parts = line.split("\\|", 3);
        String requestId = parts.length > 1 ? parts[1] : "";
        String channelId = parts.length > 2 ? parts[2] : "";
        CompletableFuture<SipTunnelSession> future = pendingTunnels.remove(requestId);
        if (future == null) {
            return;
        }
        SipTunnelSession session = new SipTunnelSession(this, channelId, "");
        openTunnels.put(channelId, session);
        connectDataStream(session, "visitor");
        future.complete(session);
    }

    /**
     * 处理隧道开启请求（作为服务提供方收到访问方的隧道请求）。
     *
     * @param line 报文内容（TUNNEL_OPEN|channelId|serviceName）
     */
    private void handleTunnelOpenRequest(String line) {
        String[] parts = line.split("\\|", 3);
        String channelId = parts.length > 1 ? parts[1] : "";
        String serviceName = parts.length > 2 ? parts[2] : "";
        SipTunnelSession session = new SipTunnelSession(this, channelId, serviceName);
        openTunnels.put(channelId, session);
        connectDataStream(session, "provider");
        for (BiConsumer<String, String> listener : tunnelOpenListeners) {
            try {
                listener.accept(channelId, serviceName);
            } catch (Exception e) {
                log.error("SIP 隧道开启监听器异常", e);
            }
        }
    }

    /**
     * 处理隧道错误。
     *
     * @param line 报文内容（ERROR|requestId|reason）
     */
    private void handleError(String line) {
        String[] parts = line.split("\\|", 3);
        String requestId = parts.length > 1 ? parts[1] : "";
        String reason = parts.length > 2 ? parts[2] : "";
        CompletableFuture<SipTunnelSession> future = pendingTunnels.remove(requestId);
        if (future != null) {
            future.completeExceptionally(new IllegalArgumentException(reason));
        }
    }

    /**
     * 处理隧道关闭。
     *
     * @param line 报文内容（CLOSE|channelId）
     */
    private void handleClose(String line) {
        String channelId = line.substring(SipProtocol.PREFIX_CLOSE.length() + 1).trim();
        SipTunnelSession session = openTunnels.remove(channelId);
        if (session != null) {
            session.dispatchClose();
        }
    }

    /**
     * 建立数据平面连接并绑定到会话（携带会话 token）。
     *
     * @param session 隧道会话
     * @param role    连接角色（visitor / provider）
     */
    private void connectDataStream(SipTunnelSession session, String role) {
        try {
            SipTunnelStream stream = new SipTunnelStream(serverHost, serverPort,
                    session.getChannelId(), role, sessionToken);
            session.attachStream(stream);
            log.debug("SIP 数据平面连接已建立: channel={}, role={}", session.getChannelId(), role);
        } catch (Exception e) {
            log.warn("SIP 数据平面连接失败: channel={}, role={}", session.getChannelId(), role);
            session.dispatchClose();
        }
    }

    /**
     * 获取客户端标识。
     *
     * @return 客户端标识
     */
    public String getClientId() {
        return clientId;
    }

    /**
     * 判断客户端是否已连接。
     *
     * @return true 表示已连接
     */
    public boolean isConnected() {
        return connected;
    }

    /**
     * 注册隧道服务，声明本客户端对外暴露的服务。
     *
     * @param serviceName 服务名称
     * @return 当前客户端实例，支持链式调用
     */
    public SipClient registerTunnel(String serviceName) {
        sendSignal(SipProtocol.line(SipProtocol.PREFIX_SERVICE, sessionToken, serviceName));
        return this;
    }

    /**
     * 注册隧道开启请求监听器（作为服务提供方接收访问方的隧道请求）。
     *
     * @param listener 监听器，参数为通道标识与服务名称
     * @return 当前客户端实例，支持链式调用
     */
    public SipClient onTunnelOpen(BiConsumer<String, String> listener) {
        tunnelOpenListeners.add(listener);
        return this;
    }

    /**
     * 获取指定通道标识的隧道会话（服务提供方在 {@code onTunnelOpen} 回调中获取会话）。
     *
     * @param channelId 通道标识
     * @return 隧道会话，通道不存在时返回 null
     */
    public SipTunnelSession tunnelSession(String channelId) {
        return openTunnels.get(channelId);
    }

    /**
     * 同步开启到指定服务的隧道。
     *
     * @param serviceName 服务名称
     * @return 隧道会话
     */
    public SipTunnelSession openTunnel(String serviceName) {
        return openTunnel(serviceName, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 同步开启到指定服务的隧道，指定超时时间。
     *
     * @param serviceName 服务名称
     * @param timeoutMs   超时时间（毫秒）
     * @return 隧道会话
     */
    public SipTunnelSession openTunnel(String serviceName, long timeoutMs) {
        CompletableFuture<SipTunnelSession> future = openTunnelAsync(serviceName);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new RuntimeException("SIP 隧道开启失败: " + serviceName, e);
        }
    }

    /**
     * 异步开启到指定服务的隧道。
     *
     * @param serviceName 服务名称
     * @return 异步任务，完成时包含隧道会话
     */
    public CompletableFuture<SipTunnelSession> openTunnelAsync(String serviceName) {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<SipTunnelSession> future = new CompletableFuture<>();
        pendingTunnels.put(requestId, future);
        future.whenComplete((session, error) -> pendingTunnels.remove(requestId));
        sendSignal(SipProtocol.line(SipProtocol.PREFIX_OPEN, sessionToken, requestId, serviceName));
        return future;
    }

    /**
     * 关闭指定隧道通道。
     *
     * @param channelId 通道标识
     */
    public void closeTunnel(String channelId) {
        sendSignal(SipProtocol.line(SipProtocol.PREFIX_CLOSE, sessionToken, channelId));
        openTunnels.remove(channelId);
    }

    /**
     * 发送信令行。
     *
     * @param line 信令行
     */
    private void sendSignal(String line) {
        PrintWriter writer = signalWriter;
        if (writer != null) {
            synchronized (writer) {
                writer.println(line);
                writer.flush();
            }
        }
    }

    /**
     * 断开与服务器的连接。
     */
    public void disconnect() {
        if (!connected) {
            return;
        }
        connected = false;
        try {
            if (signalSocket != null) {
                signalSocket.close();
            }
        } catch (IOException ignored) {
        }
        for (SipTunnelSession session : openTunnels.values()) {
            session.dispatchClose();
        }
        openTunnels.clear();
    }

    /**
     * 关闭客户端，释放资源。
     */
    public void close() {
        disconnect();
    }

    /**
     * 数据平面连接流：携带会话 token 握手后裸字节流透传。
     */
    static final class DerivedStream extends SipBaseStream {
    }

    /**
     * 数据平面原始流基类。
     */
    @Slf4j
    static class SipBaseStream {

        /**
         * 通道标识
         */
        private final String channelId;

        /**
         * 底层数据连接
         */
        private final Socket socket;

        /**
         * 输出流
         */
        private final OutputStream out;

        /**
         * 是否已关闭
         */
        private volatile boolean closed;

        /**
         * 建立数据平面连接（携带会话 token 握手）。
         *
         * @param host      服务器地址
         * @param port      服务器端口
         * @param channelId 通道标识
         * @param role      角色（visitor / provider）
         * @param token     会话令牌
         */
        SipBaseStream(String host, int port, String channelId, String role, String token) {
            this.channelId = channelId;
            Socket s = null;
            OutputStream o = null;
            try {
                s = new Socket();
                s.setTcpNoDelay(true);
                s.connect(new java.net.InetSocketAddress(host, port), 5000);
                o = s.getOutputStream();
                o.write((SipProtocol.line(SipProtocol.PREFIX_CONNECT, channelId, role, token) + "\n").getBytes(StandardCharsets.UTF_8));
                o.flush();
            } catch (Exception e) {
                if (s != null) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                    }
                }
                s = null;
            }
            this.socket = s;
            this.out = o;
        }

        /**
         * 是否连接成功。
         *
         * @return true 表示连接成功
         */
        boolean isConnected() {
            return socket != null;
        }

        /**
         * 获取通道标识。
         *
         * @return 通道标识
         */
        String getChannelId() {
            return channelId;
        }

        /**
         * 启动读循环，将收到的裸字节流回调给消费者。
         *
         * @param consumer 数据消费者
         */
        void startRead(Consumer<byte[]> consumer) {
            ThreadUtils.newThread(() -> {
                try {
                    InputStream in = socket.getInputStream();
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while (!closed && (n = in.read(buf)) != -1) {
                        byte[] data = new byte[n];
                        System.arraycopy(buf, 0, data, 0, n);
                        if (!closed) {
                            consumer.accept(data);
                        }
                    }
                } catch (IOException e) {
                    if (!closed) {
                        log.debug("SIP 数据流读取异常: {}", e.getMessage());
                    }
                } finally {
                    close();
                }
            }, "sip-data-stream-" + channelId).start();
        }

        /**
         * 发送字节流。
         *
         * @param payload 负载字节
         * @throws IOException IO 异常
         */
        void send(byte[] payload) throws IOException {
            synchronized (out) {
                out.write(payload);
                out.flush();
            }
        }

        /**
         * 关闭数据连接。
         */
        void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        /**
         * 是否已关闭。
         *
         * @return true 表示已关闭
         */
        boolean isClosed() {
            return closed;
        }
    }
}