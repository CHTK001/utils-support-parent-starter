package com.chua.common.support.network.sip;

import com.chua.common.support.lang.algorithm.hmac.HMacUtils;
import com.chua.common.support.network.crypto.AesGcmUtils;
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

import javax.crypto.spec.SecretKeySpec;

/**
 * SIP 单端口客户端：先认证换取会话 token，再持 token 建隧道数据连接。
 *
 * <p>工作流程：</p>
 * <ol>
 *   <li>{@link #connect()} 建立信令长连接并完成 {@code AUTH} 认证，串换 {@code token}</li>
 *   <li>{@link #service(String)} 暴露本端本地服务（provider 角色）</li>
 *   <li>{@link #tunnel(String)} 访问对端服务的访问方入口（visitor 角色）</li>
 *   <li>隧道建立后自动建立 {@code CONNECT} 数据连接，进入裸字节流透传</li>
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
     * 数据面端到端加密开关（AES-256-GCM，密钥由共享 token 派生，两侧需一致）
     */
    private volatile boolean encryptData;

    /**
     * 数据面多路复用开关（单条连接承载同角色全部隧道，两侧需一致）
     */
    private volatile boolean muxData;

    /**
     * visitor 角色复用连接
     */
    private volatile SipMuxConnection muxVisitorConn;

    /**
     * provider 角色复用连接
     */
    private volatile SipMuxConnection muxProviderConn;

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
     * 是否主动关闭（关闭后不再自动重连）
     */
    private volatile boolean manualClosed;

    /**
     * 重连间隔（毫秒）
     */
    private static final long RECONNECT_INTERVAL_MS = 3000L;

    /**
     * 心跳间隔（毫秒）
     */
    private static final long HEARTBEAT_INTERVAL_MS = 15000L;

    /**
     * 心跳定时器
     */
    private volatile java.util.concurrent.ScheduledExecutorService heartbeatScheduler;

    /**
     * 已声明注册的隧道服务名（重连后自动重放 SERVICE，保证服务端路由不丢）
     */
    private final java.util.Set<String> registeredServices = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 重连成功回调列表（服务/端口代理可在重连后重新绑定本地资源）
     */
    private final List<Runnable> reconnectListeners = new CopyOnWriteArrayList<>();

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
    public SipClient token(String token) {
        this.token = token != null ? token : "";
        return this;
    }

    /**
     * 设置数据面端到端加密开关（AES-256-GCM，密钥由共享 token 派生）。
     *
     * <p>开启后 visitor 与 provider 之间的数据在客户端侧加密、服务端仅桥接密文；
     * 要求隧道两侧客户端同时开启，默认关闭。</p>
     *
     * @param encryptData true 开启加密
     * @return 当前客户端实例，支持链式调用
     */
    public SipClient encrypt(boolean encryptData) {
        this.encryptData = encryptData;
        return this;
    }

    /**
     * 设置数据面多路复用开关（单条连接承载同角色全部隧道，两侧需一致）。
     *
     * <p>开启后每条隧道不再独立拨号，而是复用同角色的共享数据连接（帧式按 channelId 分发），
     * 显著降低隧道建立延迟与连接数。默认关闭。</p>
     *
     * @param muxData true 开启多路复用
     * @return 当前客户端实例，支持链式调用
     */
    public SipClient mux(boolean muxData) {
        this.muxData = muxData;
        return this;
    }

    /**
     * 设置认证令牌（初始共享密钥，与 {@link #token(String)} 等价）。
     *
     * @param token 认证令牌
     * @return 当前客户端实例，支持链式调用
     * @deprecated 请使用 {@link #token(String)}
     */
    @Deprecated
    public SipClient setToken(String token) {
        return token(token);
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
            OutputStream rawOut = socket.getOutputStream();
            InputStream rawIn = socket.getInputStream();
            if (encryptData) {
                SecretKeySpec key = AesGcmUtils.deriveKey(token);
                rawOut = AesGcmUtils.encrypting(rawOut, key);
                rawIn = AesGcmUtils.decrypting(rawIn, key);
            }
            this.signalWriter = new PrintWriter(rawOut, true, StandardCharsets.UTF_8);
            // 认证握手：AUTH|clientId|host|port|signature
            String signature = HMacUtils.hmacSha256Hex(token, clientId + serverHost + serverPort);
            PrintWriter writer = signalWriter;
            writer.println(SipProtocol.line(SipProtocol.PREFIX_AUTH, clientId, serverHost, String.valueOf(serverPort), signature));
            writer.flush();
            // 读响应，等待 TOKEN
            BufferedReader reader = new BufferedReader(new InputStreamReader(rawIn, StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null || !line.startsWith(SipProtocol.PREFIX_TOKEN + SipProtocol.SEPARATOR)) {
                socket.close();
                throw new IllegalStateException("SIP 认证失败: " + line);
            }
            this.sessionToken = line.substring(SipProtocol.PREFIX_TOKEN.length() + 1);
            connected = true;
            manualClosed = false;
            startSignalReader(reader);
            startHeartbeat();
            log.info("SIP 客户端认证成功: {} @ {}:{}", clientId, serverHost, serverPort);
            // 重连后重放服务注册，保证服务端路由不丢
            for (String serviceName : registeredServices) {
                sendSignal(SipProtocol.line(SipProtocol.PREFIX_SERVICE, sessionToken, serviceName));
            }
            // 触发重连回调，供服务/端口代理重新绑定本地资源
            for (Runnable listener : reconnectListeners) {
                try {
                    listener.run();
                } catch (Exception e) {
                    log.warn("SIP 重连回调异常", e);
                }
            }
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
                boolean wasConnected = connected;
                connected = false;
                stopHeartbeat();
                if (wasConnected && !manualClosed) {
                    scheduleReconnect();
                }
            }
        }, "sip-signal-" + clientId).start();
    }

    /**
     * 信令连接被动断开后自动重连（网络抖动/服务端重启自愈）。
     */
    private void scheduleReconnect() {
        ThreadUtils.startVirtualThread("sip-reconnect-" + clientId, () -> {
            while (!manualClosed && !connected) {
                try {
                    Thread.sleep(RECONNECT_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (manualClosed || connected) {
                    return;
                }
                try {
                    log.info("SIP 尝试重连: {}:{}", serverHost, serverPort);
                    connect();
                    return;
                } catch (Exception e) {
                    log.debug("SIP 重连失败: {}", e.getMessage());
                }
            }
        });
    }

    /**
     * 启动心跳定时器，定期发送 PING 保持 NAT 通道存活并检测连接。
     */
    private void startHeartbeat() {
        stopHeartbeat();
        java.util.concurrent.ScheduledExecutorService scheduler =
                ThreadUtils.newSingleThreadScheduledExecutor();
        this.heartbeatScheduler = scheduler;
        scheduler.scheduleAtFixedRate(() -> {
            if (connected && !manualClosed) {
                sendSignal(SipProtocol.line(SipProtocol.PREFIX_PING, sessionToken));
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 停止心跳定时器。
     */
    private void stopHeartbeat() {
        java.util.concurrent.ScheduledExecutorService scheduler = heartbeatScheduler;
        if (scheduler != null) {
            heartbeatScheduler = null;
            scheduler.shutdownNow();
        }
    }

    /**
     * 处理服务端下发的信令行。
     *
     * @param line 信令行
     */
    private void handleSignal(String line) {
        try {
            log.info("SIP RECV: {}", line);
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
            } else if (line.startsWith(SipProtocol.PREFIX_PING + SipProtocol.SEPARATOR)) {
                // 服务端心跳探测，回复 PONG
                String[] parts = line.split("\\|", 3);
                sendSignal(SipProtocol.line(SipProtocol.PREFIX_PONG,
                        parts.length > 1 ? parts[1] : ""));
            } else if (line.startsWith(SipProtocol.PREFIX_PONG + SipProtocol.SEPARATOR)) {
                // 客户端心跳的响应，无需额外处理
                log.debug("SIP 心跳确认");
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
        String[] parts = line.split("\\|", 4);
        String token = parts.length > 1 ? parts[1] : "";
        String channelId = parts.length > 2 ? parts[2] : "";
        String serviceName = parts.length > 3 ? parts[3] : "";
        SipTunnelSession session = new SipTunnelSession(this, channelId, serviceName);
        openTunnels.put(channelId, session);
        for (BiConsumer<String, String> listener : tunnelOpenListeners) {
            try {
                listener.accept(channelId, serviceName);
            } catch (Exception e) {
                log.error("SIP 隧道开启监听器异常", e);
            }
        }
        connectDataStream(session, "provider");
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
        String[] parts = line.split("\\|", 3);
        String channelId = parts.length > 2 ? parts[2].trim() : "";
        SipTunnelSession session = openTunnels.remove(channelId);
        log.info("SIP CLOSE 处理: channel={}, sessionFound={}", channelId, session != null);
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
            if (muxData) {
                SipMuxConnection conn = muxConnection(role, session.getChannelId());
                SipMuxStream stream = new SipMuxStream(session.getChannelId(), conn);
                conn.attach(stream);
                stream.startRead(session::dispatchBytes);
                stream.attach(session::dispatchBytes, session::dispatchClose);
                session.attachMuxStream(stream);
                log.debug("SIP mux 数据通道已挂载: channel={}, role={}", session.getChannelId(), role);
                return;
            }
            SipTunnelStream stream = new SipTunnelStream(serverHost, serverPort,
                    session.getChannelId(), role, token, sessionToken, encryptData);
            session.attachStream(stream);
            log.debug("SIP 数据平面连接已建立: channel={}, role={}", session.getChannelId(), role);
        } catch (Exception e) {
            log.warn("SIP 数据平面连接失败: channel={}, role={}", session.getChannelId(), role);
            session.dispatchClose();
        }
    }

    /**
     * 获取（或建立）指定角色的多路复用连接。
     *
     * @param role           角色
     * @param firstChannelId 首个通道标识（用于握手）
     * @return 复用连接
     * @throws IOException 建立失败
     */
    private SipMuxConnection muxConnection(String role, String firstChannelId) throws IOException {
        SipMuxConnection existing = "visitor".equals(role) ? muxVisitorConn : muxProviderConn;
        if (existing != null && !existing.isClosed()) {
            return existing;
        }
        SipMuxConnection created = SipMuxConnection.open(this, serverHost, serverPort, role,
                token, sessionToken, firstChannelId, encryptData);
        if ("visitor".equals(role)) {
            muxVisitorConn = created;
        } else {
            muxProviderConn = created;
        }
        log.info("SIP mux 连接已建立: role={}, channel={}", role, firstChannelId);
        return created;
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
     * 注册隧道服务，声明本客户端对外暴露的服务（provider 角色）。
     *
     * @param serviceName 服务名称
     * @return 当前客户端实例，支持链式调用
     */
    public SipClient registerTunnel(String serviceName) {
        registeredServices.add(serviceName);
        sendSignal(SipProtocol.line(SipProtocol.PREFIX_SERVICE, sessionToken, serviceName));
        return this;
    }

    /**
     * 注册重连成功回调（自动重连建立新信令连接后触发，用于重新绑定本地资源）。
     *
     * @param listener 回调
     * @return 当前客户端实例，支持链式调用
     */
    public SipClient onReconnect(Runnable listener) {
        reconnectListeners.add(listener);
        return this;
    }

    /**
     * 以服务提供方（provider）角色暴露本机 TCP 服务，链式声明服务名与本地地址。
     *
     * <p>等价于 {@code new SipTunnelService(this, name, host, port).start()}，
     * 但以链式 DSL 形式提供，便于串接在客户端链上：</p>
     * <pre>{@code
     * SipClient client = SipClient.tcp("tcp://127.0.0.1:19460")
     *         .token("xxx")
     *         .service("mariadb").to("127.0.0.1", 3306);
     * }</pre>
     *
     * @param serviceName 服务名称
     * @return 服务 DSL，调用 {@link SipClient.ServiceDsl#to(String, int)} 完成暴露
     */
    public ServiceDsl service(String serviceName) {
        return new ServiceDsl(serviceName);
    }

    /**
     * 以访问方（visitor）角色监听本地端口并转发到对端服务，链式声明服务名与本地端口。
     *
     * <p>等价于 {@code new SipTunnelPort(this, name, port).start()}，
     * 但以链式 DSL 形式提供，便于串接在客户端链上：</p>
     * <pre>{@code
     * SipClient client = SipClient.tcp("tcp://127.0.0.1:19460")
     *         .token("xxx")
     *         .tunnel("mariadb").listen(14306);
     * }</pre>
     *
     * @param serviceName 目标隧道服务名称
     * @return 隧道 DSL，调用 {@link SipClient.TunnelDsl#listen(int)} 完成映射
     */
    public TunnelDsl tunnel(String serviceName) {
        return new TunnelDsl(serviceName);
    }

    /**
     * 服务提供方 DSL：{@code client.service(name).to(host, port)} 暴露本地服务。
     */
    public class ServiceDsl {

        private final String serviceName;

        ServiceDsl(String serviceName) {
            this.serviceName = serviceName;
        }

        /**
         * 将 {@code localHost:localPort} 暴露为 SIP 隧道服务。
         *
         * @param localHost 本地服务地址
         * @param localPort 本地服务端口
         * @return 服务侧隧道代理
         */
        public SipTunnelService to(String localHost, int localPort) {
            return new SipTunnelService(SipClient.this, serviceName, localHost, localPort).start();
        }

        /**
         * 将本机 {@code localPort} 暴露为 SIP 隧道服务。
         *
         * @param localPort 本地服务端口
         * @return 服务侧隧道代理
         */
        public SipTunnelService to(int localPort) {
            return to("127.0.0.1", localPort);
        }
    }

    /**
     * 访问方 DSL：{@code client.tunnel(name).listen(port)} 映射本地端口到对端服务。
     */
    public class TunnelDsl {

        private final String serviceName;

        TunnelDsl(String serviceName) {
            this.serviceName = serviceName;
        }

        /**
         * 监听 {@code localHost:localPort}，将连接转发到对端隧道服务。
         *
         * @param localHost 本地监听地址
         * @param localPort 本地监听端口
         * @return 端口侧隧道代理
         */
        public SipTunnelPort listen(String localHost, int localPort) {
            return new SipTunnelPort(SipClient.this, serviceName, localHost, localPort).start();
        }

        /**
         * 监听本机 {@code localPort}，将连接转发到对端隧道服务。
         *
         * @param localPort 本地监听端口
         * @return 端口侧隧道代理
         */
        public SipTunnelPort listen(int localPort) {
            return listen("127.0.0.1", localPort);
        }
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
        if (!connected && manualClosed) {
            return;
        }
        manualClosed = true;
        connected = false;
        stopHeartbeat();
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
}