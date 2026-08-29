package com.chua.common.support.network.sip;

import com.chua.common.support.lang.algorithm.hmac.HMacUtils;
import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.impl.JdkTcpServer;
import com.chua.common.support.network.tcp.TcpServer;
import com.chua.common.support.network.tcp.callback.TcpServerHandler;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * SIP 单端口服务器：认证信令与 frp 数据平面共用同一监听端口。
 *
 * <p>继承 {@link AbstractServer} 获得统一生命周期与配置管理，实现 {@link TcpServer} 接口
 * 复用 TCP 服务抽象，底层由 {@link JdkTcpServer} 流式模式承载连接。</p>
 *
 * <p>连接建立后首行握手：</p>
 * <ul>
 *   <li>{@code AUTH|clientId|host|port|signature} → 认证连接，
 *       验签通过后返回 {@code TOKEN|token} 并保持为信令长连接，后续按行收发信令</li>
 *   <li>{@code CONNECT|channelId|role|token} → 数据平面连接，
 *       携带会话 token 校验，通过后与对端裸字节流双向桥接（TCP 代理模式）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SipServer extends AbstractServer implements TcpServer {

    /**
     * 握手首行最大长度（字节），超长直接断开，防止恶意连接打爆内存
     */
    private static final int MAX_HEAD_LINE = 8192;

    /**
     * 客户端注册表（clientId -> 信令连接）
     */
    private final Map<String, SignalConnection> registry = new ConcurrentHashMap<>();

    /**
     * 会话令牌表（token -> clientId）
     */
    private final Map<String, String> sessionTokens = new ConcurrentHashMap<>();

    /**
     * 隧道服务注册表（serviceName -> 服务提供方 clientId）
     */
    private final Map<String, String> tunnelServices = new ConcurrentHashMap<>();

    /**
     * 隧道通道路由表（channelId -> 通道两端）
     */
    private final Map<String, TunnelChannel> tunnelChannels = new ConcurrentHashMap<>();

    /**
     * 数据通道桥接表（channelId -> 桥接器）
     */
    private final Map<String, DataChannel> dataChannels = new ConcurrentHashMap<>();

    /**
     * 多路复用连接表（clientId|role -> 连接）
     */
    private final Map<String, MuxServerConn> muxConns = new ConcurrentHashMap<>();

    /**
     * 多路复用待转发帧（channelId|peerKey -> 帧列表，对端连接未注册时暂存）
     */
    private final Map<String, List<byte[]>> muxPending = new ConcurrentHashMap<>();

    /**
     * 客户端连接回调列表
     */
    private final List<Consumer<String>> connectListeners = new CopyOnWriteArrayList<>();

    /**
     * 客户端断开回调列表
     */
    private final List<Consumer<String>> disconnectListeners = new CopyOnWriteArrayList<>();

    /**
     * 认证令牌（初始共享密钥）
     */
    private final String token;



    /**
     * 底层 TCP 服务器（流式模式，一连接一虚拟线程）
     */
    private JdkTcpServer tcpServer;

    /**
     * 连接级限流器（认证限流 + 帧率限制）
     */
    private final SipRateLimiter rateLimiter;

<<<<<<< Updated upstream
    private volatile boolean running = true;
=======
    /**
     * frp 数据平面（独立端口承载隧道数据）
     */
    private SipDataPlane dataPlane;

    /**
     * 是否正在运行
     */
    private volatile boolean running;
>>>>>>> Stashed changes

    /**
     * 使用默认配置创建 SIP 服务器。
     */
    public SipServer() {
        this(SipConfig.defaults());
    }

    /**
     * 使用指定配置创建 SIP 服务器。
     *
     * @param config 服务器配置
     */
    public SipServer(SipConfig config) {
        super(serverSetting(config));
        this.token = resolveToken(config);
        this.rateLimiter = new SipRateLimiter(config.getMinFrameIntervalNs(), config.getMaxAuthPerIpPerMin());
    }

    /**
     * 构建服务器配置。
     *
     * @param config SIP 配置
     * @return 服务器配置
     */
<<<<<<< Updated upstream
        /**
     * 解析 token：优先 --token-file 文件，其次 SipConfig.token。
=======
    public SipServer start() {
        if (running) {
            return this;
        }
        if (config.isTcpEnabled()) {
            startTransport("tcp", config.getTcpPort());
        }
        if (config.isKcpEnabled()) {
            startTransport("kcp", config.getKcpPort());
        }
        if (config.isDataPlaneEnabled()) {
            try {
                dataPlane = new SipDataPlane(config.getHost(), config.getDataPort(), this::handleDataPlaneClosed);
                dataPlane.start();
            } catch (Exception e) {
                log.warn("SIP 数据平面启动失败: {}", e.getMessage());
            }
        }
        running = true;
        log.info("SIP 服务器启动完成: tcp={}, kcp={}, dataPlane={}",
                config.isTcpEnabled(), config.isKcpEnabled(), config.isDataPlaneEnabled());
        return this;
    }

    /**
     * 启动指定类型的传输。
     *
     * @param type 传输类型（tcp / kcp）
     * @param port 监听端口
>>>>>>> Stashed changes
     */
    private static String resolveToken(SipConfig config) {
        String file = config.getTokenFile();
        if (file != null && !file.isEmpty()) {
            try {
                String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(file)),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!content.isEmpty()) {
                    log.info("SIP token 从文件加载: {}", file);
                    return content;
                }
            } catch (Exception e) {
                log.warn("SIP token 文件读取失败: {} ({})", file, e.getMessage());
            }
        }
        return config.getToken();
    }

