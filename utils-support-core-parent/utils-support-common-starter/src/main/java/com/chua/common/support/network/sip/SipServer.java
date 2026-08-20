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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        this.token = config.getToken();
    }

    /**
     * 构建服务器配置。
     *
     * @param config SIP 配置
     * @return 服务器配置
     */
    private static ServerSetting serverSetting(SipConfig config) {
        ServerSetting setting = ServerSetting.defaults();
        setting.setHost(config.getHost());
        setting.setPort(config.getPort());
        setting.setProtocol("tcp");
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
        dataChannels.clear();
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
            } else if (firstLine.startsWith(SipProtocol.PREFIX_CONNECT + SipProtocol.SEPARATOR)) {
                handleDataConnection(in, out, firstLine);
            }
        } catch (IOException e) {
            log.debug("SIP 连接处理异常: {}", e.getMessage());
        }
    }

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
        if (previous != null) {
            // 同一 clientId 重复接入：踢掉旧连接，防止注册表互相覆盖导致隧道串线
            log.warn("SIP 客户端重复接入，踢掉旧连接: clientId={}", clientId);
            sessionTokens.remove(previous.token());
            closeQuietly(previous);
            disconnectTunnelsOf(clientId);
        }
        writeLine(out, SipProtocol.line(SipProtocol.PREFIX_TOKEN, sessionToken));
        notifyConnectListeners(clientId);
        log.info("SIP 客户端认证接入: {} @ {}:{}", clientId, host, port);

        String line;
        while (running && (line = reader.readLine()) != null) {
            handleSignal(clientId, line);
        }
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
        String providerId = tunnelServices.get(serviceName);
        if (providerId == null || providerId.equals(clientId)) {
            send(clientId, SipProtocol.line(SipProtocol.PREFIX_ERROR, requestId, "service not found: " + serviceName));
            return;
        }
        SignalConnection provider = registry.get(providerId);
        if (provider == null) {
            send(clientId, SipProtocol.line(SipProtocol.PREFIX_ERROR, requestId, "provider offline: " + serviceName));
            return;
        }
        String channelId = UUID.randomUUID().toString();
        tunnelChannels.put(channelId, new TunnelChannel(clientId, providerId));
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
        DataChannel dataChannel = dataChannels.remove(channelId);
        if (dataChannel != null) {
            dataChannel.close();
        }
        if (channel == null) {
            return;
        }
        if (registry.containsKey(channel.aId())) {
            send(channel.aId(), SipProtocol.line(SipProtocol.PREFIX_CLOSE, channelId));
        }
        if (registry.containsKey(channel.bId())) {
            send(channel.bId(), SipProtocol.line(SipProtocol.PREFIX_CLOSE, channelId));
        }
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
    private record SignalConnection(String clientId, String host, int port, String token, PrintWriter writer) {

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
