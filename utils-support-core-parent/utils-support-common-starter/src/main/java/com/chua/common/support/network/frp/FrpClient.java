package com.chua.common.support.network.frp;

import com.chua.common.support.lang.algorithm.hmac.HMacUtils;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.Closeable;
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

/**
 * FRP 单端口客户端：认证换会话 token 后，持 token 注册显式映射并建立数据连接。
 *
 * <p>工作流程：</p>
 * <ol>
 *   <li>{@link #connect()} 建立信令长连接并完成 {@code AUTH} 认证，串换 {@code token}</li>
 *   <li>{@link #expose(String, int, int)} 暴露本端本地服务（provider 角色）：
 *       本机 {@code localPort} 上的服务以 {@code targetHost:targetPort} 为逻辑标识对外提供</li>
 *   <li>{@link #map(String, int, int)} 声明本端映射（visitor 角色）：
 *       本机监听 {@code localPort}，访问它即访问远端 {@code targetHost:targetPort} 服务</li>
 *   <li>隧道建立后自动建立 {@code CONNECT} 数据连接，进入裸字节流透传</li>
 * </ol>
 *
 * <p>服务端以「目标地址:端口 → 提供方」精确路由；本客户端提供服务给
 * {@link FrpExpose}、「访问方监听」由 {@link FrpTunnelPort} 承担。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public class FrpClient implements Closeable {

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
     * 认证令牌（初始共享密钥，与 FrpServer 相同）
     */
    private volatile String token = FrpConfig.DEFAULT_TOKEN;

    /**
     * 会话令牌（认证成功后由服务端颁发）
     */
    private volatile String sessionToken;

    /**
     * 隧道开启超时（毫秒），默认 {@link #DEFAULT_TIMEOUT_MS}
     */
    private volatile long tunnelOpenTimeoutMs = DEFAULT_TIMEOUT_MS;

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
    private final Map<String, CompletableFuture<FrpTunnelSession>> pendingTunnels = new ConcurrentHashMap<>();

    /**
     * 活动隧道会话集合（channelId -> 会话）
     */
    private final Map<String, FrpTunnelSession> openTunnels = new ConcurrentHashMap<>();

    /**
     * 隧道开启请求监听器列表（作为服务提供方接收：channelId, targetHost:targetPort）
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
     * 已声明注册的映射（重连后自动重放，保证服务端路由不丢）
     */
    private final Map<String, Mapping> registeredMappings = new ConcurrentHashMap<>();

    /**
     * 重连成功回调列表（服务/端口代理可在重连后重新绑定本地资源）
     */
    private final List<Runnable> reconnectListeners = new CopyOnWriteArrayList<>();

    /**
     * 服务端下推规则回调列表（参数：是否暴露型、目标地址、目标端口、本地端口、规则标识）
     *
     * <p>管理端在服务管理页新增规则后，服务端将其下推给客户端；
     * 宿主应用据此启动对应的本地桥接（{@link FrpExpose} / {@link FrpTunnelPort}）。</p>
     */
    private final List<RuleListener> rulePushListeners = new CopyOnWriteArrayList<>();

    /**
     * 服务端撤销规则回调列表（参数同 {@link #rulePushListeners}）
     */
    private final List<RuleListener> ruleRevokeListeners = new CopyOnWriteArrayList<>();

    /**
     * 创建 FRP 客户端。
     *
     * @param url 服务器地址，如 tcp://127.0.0.1:19470
     */
    public FrpClient(String url) {
        String addr = url.replaceFirst("^\\w+://", "");
        int colon = addr.lastIndexOf(':');
        this.serverHost = colon > 0 ? addr.substring(0, colon) : "127.0.0.1";
        this.serverPort = colon > 0 ? Integer.parseInt(addr.substring(colon + 1)) : FrpConfig.DEFAULT_PORT;
    }

    /**
     * 创建基于 TCP 传输的 FRP 客户端。
     *
     * @param url 服务端地址，如 tcp://127.0.0.1:19470
     * @return FRP 客户端
     */
    public static FrpClient tcp(String url) {
        return new FrpClient(url);
    }

    /**
     * 设置认证令牌（初始共享密钥，注册与数据平面握手签名共用）。
     *
     * @param token 认证令牌
     * @return 当前客户端实例，支持链式调用
     */
    public FrpClient token(String token) {
        this.token = token != null ? token : "";
        return this;
    }

    /**
     * 设置隧道开启超时时间（毫秒）。
     *
     * <p>高延迟或高负载链路（如跨机房、突发建隧窗口）可适当调大，
     * 避免服务端瞬时繁忙时 {@link #openTunnel(String, int)} 过早失败。</p>
     *
     * @param timeoutMs 超时时间（毫秒），&lt;=0 时回退默认值 {@link #DEFAULT_TIMEOUT_MS}
     * @return 当前客户端实例，支持链式调用
     */
    public FrpClient tunnelOpenTimeoutMs(long timeoutMs) {
        this.tunnelOpenTimeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        return this;
    }

    /**
     * 连接服务器并完成认证换 token。
     *
     * @return 当前客户端实例，支持链式调用
     */
    public FrpClient connect() {
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
            this.signalWriter = new PrintWriter(rawOut, true, StandardCharsets.UTF_8);
            // 认证握手：AUTH|clientId|host|port|signature
            String signature = HMacUtils.hmacSha256Hex(token, clientId + serverHost + serverPort);
            PrintWriter writer = signalWriter;
            writer.println(FrpProtocol.line(FrpProtocol.PREFIX_AUTH, clientId, serverHost,
                    String.valueOf(serverPort), signature));
            writer.flush();
            // 读响应，等待 TOKEN
            BufferedReader reader = new BufferedReader(new InputStreamReader(rawIn, StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null || !line.startsWith(FrpProtocol.PREFIX_TOKEN + FrpProtocol.SEPARATOR)) {
                socket.close();
                throw new IllegalStateException("FRP 认证失败: " + line);
            }
            this.sessionToken = line.substring(FrpProtocol.PREFIX_TOKEN.length() + 1);
            connected = true;
            manualClosed = false;
            startSignalReader(reader);
            startHeartbeat();
            log.info("FRP 客户端认证成功: {} @ {}:{}", clientId, serverHost, serverPort);
            // 重连后重放映射规则，保证服务端路由不丢
            for (Mapping mapping : registeredMappings.values()) {
                sendSignal(mapping.registrationLine(sessionToken));
            }
            // 触发重连回调，供服务/端口代理重新绑定本地资源
            for (Runnable listener : reconnectListeners) {
                try {
                    listener.run();
                } catch (Exception e) {
                    log.warn("FRP 重连回调异常", e);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("FRP 客户端连接失败: " + serverHost + ":" + serverPort, e);
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
                    log.debug("FRP 信令读取中断: {}", e.getMessage());
                }
            } finally {
                boolean wasConnected = connected;
                connected = false;
                stopHeartbeat();
                if (wasConnected && !manualClosed) {
                    scheduleReconnect();
                }
            }
        }, "frp-signal-" + clientId).start();
    }

    /**
     * 信令连接被动断开后自动重连（网络抖动/服务端重启自愈）。
     */
    private void scheduleReconnect() {
        ThreadUtils.startVirtualThread("frp-reconnect-" + clientId, () -> {
            while (!manualClosed && !connected) {
                ThreadUtils.sleep(RECONNECT_INTERVAL_MS);
                if (manualClosed || connected) {
                    return;
                }
                try {
                    log.info("FRP 尝试重连: {}:{}", serverHost, serverPort);
                    connect();
                    return;
                } catch (Exception e) {
                    log.debug("FRP 重连失败: {}", e.getMessage());
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
                sendSignal(FrpProtocol.line(FrpProtocol.PREFIX_PING, sessionToken));
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
            log.debug("FRP RECV: {}", line);
            if (line.startsWith(FrpProtocol.PREFIX_EXPOSE_OK + FrpProtocol.SEPARATOR)) {
                log.debug("FRP 暴露规则生效: {}", line.substring(FrpProtocol.PREFIX_EXPOSE_OK.length() + 1));
            } else if (line.startsWith(FrpProtocol.PREFIX_MAP_OK + FrpProtocol.SEPARATOR)) {
                log.debug("FRP 映射规则生效: {}", line.substring(FrpProtocol.PREFIX_MAP_OK.length() + 1));
            } else if (line.startsWith(FrpProtocol.PREFIX_EXPOSE + FrpProtocol.SEPARATOR)) {
                // 服务端下推暴露规则（管理端新增）：登记并回调宿主
                handlePushedRule(line, true);
            } else if (line.startsWith(FrpProtocol.PREFIX_MAP + FrpProtocol.SEPARATOR)) {
                // 服务端下推映射规则（管理端新增）：登记并回调宿主
                handlePushedRule(line, false);
            } else if (line.startsWith(FrpProtocol.PREFIX_EXPOSE_DEL + FrpProtocol.SEPARATOR)) {
                handleRevokedRule(line, true);
            } else if (line.startsWith(FrpProtocol.PREFIX_MAP_DEL + FrpProtocol.SEPARATOR)) {
                handleRevokedRule(line, false);
            } else if (line.startsWith(FrpProtocol.PREFIX_OPENED + FrpProtocol.SEPARATOR)) {
                handleOpened(line);
            } else if (line.startsWith(FrpProtocol.PREFIX_TUNNEL_OPEN + FrpProtocol.SEPARATOR)) {
                handleTunnelOpenRequest(line);
            } else if (line.startsWith(FrpProtocol.PREFIX_ERROR + FrpProtocol.SEPARATOR)) {
                handleError(line);
            } else if (line.startsWith(FrpProtocol.PREFIX_CLOSE + FrpProtocol.SEPARATOR)) {
                handleClose(line);
            } else if (line.startsWith(FrpProtocol.PREFIX_PING + FrpProtocol.SEPARATOR)) {
                // 服务端心跳探测，回复 PONG
                String[] parts = line.split("\\|", 3);
                sendSignal(FrpProtocol.line(FrpProtocol.PREFIX_PONG,
                        parts.length > 1 ? parts[1] : ""));
            } else {
                log.debug("忽略未知 FRP 信令: {}", line);
            }
        } catch (Exception e) {
            log.warn("FRP 信令解析异常: {}", e.getMessage(), e);
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
        CompletableFuture<FrpTunnelSession> future = pendingTunnels.remove(requestId);
        log.debug("FRP OPENED 处理: requestId={}, ch={}, futureFound={}", requestId, channelId, future != null);
        if (future == null) {
            return;
        }
        FrpTunnelSession session = new FrpTunnelSession(this, channelId, "");
        openTunnels.put(channelId, session);
        connectDataStream(session, FrpProtocol.ROLE_VISITOR);
        future.complete(session);
    }

    /**
     * 处理隧道开启请求（作为服务提供方收到访问方的隧道请求）。
     *
     * @param line 报文内容（TUNNEL_OPEN|token|channelId|targetHost|targetPort）
     */
    private void handleTunnelOpenRequest(String line) {
        String[] parts = line.split("\\|", 5);
        String channelId = parts.length > 2 ? parts[2] : "";
        String target = parts.length > 4 ? parts[3] + ":" + parts[4] : "";
        FrpTunnelSession session = new FrpTunnelSession(this, channelId, target);
        openTunnels.put(channelId, session);
        for (BiConsumer<String, String> listener : tunnelOpenListeners) {
            try {
                listener.accept(channelId, target);
            } catch (Exception e) {
                log.error("FRP 隧道开启监听器异常", e);
            }
        }
        connectDataStream(session, FrpProtocol.ROLE_PROVIDER);
    }

    /**
     * 处理服务端下推的规则（管理端在服务管理页新增后由服务端下发）。
     *
     * @param line   报文内容（EXPOSE/MAP|token|ruleId|targetHost|targetPort|localPort）
     * @param expose 是否为暴露规则（否则为映射规则）
     */
    private void handlePushedRule(String line, boolean expose) {
        String[] parts = line.split("\\|", 6);
        if (parts.length < 6) {
            return;
        }
        String ruleId = parts[2];
        String targetHost = parts[3];
        int targetPort = parseInt(parts[4]);
        int localPort = parseInt(parts[5]);
        // 登记到本地映射表：重连时由 connect() 自动重放，避免服务端下推的规则丢
        registeredMappings.putIfAbsent(ruleId,
                new Mapping(expose ? MappingType.EXPOSE : MappingType.MAP,
                        targetHost, targetPort, localPort));
        for (RuleListener listener : rulePushListeners) {
            try {
                listener.accept(expose, targetHost, targetPort, localPort, ruleId);
            } catch (Exception e) {
                log.error("FRP 规则下推回调异常", e);
            }
        }
    }

    /**
     * 处理服务端下推的规则撤销（管理端在服务管理页撤销后由服务端下发）。
     *
     * @param line   报文内容（EXPOSE_DEL/MAP_DEL|token|ruleId）
     * @param expose 是否为暴露规则（否则为映射规则）
     */
    private void handleRevokedRule(String line, boolean expose) {
        String[] parts = line.split("\\|", 3);
        String ruleId = parts.length > 2 ? parts[2].trim() : "";
        registeredMappings.remove(ruleId);
        for (RuleListener listener : ruleRevokeListeners) {
            try {
                listener.accept(expose, "", 0, 0, ruleId);
            } catch (Exception e) {
                log.error("FRP 规则撤销回调异常", e);
            }
        }
    }

    /**
     * 解析整数。
     *
     * @param value 文本
     * @return 整数，解析失败返回 0
     */
    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 注册服务端下推规则监听（管理端新增规则后触发）。
     *
     * <p>宿主应用据此启动对应的本地桥接：{@code expose=true} 时启动
     * {@link FrpExpose} 到 localPort，否则启动 {@link FrpTunnelPort}。
     * 多个监听器都会收到，可按规则标识去重。</p>
     *
     * @param listener 监听器（isExpose, targetHost, targetPort, localPort, ruleId）
     * @return 当前客户端实例，支持链式调用
     */
    public FrpClient onRulePushed(RuleListener listener) {
        rulePushListeners.add(listener);
        return this;
    }

    /**
     * 注册服务端撤销规则监听（管理端撤销规则后触发）。
     *
     * @param listener 监听器（isExpose, targetHost, targetPort, localPort, ruleId）
     * @return 当前客户端实例，支持链式调用
     */
    public FrpClient onRuleRevoked(RuleListener listener) {
        ruleRevokeListeners.add(listener);
        return this;
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
        CompletableFuture<FrpTunnelSession> future = pendingTunnels.remove(requestId);
        if (future != null) {
            future.completeExceptionally(new IllegalArgumentException(reason));
        }
    }

    /**
     * 处理隧道关闭。
     *
     * @param line 报文内容（CLOSE|token|channelId）
     */
    private void handleClose(String line) {
        String[] parts = line.split("\\|", 3);
        String channelId = parts.length > 2 ? parts[2].trim() : "";
        FrpTunnelSession session = openTunnels.remove(channelId);
        if (session != null) {
            session.dispatchClose();
        }
    }

    /**
     * 建立数据平面连接并绑定到会话（携带签名握手）。
     *
     * @param session 隧道会话
     * @param role    连接角色（visitor / provider）
     */
    private void connectDataStream(FrpTunnelSession session, String role) {
        try {
            FrpTunnelStream stream = new FrpTunnelStream(serverHost, serverPort,
                    session.getChannelId(), role, token, sessionToken);
            session.attachStream(stream);
        } catch (Exception e) {
            log.warn("FRP 数据平面连接失败: channel={}, role={}", session.getChannelId(), role);
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
     * 注册暴露规则：本机 {@code localPort} 上的服务以 {@code targetHost:targetPort}
     * 为逻辑标识对外提供（provider 角色）。
     *
     * @param targetHost 目标地址（对外逻辑标识）
     * @param targetPort 目标端口（对外逻辑标识）
     * @param localPort  本机服务端口
     * @return 当前客户端实例，支持链式调用
     */
    public FrpClient expose(String targetHost, int targetPort, int localPort) {
        registerMapping(new Mapping(MappingType.EXPOSE, targetHost, targetPort, localPort));
        return this;
    }

    /**
     * 注册映射规则：本机监听 {@code localPort}，访问它即访问远端
     * {@code targetHost:targetPort} 服务（visitor 角色）。
     *
     * @param targetHost 目标地址
     * @param targetPort 目标端口
     * @param localPort  本机监听端口
     * @return 当前客户端实例，支持链式调用
     */
    public FrpClient map(String targetHost, int targetPort, int localPort) {
        registerMapping(new Mapping(MappingType.MAP, targetHost, targetPort, localPort));
        return this;
    }

    /**
     * 内部注册一条映射规则并发送服务端。
     *
     * @param mapping 映射规则
     */
    private void registerMapping(Mapping mapping) {
        registeredMappings.put(mapping.ruleId, mapping);
        sendSignal(mapping.registrationLine(sessionToken));
    }

    /**
     * 撤销一条已注册的映射规则。
     *
     * @param targetHost 目标地址
     * @param targetPort 目标端口
     * @param localPort  本地端口
     * @return 当前客户端实例，支持链式调用
     */
    public FrpClient removeMapping(String targetHost, int targetPort, int localPort) {
        String key = targetHost + ":" + targetPort + ":" + localPort;
        Mapping mapping = registeredMappings.values().stream()
                .filter(m -> key.equals(m.describe()))
                .findFirst().orElse(null);
        if (mapping == null) {
            return this;
        }
        registeredMappings.remove(mapping.ruleId);
        String prefix = mapping.type == MappingType.EXPOSE
                ? FrpProtocol.PREFIX_EXPOSE_DEL : FrpProtocol.PREFIX_MAP_DEL;
        sendSignal(FrpProtocol.line(prefix, sessionToken, mapping.ruleId));
        return this;
    }

    /**
     * 注册重连成功回调（自动重连建立新信令连接后触发，用于重新绑定本地资源）。
     *
     * @param listener 回调
     * @return 当前客户端实例，支持链式调用
     */
    public FrpClient onReconnect(Runnable listener) {
        reconnectListeners.add(listener);
        return this;
    }

    /**
     * 注册隧道开启请求监听器（作为服务提供方接收：channelId, targetHost:targetPort）。
     *
     * @param listener 监听器
     * @return 当前客户端实例，支持链式调用
     */
    public FrpClient onTunnelOpen(BiConsumer<String, String> listener) {
        tunnelOpenListeners.add(listener);
        return this;
    }

    /**
     * 打开一条到目标服务的隧道（visitor 侧调用，服务端精确路由到注册了该目标的提供方）。
     *
     * @param targetHost 目标地址
     * @param targetPort 目标端口
     * @return 隧道会话
     */
    public FrpTunnelSession openTunnel(String targetHost, int targetPort) {
        String requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        var future = new CompletableFuture<FrpTunnelSession>();
        pendingTunnels.put(requestId, future);
        sendSignal(FrpProtocol.line(FrpProtocol.PREFIX_OPEN, sessionToken, requestId,
                targetHost, String.valueOf(targetPort)));
        try {
            return future.get(tunnelOpenTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            pendingTunnels.remove(requestId);
            throw new IllegalStateException("FRP 隧道开启失败: " + targetHost + ":" + targetPort, e);
        }
    }

    /**
     * 获取已建立的隧道会话。
     *
     * @param channelId 通道标识
     * @return 隧道会话，不存在返回 null
     */
    public FrpTunnelSession tunnelSession(String channelId) {
        return openTunnels.get(channelId);
    }

    /**
     * 关闭一条隧道。
     *
     * @param channelId 通道标识
     */
    public void closeTunnel(String channelId) {
        FrpTunnelSession session = openTunnels.remove(channelId);
        sendSignal(FrpProtocol.line(FrpProtocol.PREFIX_CLOSE, sessionToken, channelId));
        if (session != null) {
            session.dispatchClose();
        }
    }

    /**
     * 发送一行信令。
     *
     * @param line 信令行
     */
    void sendSignal(String line) {
        PrintWriter writer = signalWriter;
        if (writer != null && connected) {
            synchronized (writer) {
                writer.println(line);
                writer.flush();
            }
        }
    }

    /**
     * 关闭客户端：断开信令连接与全部隧道。
     */
    @Override
    public void close() {
        manualClosed = true;
        connected = false;
        stopHeartbeat();
        for (FrpTunnelSession session : openTunnels.values()) {
            session.dispatchClose();
        }
        openTunnels.clear();
        Socket socket = signalSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                log.debug("关闭 FRP 信令连接失败，已忽略: {}", e.getMessage());
            }
        }
    }

    /**
     * 规则监听器（服务端下推 / 撤销规则时回调）。
     *
     * @param expose     是否为暴露规则（否则为映射规则）
     * @param targetHost 目标地址（撤销回调为空串）
     * @param targetPort 目标端口（撤销回调为 0）
     * @param localPort  本地端口（撤销回调为 0）
     * @param ruleId     规则标识
     */
    @FunctionalInterface
    public interface RuleListener {
        /**
         * 回调。
         *
         * @param expose     是否为暴露规则
         * @param targetHost 目标地址
         * @param targetPort 目标端口
         * @param localPort  本地端口
         * @param ruleId     规则标识
         */
        void accept(boolean expose, String targetHost, int targetPort, int localPort, String ruleId);
    }

    /**
     * 映射类型。
     */
    private enum MappingType {
        /**
         * 暴露（provider）：本机 localPort 对外 = targetHost:targetPort
         */
        EXPOSE,
        /**
         * 映射（visitor）：本机 localPort -> 远端 targetHost:targetPort
         */
        MAP
    }

    /**
     * 一条显式映射规则（目标地址、目标端口、本地端口）。
     */
    private static final class Mapping {

        /**
         * 映射类型
         */
        private final MappingType type;

        /**
         * 目标地址
         */
        private final String targetHost;

        /**
         * 目标端口
         */
        private final int targetPort;

        /**
         * 本地端口
         */
        private final int localPort;

        /**
         * 规则标识
         */
        private final String ruleId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        /**
         * 创建映射规则。
         *
         * @param type       映射类型
         * @param targetHost 目标地址
         * @param targetPort 目标端口
         * @param localPort  本地端口
         */
        private Mapping(MappingType type, String targetHost, int targetPort, int localPort) {
            this.type = type;
            this.targetHost = targetHost;
            this.targetPort = targetPort;
            this.localPort = localPort;
        }

        /**
         * 描述（用于去重匹配）。
         *
         * @return targetHost:targetPort:localPort
         */
        private String describe() {
            return targetHost + ":" + targetPort + ":" + localPort;
        }

        /**
         * 构建注册信令行。
         *
         * @param sessionToken 会话令牌
         * @return 注册行
         */
        private String registrationLine(String sessionToken) {
            if (type == MappingType.EXPOSE) {
                return FrpProtocol.line(FrpProtocol.PREFIX_EXPOSE, sessionToken, ruleId,
                        targetHost, String.valueOf(targetPort), String.valueOf(localPort));
            }
            return FrpProtocol.line(FrpProtocol.PREFIX_MAP, sessionToken, ruleId,
                    targetHost, String.valueOf(targetPort), String.valueOf(localPort));
        }
    }
}