private static ServerSetting serverSetting(SipConfig config) {
        ServerSetting setting = ServerSetting.defaults();
        setting.setHost(config.getHost());
        setting.setPort(config.getPort());
        setting.setProtocol("tcp");
        setting.setEncrypt(config.isEncrypt());
        if (config.isEncrypt()) {
            setting.setEncryptKey(config.getToken());
        }
        return setting;
    }

    /**
     * 启动服务器的具体逻辑。
     */
    @Override
    protected void doStart() {
        tcpServer = new JdkTcpServer(setting);
        tcpServer.registerHandler("*", this::handleConnection);
        tcpServer.start();
        setting.setPort(tcpServer.getPort());
        log.info("SIP 单端口服务器启动成功: {}:{}", setting.getHost(), setting.getPort());
    }

    /**
     * 停止服务器的具体逻辑。
     */
    @Override
    protected void doStop() {
        // 通知所有在线客户端即将停机
        for (String clientId : registry.keySet()) {
            send(clientId, SipProtocol.line(SipProtocol.PREFIX_ERROR, "server-shutdown", "服务器停机"));
        }
        if (tcpServer != null) {
            try {
                tcpServer.stop();
            } catch (Exception ignored) {
            }
        }
        for (DataChannel channel : dataChannels.values()) {
            channel.close();
        }
<<<<<<< Updated upstream
        dataChannels.clear();
=======
        if (dataPlane != null) {
            try {
                dataPlane.stop();
            } catch (Exception ignored) {
            }
        }
>>>>>>> Stashed changes
        registry.clear();
        sessionTokens.clear();
        tunnelServices.clear();
        tunnelChannels.clear();
    }

    /**
     * 获取服务器配置。
     *
     * @return 配置实例
     */
    public SipConfig getConfig() {
        return SipConfig.builder()
                .host(setting.getHost())
                .port(setting.getPort())
                .token(token)
                .build();
    }

    /**
     * 获取当前已注册的客户端标识列表。
     *
     * @return 客户端标识列表
     */
    public List<String> getConnectedClients() {
        return List.copyOf(registry.keySet());
    }

    /**
     * 注册客户端连接回调。
     *
     * @param listener 连接回调，参数为客户端标识
     * @return 当前服务器实例，支持链式调用
     */
    public SipServer onConnect(Consumer<String> listener) {
        connectListeners.add(listener);
        return this;
    }

    /**
     * 注册客户端断开回调。
     *
     * @param listener 断开回调，参数为客户端标识
     * @return 当前服务器实例，支持链式调用
     */
    public SipServer onDisconnect(Consumer<String> listener) {
        disconnectListeners.add(listener);
        return this;
    }

    /**
     * 注册帧处理器（TcpServer 接口，SIP 使用流式协议，此处直接返回当前实例）。
     *
     * @param handler 帧处理器
     * @return 当前实例
     */
    @Override
    public SipServer setHandler(TcpServerHandler handler) {
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
     * 以协议处理器方式处理一条被协议嗅探服务器转交的连接。
     *
     * <p>复用单端口首行分流逻辑（{@code AUTH} 认证信令 / {@code CONNECT} 数据平面），
     * 供 {@link com.chua.common.support.network.tcp.ProtocolSniffingTcpServer} 等
     * 外部监听器嵌入调用。此时本服务器不独立监听端口，由外部监听器负责连接接入与协议识别。</p>
     *
     * @param in  输入流（含外部监听器已回推的头部字节）
     * @param out 输出流
     */
    public void handleStream(InputStream in, OutputStream out) {
        running = true;
        handleConnection(in, out);
    }

    /**
     * 处理一条连接：读首行握手，按前缀分流认证信令与数据平面。
     *
     * @param in  输入流
     * @param out 输出流
     */
    private void handleConnection(InputStream in, OutputStream out) {
        try {
            String firstLine = readHeadLine(in);
            if (firstLine == null || firstLine.isBlank()) {
                return;
            }
            if (firstLine.startsWith(SipProtocol.PREFIX_AUTH + SipProtocol.SEPARATOR)) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                handleSignalConnection(reader, out, firstLine);
            } else if (firstLine.startsWith(SipProtocol.PREFIX_MUXCONN + SipProtocol.SEPARATOR)) {
                handleMuxConnection(in, out, firstLine);
            } else if (firstLine.startsWith(SipProtocol.PREFIX_CONNECT + SipProtocol.SEPARATOR)) {
                handleDataConnection(in, out, firstLine);
            }
        } catch (IOException e) {
            log.debug("SIP 连接处理异常: {}", e.getMessage());
        }
    }

    /**
     * 处理多路复用数据面连接：验签后注册到连接表，读循环内按 channelId 解帧路由。
     *
     * @param in        输入流
     * @param out       输出流
     * @param firstLine 握手行（MUXCONN|首个channelId|role|signature）
     * @throws IOException IO 异常
     */
    private void handleMuxConnection(InputStream in, OutputStream out, String firstLine) throws IOException {
        String[] parts = firstLine.split("\\|", 4);
        if (parts.length < 4) {
            return;
        }
        String channelId = parts[1];
        String role = parts[2];
        String signature = parts[3];
        TunnelChannel channel = tunnelChannels.get(channelId);
        if (channel == null) {
            log.warn("SIP mux 握手通道不存在: channelId={}", channelId);
            return;
        }
        SignalConnection a = registry.get(channel.aId());
        SignalConnection b = registry.get(channel.bId());
        String expectedA = a != null ? HMacUtils.hmacSha256Hex(a.token(), channelId + role) : null;
        String expectedB = b != null ? HMacUtils.hmacSha256Hex(b.token(), channelId + role) : null;
        boolean verified = signature.equals(expectedA) || signature.equals(expectedB);
        if (!verified) {
            log.warn("SIP mux 握手签名校验失败: channelId={}, role={}", channelId, role);
            return;
        }
        String clientId = signature.equals(expectedA) ? channel.aId() : channel.bId();
        String key = clientId + "|" + role;
        MuxServerConn conn = new MuxServerConn(key, clientId, role, in, out);
        muxConns.put(key, conn);
            SipMetrics.get().onClientConnect();
        muxFlushPending(conn);
        log.info("SIP mux 连接接入: key={}, 首通道={}", key, channelId);
        conn.readLoop();
        muxConns.remove(key, conn);
            SipMetrics.get().onClientDisconnect();
        conn.notifyPeerChannelsClosed();
    }

    /**
     * mux 帧路由：按通道两端将帧转发给对端复用连接；对端未注册时暂存。
     *
     * @param from      来源连接
     * @param channelId 通道标识
     * @param payload   负载（空数组为通道关闭标记）
     */
<<<<<<< Updated upstream
    private void muxRoute(MuxServerConn from, String channelId, byte[] payload) {
=======
    private void handleTunnelOpen(SyncServer transport, String clientId, String payload) {
        String[] parts = payload.split("\\|", 2);
        String requestId = parts[0];
        String serviceName = parts.length > 1 ? parts[1] : "";
        String providerId = tunnelServices.get(serviceName);
        if (providerId == null || providerId.equals(clientId)) {
            transport.send(clientId, SipProtocol.CMD_TUNNEL_ERROR,
                    requestId + SipProtocol.SEPARATOR + "service not found: " + serviceName);
            return;
        }
        SipPeer provider = registry.get(providerId);
        if (provider == null) {
            transport.send(clientId, SipProtocol.CMD_TUNNEL_ERROR,
                    requestId + SipProtocol.SEPARATOR + "provider offline: " + serviceName);
            return;
        }
        String channelId = UUID.randomUUID().toString();
        tunnelChannels.put(channelId, new TunnelChannel(clientId, providerId));
        if (dataPlane != null) {
            dataPlane.createChannel(channelId);
        }
        provider.transport().send(providerId, SipProtocol.CMD_TUNNEL_OPEN,
                channelId + SipProtocol.SEPARATOR + serviceName + SipProtocol.SEPARATOR + config.getDataPort());
        transport.send(clientId, SipProtocol.CMD_TUNNEL_OPENED,
                requestId + SipProtocol.SEPARATOR + channelId + SipProtocol.SEPARATOR + config.getDataPort());
        log.info("SIP 隧道建立: {} <-> {} via {}", clientId, providerId, channelId);
    }

    /**
     * 处理隧道数据帧，转发给通道对端。
     *
     * @param transport 来源传输实例
     * @param clientId  发送方客户端标识
     * @param payload   报文内容（channelId|data）
     */
    private void handleTunnelData(SyncServer transport, String clientId, String payload) {
        String[] parts = payload.split("\\|", 2);
        String channelId = parts[0];
        String data = parts.length > 1 ? parts[1] : "";
>>>>>>> Stashed changes
        TunnelChannel channel = tunnelChannels.get(channelId);
        if (channel == null) {
            return;
        }
        boolean fromVisitor = "visitor".equals(from.role);
        String peerKey = (fromVisitor ? channel.bId() : channel.aId()) + "|" + (fromVisitor ? "provider" : "visitor");
        from.channels.add(channelId);
       

        // 通道关闭标记
        if (payload.length == 0) {
            muxPending.remove(channelId + "|" + peerKey);
            MuxServerConn peer = muxConns.get(peerKey);
            if (peer != null) {
                peer.sendFrame(channelId, payload);
            }
            return;
        }

        // 对端连接已注册即可发送：对端客户端的 earlyFrames 会缓冲 attach 前的帧
        MuxServerConn peer = muxConns.get(peerKey);
        if (peer != null) {
            peer.sendFrame(channelId, payload);
            return;
        }

        // 对端连接未注册：缓冲完整帧，注册时冲刷
        byte[] ch = uuidBytes(channelId);
        ByteBuffer buffer = ByteBuffer.allocate(4 + 16 + payload.length);
        buffer.putInt(16 + payload.length);
        buffer.put(ch);
        buffer.put(payload);
        byte[] fullFrame = buffer.array();
        List<byte[]> pending = muxPending.computeIfAbsent(channelId + "|" + peerKey,
                k -> java.util.Collections.synchronizedList(new ArrayList<>()));
        synchronized (pending) {
            if (pending.size() < 256) {
                pending.add(fullFrame);
            }
        }
    }

    /**
     * 冲刷暂存帧：对端复用连接注册后补发（对端客户端 earlyFrames 会缓冲至流挂载）。
     *
     * @param conn 新注册的连接
     */
    private void muxFlushPending(MuxServerConn conn) {
        for (Map.Entry<String, List<byte[]>> entry : muxPending.entrySet()) {
            if (!entry.getKey().endsWith("|" + conn.key)) {
                continue;
            }
            String channelId = entry.getKey().substring(0, entry.getKey().indexOf('|'));
            List<byte[]> list = entry.getValue();
            synchronized (list) {
                for (byte[] fullFrame : list) {
                    conn.sendRaw(fullFrame);
                }
                list.clear();
            }
            muxPending.remove(entry.getKey(), list);
        }
    }

    /**
     * 冲刷暂存帧：新复用连接注册后，补发等它的一切帧。
     *
     * @param conn 新注册的连接
     */


    /**
     * 逐字节读取首行（遇换行停止），不预读缓冲后续字节，保证数据平面业务字节不被吞掉。
     * <p>超过 {@link #MAX_HEAD_LINE} 字节直接视为非法连接并断开，防止恶意客户端打爆内存。</p>
     *
     * @param in 输入流
     * @return 首行内容（不含换行符），读不到或超长时返回 null
     * @throws IOException IO 异常
     */
    private static String readHeadLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buffer.write(b);
                if (buffer.size() > MAX_HEAD_LINE) {
                    log.warn("SIP 首行超长，拒绝连接: {} bytes", buffer.size());
                    return null;
                }
            }
        }
        return buffer.size() == 0 ? null : new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 处理信令长连接：认证换 token 后循环读取信令行。
     *
     * @param reader    输入
     * @param out       输出
     * @param firstLine 认证握手行（AUTH|clientId|host|port|signature）
     * @throws IOException IO 异常
     */
    private void handleSignalConnection(BufferedReader reader, OutputStream out, String firstLine) throws IOException {
        String[] parts = firstLine.split("\\|");
        if (parts.length < 5) {
            return;
        }
        String clientId = parts[1];
        String host = parts[2];
        int port = parseInt(parts[3]);
        String signature = parts[4];
        String expected = HMacUtils.hmacSha256Hex(token, clientId + host + port);
        if (!expected.equals(signature)) {
            log.warn("SIP 认证签名校验失败，拒绝接入: clientId={}", clientId);
            writeLine(out, SipProtocol.line(SipProtocol.PREFIX_ERROR, "auth", "签名校验失败"));
            return;
        }
        String sessionToken = UUID.randomUUID().toString();
        sessionTokens.put(sessionToken, clientId);
        PrintWriter writer = new PrintWriter(out, true, StandardCharsets.UTF_8);
        SignalConnection conn = new SignalConnection(clientId, host, port, sessionToken, writer);
        SignalConnection previous = registry.put(clientId, conn);
        SipMetrics.get().onClientConnect();
        if (previous == null) SipMetrics.get().onAuthAccept();
        if (previous != null) {
            // 同一 clientId 重复接入：踢掉旧连接，防止注册表互相覆盖导致隧道串线
            log.warn("SIP 客户端重复接入，踢掉旧连接: clientId={}", clientId);
            sessionTokens.remove(previous.token());
            closeQuietly(previous);
            disconnectTunnelsOf(clientId);
        }
        writeLine(out, SipProtocol.line(SipProtocol.PREFIX_TOKEN, sessionToken));
            SipMetrics.get().onAuthAccept();
        notifyConnectListeners(clientId);
        log.info("SIP 客户端认证接入: {} @ {}:{}", clientId, host, port);

        String line;
        while (running && (line = reader.readLine()) != null) {
            if (!rateLimiter.allowFrame(clientId)) { log.debug("SIP 帧限速: client={}", clientId); continue; }
            handleSignal(clientId, line);
        }
                rateLimiter.releaseConnection(clientId);
onSignalClosed(clientId);
    }

    /**
     * 处理信令命令。
     *
     * @param clientId 客户端标识
     * @param line     命令行
     */
    private void handleSignal(String clientId, String line) {
        try {
            if (line.startsWith(SipProtocol.PREFIX_SERVICE + SipProtocol.SEPARATOR)) {
                String[] parts = line.split("\\|", 3);
                if (!matchesToken(clientId, parts, 1)) {
                    return;
                }
                String serviceName = parts.length > 2 ? parts[2].trim() : "";
                tunnelServices.put(serviceName, clientId);
                send(clientId, SipProtocol.line(SipProtocol.PREFIX_SERVICE_OK, serviceName));
                log.info("SIP 隧道服务注册: {} -> {}", serviceName, clientId);
            } else if (line.startsWith(SipProtocol.PREFIX_OPEN + SipProtocol.SEPARATOR)) {
                String[] parts = line.split("\\|", 4);
                if (!matchesToken(clientId, parts, 1)) {
                    return;
                }
                handleOpen(clientId, line);
            } else if (line.startsWith(SipProtocol.PREFIX_CLOSE + SipProtocol.SEPARATOR)) {
                String[] parts = line.split("\\|", 3);
                if (!matchesToken(clientId, parts, 1)) {
                    return;
                }
                handleClose(clientId, line);
            } else if (line.startsWith(SipProtocol.PREFIX_PING + SipProtocol.SEPARATOR)) {
                // 心跳探活：回 PONG 确认连接存活
                String[] parts = line.split("\\|", 3);
                String token = parts.length > 1 ? parts[1] : "";
                if (matchesToken(clientId, parts, 1)) {
                    send(clientId, SipProtocol.line(SipProtocol.PREFIX_PONG, token));
                }
            } else {
                log.debug("忽略未知 SIP 信令: {}", line);
            }
        } catch (Exception e) {
            log.warn("SIP 信令处理异常: {}", e.getMessage());
        }
    }

    /**
     * 校验信令行携带的会话令牌与当前连接一致，防止伪造命令。
     *
     * @param clientId 客户端标识
     * @param parts    已按分隔符拆分的信令字段（第 1 位为会话令牌）
     * @param tokenIdx 会话令牌所在下标
     * @return true 表示校验通过
     */
    private boolean matchesToken(String clientId, String[] parts, int tokenIdx) {
        SignalConnection conn = registry.get(clientId);
        if (conn == null) {
            return false;
        }
        String presented = parts.length > tokenIdx ? parts[tokenIdx] : "";
        if (!conn.token().equals(presented)) {
            log.warn("SIP 信令令牌校验失败，拒绝: clientId={}, command={}", clientId,
                    parts[0]);
            return false;
        }
        return true;
    }

    /**
     * 处理隧道开启请求。
     *
     * @param clientId 访问方客户端标识
     * @param line     命令行（OPEN|requestId|serviceName）
     */
    private void handleOpen(String clientId, String line) {
        String[] parts = line.split("\\|", 4);
        String token = parts.length > 1 ? parts[1] : "";
        String requestId = parts.length > 2 ? parts[2] : "";
        String serviceName = parts.length > 3 ? parts[3] : "";
        // 路由：精确服务名优先，未命中回落到通配服务（"*"，动态目标中继）
        String providerId = tunnelServices.get(serviceName);
        if (providerId == null) {
            providerId = tunnelServices.get(SipProtocol.WILDCARD_SERVICE);
        }
        if (providerId == null || providerId.equals(clientId)) {
            send(clientId, SipProtocol.line(SipProtocol.PREFIX_ERROR, requestId, "service not found: " + serviceName));
            SipMetrics.get().incError("open.service_not_found");
            return;
        }
        SignalConnection provider = registry.get(providerId);
        if (provider == null) {
            send(clientId, SipProtocol.line(SipProtocol.PREFIX_ERROR, requestId, "provider offline: " + serviceName));
            return;
        }
        String channelId = UUID.randomUUID().toString();
        tunnelChannels.put(channelId, new TunnelChannel(clientId, providerId));
            SipMetrics.get().onTunnelOpen();
        dataChannels.computeIfAbsent(channelId, DataChannel::new);
        provider.send(SipProtocol.line(SipProtocol.PREFIX_TUNNEL_OPEN, provider.token(), channelId, serviceName));
        send(clientId, SipProtocol.line(SipProtocol.PREFIX_OPENED, requestId, channelId));
        log.info("SIP 隧道建立: {} <-> {} via {}", clientId, providerId, channelId);
    }

    /**
     * 处理隧道关闭。
     *
     * @param clientId 关闭方客户端标识
     * @param line     命令行（CLOSE|channelId）
     */
    private void handleClose(String clientId, String line) {
        String[] parts = line.split("\\|", 3);
        String channelId = parts.length > 2 ? parts[2].trim() : "";
        closeChannel(channelId);
    }

    /**
     * 处理数据平面连接：会话 token 校验后与对端桥接裸字节流。
     *
     * @param in        输入流
     * @param out       输出流
     * @param firstLine 连接握手行（CONNECT|channelId|role|token）
     */
    private void handleDataConnection(InputStream in, OutputStream out, String firstLine) {
        String[] parts = firstLine.split("\\|", 4);
        if (parts.length < 4) {
            return;
        }
        String channelId = parts[1];
        String role = parts[2];
        String signature = parts[3];
        TunnelChannel channel = tunnelChannels.get(channelId);
        if (channel == null) {
            log.warn("SIP 数据平面通道不存在: channelId={}", channelId);
            return;
        }
        // 签名 = HMAC-SHA256(会话token, channelId + role)，分别用通道两端 token 验签
        SignalConnection a = registry.get(channel.aId());
        SignalConnection b = registry.get(channel.bId());
        String expectedA = a != null
                ? HMacUtils.hmacSha256Hex(a.token(), channelId + role) : null;
        String expectedB = b != null
                ? HMacUtils.hmacSha256Hex(b.token(), channelId + role) : null;
        boolean verified = signature.equals(expectedA) || signature.equals(expectedB);
        if (!verified) {
            log.warn("SIP 数据平面签名校验失败: channelId={}, role={}", channelId, role);
            return;
        }
        DataChannel dataChannel = dataChannels.get(channelId);
        if (dataChannel == null) {
            return;
        }
        dataChannel.bind(role, in, out);
        log.debug("SIP 数据平面连接绑定: channel={}, role={}", channelId, role);
        dataChannel.awaitClosed();
    }

    /**
     * 信令连接关闭后的清理。
     *
     * @param clientId 客户端标识
     */
    private void onSignalClosed(String clientId) {
        SignalConnection conn = registry.remove(clientId);
            SipMetrics.get().onClientDisconnect();
        if (conn == null) {
            return;
        }
        sessionTokens.remove(conn.token());
        notifyDisconnectListeners(clientId);
        disconnectTunnelsOf(clientId);
        log.info("SIP 客户端断开: {}", clientId);
    }

    /**
     * 清理某客户端名下的隧道服务与参与的通道路由（服务端主动踢连接时复用）。
     *
     * @param clientId 客户端标识
     */
    private void disconnectTunnelsOf(String clientId) {
        // 移除该客户端提供的服务
        tunnelServices.entrySet().removeIf(entry -> entry.getValue().equals(clientId));
        // 关闭该客户端参与的隧道
        List<String> closed = new ArrayList<>();
        for (Map.Entry<String, TunnelChannel> entry : tunnelChannels.entrySet()) {
            TunnelChannel channel = entry.getValue();
            if (channel.aId().equals(clientId) || channel.bId().equals(clientId)) {
                closed.add(entry.getKey());
            }
        }
        closed.forEach(this::closeChannel);
    }

    /**
     * 服务端主动关闭某客户端的信令连接。
     *
     * @param conn 信令连接
     */
    private void closeQuietly(SignalConnection conn) {
        try {
            conn.writer().close();
        } catch (Exception ignored) {
        }
    }

    /**
     * 关闭隧道并通知两端。
     *
     * @param channelId 通道标识
     */
    private void closeChannel(String channelId) {
        TunnelChannel channel = tunnelChannels.remove(channelId);
            SipMetrics.get().onTunnelClose(channel == null ? "unknown" : "normal");
        DataChannel dataChannel = dataChannels.remove(channelId);
        if (dataChannel != null) {
            dataChannel.close();
        }
        if (channel == null) {
            return;
        }
        // CLOSE 行携带对端会话 token（客户端按 CLOSE|token|channelId 三字段解析）
        SignalConnection a = registry.get(channel.aId());
        if (a != null) {
            send(channel.aId(), SipProtocol.line(SipProtocol.PREFIX_CLOSE, a.token(), channelId));
        }
        SignalConnection b = registry.get(channel.bId());
        if (b != null) {
            send(channel.bId(), SipProtocol.line(SipProtocol.PREFIX_CLOSE, b.token(), channelId));
        }
<<<<<<< Updated upstream
=======
        if (dataPlane != null) {
            dataPlane.closeChannel(channelId);
        }
        log.info("SIP 隧道关闭: {} ({})", channelId, clientId);
>>>>>>> Stashed changes
    }

    /**
     * 向指定客户端发送信令行；写入失败时移除该连接并清理其资源。
     *
     * @param clientId 客户端标识
     * @param line     信令行
     */
    private void send(String clientId, String line) {
        SignalConnection conn = registry.get(clientId);
        if (conn != null && !conn.send(line)) {
            log.warn("SIP 信令写入失败，清理僵尸连接: clientId={}", clientId);
            registry.remove(clientId);
            sessionTokens.remove(conn.token());
            disconnectTunnelsOf(clientId);
            closeQuietly(conn);
        }
    }

    /**
     * 写一行输出。
     *
     * @param out  输出流
     * @param line 行内容
     */
    private void writeLine(OutputStream out, String line) {
        try {
            synchronized (out) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException e) {
            log.debug("SIP 写响应异常: {}", e.getMessage());
        }
    }

    /**
     * 解析整数。
     *
     * @param value 文本
     * @return 整数，解析失败返回 0
     */
    private int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 通知连接回调。
     *
     * @param clientId 客户端标识
     */
    private void notifyConnectListeners(String clientId) {
        for (Consumer<String> listener : connectListeners) {
            try {
                listener.accept(clientId);
            } catch (Exception e) {
                log.error("SIP 连接回调异常", e);
            }
        }
    }

    /**
     * 通知断开回调。
     *
     * @param clientId 客户端标识
     */
    private void notifyDisconnectListeners(String clientId) {
        for (Consumer<String> listener : disconnectListeners) {
            try {
                listener.accept(clientId);
            } catch (Exception e) {
                log.error("SIP 断开回调异常", e);
            }
        }
    }

    /**
     * 已注册客户端的信令长连接。
     *
     * @param clientId 客户端标识
     * @param host     可达地址
     * @param port     可达端口
     * @param token    会话令牌
     * @param writer   输出
     */
<<<<<<< Updated upstream
    private record SignalConnection(String clientId, String host, int port, String token, PrintWriter writer) {
=======
    private void cleanupTunnels(String clientId) {
        // 移除该客户端提供的隧道服务，避免成为陈旧服务
        tunnelServices.entrySet().removeIf(entry -> entry.getValue().equals(clientId));
        // 关闭该客户端参与的所有隧道通道，并通知对端
        List<String> closedChannels = new ArrayList<>();
        for (Map.Entry<String, TunnelChannel> entry : tunnelChannels.entrySet()) {
            TunnelChannel channel = entry.getValue();
            String peerId = channel.targetOf(clientId);
            if (peerId != null) {
                SipPeer peer = registry.get(peerId);
                if (peer != null) {
                    peer.transport().send(peerId, SipProtocol.CMD_TUNNEL_CLOSE, entry.getKey());
                }
                if (dataPlane != null) {
                    dataPlane.closeChannel(entry.getKey());
                }
                closedChannels.add(entry.getKey());
            }
        }
        closedChannels.forEach(tunnelChannels::remove);
    }

    /**
     * 数据平面通道关闭回调：通知信令层清理隧道路由。
     *
     * @param channelId 通道标识
     * @param reason    关闭原因
     */
    private void handleDataPlaneClosed(String channelId, String reason) {
        TunnelChannel channel = tunnelChannels.remove(channelId);
        if (channel == null) {
            return;
        }
        SipPeer visitor = registry.get(channel.aId());
        if (visitor != null) {
            visitor.transport().send(channel.aId(), SipProtocol.CMD_TUNNEL_CLOSE, channelId);
        }
        SipPeer provider = registry.get(channel.bId());
        if (provider != null) {
            provider.transport().send(channel.bId(), SipProtocol.CMD_TUNNEL_CLOSE, channelId);
        }
    }

    /**
     * 传输层事件监听器，将各传输（TCP/KCP）的信令统一交给 {@link SipServer} 处理。
     *
     * @since 4.0.0.42
     */
    private final class SipTransportListener implements SyncServerListener {
>>>>>>> Stashed changes

        /**
         * 发送信令行；写入失败时返回 false（连接已断）。
         *
         * @param line 信令行
         * @return true 表示发送成功
         */
        boolean send(String line) {
            synchronized (writer) {
                writer.println(line);
                writer.flush();
                return !writer.checkError();
            }
        }
    }

    /**
     * 隧道通道两端路由信息。
     *
     * @param aId 访问方客户端标识
     * @param bId 服务提供方客户端标识
     */
    private record TunnelChannel(String aId, String bId) {
    }

    /**
     * 单条隧道的数据桥接器：负责访问方与提供方两条数据连接的裸字节流双向转发。
     */
    /**
     * 多路复用服务端连接：单条连接承载同角色全部通道的帧收发。
     */
    private final class MuxServerConn {

        /**
         * 连接键（clientId|role）
         */
        private final String key;

        /**
         * 客户端标识
         */
        private final String clientId;

        /**
         * 角色（visitor / provider）
         */
        private final String role;

        /**
         * 输入流
         */
        private final InputStream in;

        /**
         * 输出流
         */
        private final OutputStream out;

        /**
         * 本连接承载过的通道
         */
        private final java.util.Set<String> channels = java.util.concurrent.ConcurrentHashMap.newKeySet();

        /**
         * 是否仍在运行
         */
        private volatile boolean running = true;

        private MuxServerConn(String key, String clientId, String role, InputStream in, OutputStream out) {
            this.key = key;
            this.clientId = clientId;
            this.role = role;
            this.in = in;
            this.out = out;
        }

        /**
         * 读循环：解帧并交给路由。
         */
        void readLoop() {
            try {
                while (running) {
                    byte[] header = readFullyN(in, 4);
                    int len = ByteBuffer.wrap(header).getInt();
                    if (len < 16 || len > 16 + 64 * 1024) {
                        throw new IOException("SIP mux 帧长度非法: " + len);
                    }
                    byte[] frame = readFullyN(in, len);
                    String channelId = uuidString(java.util.Arrays.copyOfRange(frame, 0, 16));
                    byte[] payload = new byte[len - 16];
                    System.arraycopy(frame, 16, payload, 0, payload.length);
                    log.info("SIP mux recv: key={}, ch={}, payload={}", key, channelId, payload.length);
                    muxRoute(this, channelId, payload);
                }
            } catch (IOException e) {
                log.debug("SIP mux 连接读取结束: key={}, {}", key, e.getMessage());
            }
        }

        /**
         * 发送一帧。
         *
         * @param channelId 通道标识
         * @param payload   负载（空为关闭标记）
         */
        /**
         * 直接写出完整帧（含长度头与 channelId）。
         *
         * @param fullFrame 完整帧
         */
        void sendRaw(byte[] fullFrame) {
            try {
                synchronized (out) {
                    out.write(fullFrame);
                    out.flush();
                }
            } catch (IOException e) {
                log.debug("SIP mux 帧发送失败: key={}, {}", key, e.getMessage());
            }
        }
        void sendFrame(String channelId, byte[] payload) {
            try {
                byte[] ch = uuidBytes(channelId);
                ByteBuffer buffer = ByteBuffer.allocate(4 + 16 + payload.length);
                buffer.putInt(16 + payload.length);
                buffer.put(ch);
                buffer.put(payload);
                synchronized (out) {
                    out.write(buffer.array());
                    out.flush();
                }
            } catch (IOException e) {
                log.debug("SIP mux 帧发送失败: key={}, {}", key, e.getMessage());
            }
        }

        /**
         * 连接退出时：对本连接承载的全部通道，向对端发送关闭标记并清理暂存。
         */
        void notifyPeerChannelsClosed() {
            for (String ch : channels) {
                TunnelChannel channel = tunnelChannels.get(ch);
                if (channel == null) {
                    continue;
                }
                boolean fromVisitor = "visitor".equals(role);
                String peerKey = (fromVisitor ? channel.bId() : channel.aId()) + "|" + (fromVisitor ? "provider" : "visitor");
                muxPending.remove(ch + "|" + peerKey);
                MuxServerConn peer = muxConns.get(peerKey);
                if (peer != null && peer != this) {
                    peer.sendFrame(ch, new byte[0]);
                }
            }
            channels.clear();
        }
    }

    /**
     * 阻塞读满指定长度。
     *
     * @param in 输入流
     * @param n  期望长度
     * @return 数据
     * @throws IOException 流结束
     */
    private static byte[] readFullyN(InputStream in, int n) throws IOException {
        byte[] data = new byte[n];
        int offset = 0;
        while (offset < n) {
            int read = in.read(data, offset, n - offset);
            if (read == -1) {
                throw new IOException("SIP mux 连接已关闭");
            }
            offset += read;
        }
        return data;
    }

    /**
     * UUID 字符串转 16 字节。
     *
     * @param uuid UUID 字符串
     * @return 16 字节
     */
    private static byte[] uuidBytes(String uuid) {
        String hex = uuid.replace("-", "");
        byte[] data = new byte[16];
        for (int i = 0; i < 16; i++) {
            data[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return data;
    }

    /**
     * 16 字节转 UUID 字符串。
     *
     * @param data 16 字节
     * @return UUID 字符串
     */
    private static String uuidString(byte[] data) {
        StringBuilder hex = new StringBuilder();
        for (byte b : data) {
            hex.append(String.format("%02x", b));
        }
        String s = hex.toString();
        return s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16)
                + "-" + s.substring(16, 20) + "-" + s.substring(20);
    }
    private static final class DataChannel {

        /**
         * 通道标识
         */
        private final String channelId;

        /**
         * 访问方输入输出
         */
        private volatile Endpoint visitor;

        /**
         * 提供方输入输出
         */
        private volatile Endpoint provider;

        /**
         * 是否已关闭
         */
        private volatile boolean closed;

        /**
         * 关闭闩锁：两端 handler 线程在此等待，连接关闭后释放
         */
        private final java.util.concurrent.CountDownLatch closedLatch = new java.util.concurrent.CountDownLatch(1);

        /**
         * 创建通道桥接器。
         *
         * @param channelId 通道标识
         */
        private DataChannel(String channelId) {
            this.channelId = channelId;
        }

        /**
         * 等待通道关闭（阻塞调用线程，避免连接被上层框架提前关闭）。
         */
        void awaitClosed() {
            try {
                closedLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * 绑定一端连接，两端齐备后启动双向透传。
         *
         * @param role 角色（visitor / provider）
         * @param in   输入流
         * @param out  输出流
         */
        void bind(String role, InputStream in, OutputStream out) {
            Endpoint endpoint = new Endpoint(in, out);
            if (SipDataPlaneRole.VISITOR.name.equals(role)) {
                this.visitor = endpoint;
            } else if (SipDataPlaneRole.PROVIDER.name.equals(role)) {
                this.provider = endpoint;
            } else {
                return;
            }
            if (visitor != null && provider != null) {
                bridge(visitor, provider, "visitor");
                bridge(provider, visitor, "provider");
            }
        }

        /**
         * 单向裸字节流转发（与 TcpProxyServer 相同模式）。
         *
         * @param source 来源端点
         * @param target 目标端点
         * @param role   来源角色
         */
        private void bridge(Endpoint source, Endpoint target, String role) {
            ThreadUtils.startVirtualThread("sip-data-bridge-" + channelId + "-" + role, () -> {
                try {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while (!closed && (n = source.in.read(buf)) != -1) {
                        synchronized (target.out) {
                            target.out.write(buf, 0, n);
                            target.out.flush();
                        }
                    }
                } catch (IOException ignored) {
                } finally {
                    close();
                }
            });
        }

        /**
         * 关闭通道两端连接。
         */
        void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (visitor != null) {
                visitor.close();
            }
            if (provider != null) {
                provider.close();
            }
            closedLatch.countDown();
        }

        /**
         * 连接端点。
         */
        private static final class Endpoint {

            /**
             * 输入流
             */
            private final InputStream in;

            /**
             * 输出流
             */
            private final OutputStream out;

            /**
             * 创建端点。
             *
             * @param in  输入流
             * @param out 输出流
             */
            private Endpoint(InputStream in, OutputStream out) {
                this.in = in;
                this.out = out;
            }

            /**
             * 关闭。
             */
            void close() {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 数据平面角色。
     */
    private enum SipDataPlaneRole {
        /**
         * 访问方
         */
        VISITOR("visitor"),
        /**
         * 提供方
         */
        PROVIDER("provider");

        /**
         * 名称
         */
        private final String name;

        /**
         * 创建角色。
         *
         * @param name 名称
         */
        SipDataPlaneRole(String name) {
            this.name = name;
        }
    }
}
