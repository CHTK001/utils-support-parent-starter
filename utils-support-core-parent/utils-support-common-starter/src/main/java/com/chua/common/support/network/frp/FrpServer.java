package com.chua.common.support.network.frp;

import com.chua.common.support.lang.algorithm.hmac.HMacUtils;
import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerMetrics;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import com.chua.common.support.utils.StringUtils;

/**
 * FRP 单端口服务器：认证信令与 frp 数据平面共用同一监听端口。
 *
 * <p>继承 {@link AbstractServer} 获得统一生命周期与配置管理，实现 {@link TcpServer} 接口
 * 复用 TCP 服务抽象，底层由 {@link JdkTcpServer} 流式模式承载连接。</p>
 *
 * <p>连接建立后首行握手：</p>
 * <ul>
 *   <li>{@code AUTH|clientId|host|port|signature} → 认证连接，
 *       验签通过后返回 {@code TOKEN|token} 并保持为信令长连接，后续按行收发信令</li>
 *   <li>{@code CONNECT|channelId|role|signature} → 数据平面连接，
 *       携带会话 token 派生的签名校验，通过后与对端裸字节流双向桥接（TCP 代理模式）</li>
 * </ul>
 *
 * <p>映射规则为显式三元组（目标地址、目标端口、本地端口）：</p>
 * <ul>
 *   <li>{@code EXPOSE}（提供方）：本机 {@code localPort} 上的服务以
 *       {@code targetHost:targetPort} 为逻辑标识对外提供；</li>
 *   <li>{@code MAP}（访问方）：本机监听 {@code localPort}，访问它即访问远端
 *       {@code targetHost:targetPort} 服务。</li>
 * </ul>
 * 服务端按「{@code targetHost:targetPort} → 提供方 clientId」精确路由，不做任何猜测匹配。
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public class FrpServer extends AbstractServer implements TcpServer {

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
     * 暴露规则表（targetHost:targetPort -> 提供方 clientId）
     */
    private final Map<String, String> exposeIndex = new ConcurrentHashMap<>();

    /**
     * 暴露规则详情表（ruleId -> 规则）
     */
    private final Map<String, ExposeRule> exposeRules = new ConcurrentHashMap<>();

    /**
     * 映射规则表（ruleId -> 访问方 clientId）
     */
    private final Map<String, MapRule> mapRules = new ConcurrentHashMap<>();

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
     * 使用默认配置创建 FRP 服务器。
     */
    public FrpServer() {
        this(FrpConfig.defaults());
    }

    /**
     * 使用指定配置创建 FRP 服务器。
     *
     * @param config 服务器配置
     */
    public FrpServer(FrpConfig config) {
        super(serverSetting(config));
        this.token = config.getToken();
    }

    /**
     * 服务端Setting。
     *
     * @param config 配置，不允许为 null
     * @return 服务端Setting 对象
     */
    private static ServerSetting serverSetting(FrpConfig config) {
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
        log.info("FRP 单端口服务器启动成功: {}:{}", setting.getHost(), setting.getPort());
    }

    /**
     * 获取指标：FRP 的连接全部由内部 {@link JdkTcpServer} 承载。
     *
     * <p>FRP 自身不处理 HTTP 语义、也不走 {@code handleRequest} 热路径，
     * 基类那份 {@link ServerMetrics} 在这里恒为 0。若不转发，监控看到的
     * 「活跃连接数 / 峰值连接数 / 拒绝数」永远是 0，反而掩盖真实容量。</p>
     *
     * @return 内部 TCP 服务器的指标；尚未启动时返回基类实例
     */
    @Override
    public ServerMetrics getMetrics() {
        JdkTcpServer server = this.tcpServer;
        return server != null ? server.getMetrics() : super.getMetrics();
    }

    /**
     * 获取活跃连接数（转发到内部 TCP 服务器）。
     *
     * @return 活跃连接数
     */
    @Override
    public int getActiveConnections() {
        return getMetrics().getActiveConnections();
    }

    /**
     * 获取峰值连接数（转发到内部 TCP 服务器）。
     *
     * @return 峰值连接数
     */
    @Override
    public int getPeakConnections() {
        return getMetrics().getPeakConnections();
    }

    /**
     * 是否仍接受新连接：由内部 TCP 服务器的接纳开关决定。
     *
     * @return true 表示可以接受新连接
     */
    @Override
    public boolean isAcceptingConnections() {
        JdkTcpServer server = this.tcpServer;
        return server != null ? server.isAcceptingConnections() : super.isAcceptingConnections();
    }

    /**
     * 停止接纳：转发到内部 {@link JdkTcpServer}。
     *
     * <p>基类 {@code stop()} 先用 {@code doStopAccepting()} 摘接纳、再等存量请求。
     * FRP 的连接都在内部 TCP 服务器上，若不转发，等待期间仍会不断建连，
     * 优雅关闭就永远等不到活跃数归零。</p>
     */
    @Override
    protected void doStopAccepting() {
        JdkTcpServer server = this.tcpServer;
        if (server != null) {
            server.setAccepting(false);
        }
    }

    /**
     * 恢复接纳：转发到内部 {@link JdkTcpServer}。
     */
    @Override
    protected void doStartAccepting() {
        JdkTcpServer server = this.tcpServer;
        if (server != null) {
            server.setAccepting(true);
        }
    }

    /**
     * 停止服务器的具体逻辑。
     */
    @Override
    protected void doStop() {
        // 通知所有在线客户端即将停机
        for (String clientId : registry.keySet()) {
            send(clientId, FrpProtocol.line(FrpProtocol.PREFIX_ERROR, "server-shutdown", "服务器停机"));
        }
        if (tcpServer != null) {
            try {
                tcpServer.stop();
            } catch (Exception e) {
                log.debug("[frp] 操作失败，已忽略: {}", e.getMessage());
            }
        }
        for (DataChannel channel : dataChannels.values()) {
            channel.close();
        }
        dataChannels.clear();
        registry.clear();
        sessionTokens.clear();
        exposeIndex.clear();
        exposeRules.clear();
        mapRules.clear();
        tunnelChannels.clear();
    }

    /**
     * 获取服务器配置。
     *
     * @return 配置实例
     */
    public FrpConfig getConfig() {
        return FrpConfig.builder()
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
     * 获取当前已注册的暴露规则快照（targetHost:targetPort -> 提供方 clientId）。
     *
     * @return 暴露规则快照
     */
    public Map<String, String> getExposeIndex() {
        return Map.copyOf(exposeIndex);
    }

    /**
     * 获取全部暴露规则详情（服务端统管口径，供管理端查看）。
     *
     * @return 规则列表：客户端、目标、本地端口
     */
    public List<ExposeRuleView> listExposeRules() {
        return exposeRules.values().stream()
                .map(rule -> new ExposeRuleView(rule.ruleId(), rule.clientId(), rule.targetHost(), rule.targetPort(),
                        rule.localPort()))
                .sorted(java.util.Comparator.comparing(ExposeRuleView::clientId)
                        .thenComparing(ExposeRuleView::targetHost))
                .toList();
    }

    /**
     * 获取全部映射规则详情（服务端统管口径，供管理端查看）。
     *
     * @return 规则列表：客户端、目标、本地端口
     */
    public List<MapRuleView> listMapRules() {
        return mapRules.values().stream()
                .map(rule -> new MapRuleView(rule.ruleId(), rule.clientId(), rule.targetHost(), rule.targetPort(),
                        rule.localPort()))
                .sorted(java.util.Comparator.comparing(MapRuleView::clientId)
                        .thenComparing(MapRuleView::targetHost))
                .toList();
    }

    /**
     * 服务端主动撤销某客户端的暴露规则（管理端操作）。
     *
     * @param clientId 客户端标识
     * @param ruleId   规则标识
     * @return true 表示撤销成功
     */
    public boolean revokeExpose(String clientId, String ruleId) {
        ExposeRule rule = exposeRules.get(ruleId);
        if (rule == null || !rule.clientId().equals(clientId)) {
            return false;
        }
        exposeRules.remove(ruleId);
        exposeIndex.remove(rule.key());
        // 下推撤销给客户端，避免重连时把它重放回来
        SignalConnection conn = registry.get(clientId);
        if (conn != null) {
            send(clientId, FrpProtocol.line(FrpProtocol.PREFIX_EXPOSE_DEL, conn.token(), ruleId));
        }
        log.info("FRP 暴露规则被服务端撤销: {} -> {} (by {})", rule.key(), clientId, clientId);
        return true;
    }

    /**
     * 服务端主动撤销某客户端的映射规则（管理端操作）。
     *
     * @param clientId 客户端标识
     * @param ruleId   规则标识
     * @return true 表示撤销成功
     */
    public boolean revokeMap(String clientId, String ruleId) {
        MapRule rule = mapRules.get(ruleId);
        if (rule == null || !rule.clientId().equals(clientId)) {
            return false;
        }
        mapRules.remove(ruleId);
        SignalConnection conn = registry.get(clientId);
        if (conn != null) {
            send(clientId, FrpProtocol.line(FrpProtocol.PREFIX_MAP_DEL, conn.token(), ruleId));
        }
        log.info("FRP 映射规则被服务端撤销: {}:{} (by {})", rule.targetHost(), rule.targetPort(), clientId);
        return true;
    }

    /**
     * 服务端向指定客户端推送一条新规则（管理端在专属 UI 里新增）。
     *
     * <p>客户端收到后会把规则登记到本地并按需起停本地监听，
     * 规则生效回执走 EXPOSE_OK / MAP_OK。</p>
     *
     * @param clientId   客户端标识
     * @param ruleId     规则标识
     * @param targetHost 目标地址
     * @param targetPort 目标端口
     * @param localPort  本地端口
     * @param expose     是否为 EXPOSE（否则为 MAP）
     * @return true 表示已推送
     */
    public boolean pushRule(String clientId, String ruleId, String targetHost, int targetPort,
                            int localPort, boolean expose) {
        SignalConnection conn = registry.get(clientId);
        if (conn == null) {
            return false;
        }
        // 服务端同步登记：管理端新增的规则立即可查、可路由
        String key = targetHost + ":" + targetPort;
        if (expose) {
            ExposeRule previous = exposeRules.get(ruleId);
            if (previous != null) {
                exposeIndex.remove(previous.key());
            }
            exposeRules.put(ruleId, new ExposeRule(ruleId, key, clientId,
                    targetHost, targetPort, localPort));
            exposeIndex.put(key, clientId);
        } else {
            mapRules.put(ruleId, new MapRule(ruleId, targetHost, targetPort, localPort, clientId));
        }
        String prefix = expose ? FrpProtocol.PREFIX_EXPOSE : FrpProtocol.PREFIX_MAP;
        conn.send(FrpProtocol.line(prefix, conn.token(), ruleId, targetHost,
                String.valueOf(targetPort), String.valueOf(localPort)));
        return true;
    }

    /**
     * 注册客户端连接回调。
     *
     * @param listener 连接回调，参数为客户端标识
     * @return 当前服务器实例，支持链式调用
     */
    public FrpServer onConnect(Consumer<String> listener) {
        connectListeners.add(listener);
        return this;
    }

    /**
     * 注册客户端断开回调。
     *
     * @param listener 断开回调，参数为客户端标识
     * @return 当前服务器实例，支持链式调用
     */
    public FrpServer onDisconnect(Consumer<String> listener) {
        disconnectListeners.add(listener);
        return this;
    }

    /**
     * 注册帧处理器（TcpServer 接口，FRP 使用流式协议，此处直接返回当前实例）。
     *
     * @param handler 帧处理器
     * @return 当前实例
     */
    @Override
    public FrpServer setHandler(TcpServerHandler handler) {
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
            if (StringUtils.isBlank(firstLine)) {
                return;
            }
            if (firstLine.startsWith(FrpProtocol.PREFIX_AUTH + FrpProtocol.SEPARATOR)) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                handleSignalConnection(reader, out, firstLine);
            } else if (firstLine.startsWith(FrpProtocol.PREFIX_CONNECT + FrpProtocol.SEPARATOR)) {
                handleDataConnection(in, out, firstLine);
            }
        } catch (IOException e) {
            log.debug("FRP 连接处理异常: {}", e.getMessage());
        }
    }

    /**
     * 逐字节读取首行（遇换行停止），不预读缓冲后续字节，保证数据平面业务字节不被吞掉。
     *
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
                    log.warn("FRP 首行超长，拒绝连接: {} bytes", buffer.size());
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
            log.warn("FRP 认证签名校验失败，拒绝接入: clientId={}", clientId);
            writeLine(out, FrpProtocol.line(FrpProtocol.PREFIX_ERROR, "auth", "签名校验失败"));
            return;
        }
        String sessionToken = UUID.randomUUID().toString();
        sessionTokens.put(sessionToken, clientId);
        PrintWriter writer = new PrintWriter(out, true, StandardCharsets.UTF_8);
        SignalConnection conn = new SignalConnection(clientId, host, port, sessionToken, writer);
        SignalConnection previous = registry.put(clientId, conn);
        if (previous != null) {
            // 同一 clientId 重复接入：踢掉旧连接，防止注册表互相覆盖导致隧道串线
            log.warn("FRP 客户端重复接入，踢掉旧连接: clientId={}", clientId);
            sessionTokens.remove(previous.token());
            closeQuietly(previous);
            disconnectRulesOf(clientId);
        }
        writeLine(out, FrpProtocol.line(FrpProtocol.PREFIX_TOKEN, sessionToken));
        notifyConnectListeners(clientId);
        log.info("FRP 客户端认证接入: {} @ {}:{}", clientId, host, port);
        try {
            String line;
            while (running && (line = reader.readLine()) != null) {
                handleSignal(clientId, line);
            }
        } catch (IOException e) {
            // 客户端异常断开（进程被杀 / 网络闪断）：readLine 抛 IOException 时
            // while 循环被中断，若不清理 registry，断开客户端的 clientId 会残留成
            // 「在线 N 客户端」的假计数。这里与正常 EOF 走同一清理路径。
            log.debug("FRP 客户端连接异常中断，清理登记: clientId={}, err={}", clientId, e.getMessage());
        } finally {
            // 引用守卫：同一 clientId 重接踢旧时，旧线程的 finally 不得误删新连接。
            if (registry.get(clientId) == conn) {
                onSignalClosed(clientId);
            }
        }
    }

    /**
     * 处理信令命令。
     *
     * @param clientId 客户端标识
     * @param line     命令行
     */
    private void handleSignal(String clientId, String line) {
        try {
            if (line.startsWith(FrpProtocol.PREFIX_EXPOSE + FrpProtocol.SEPARATOR)) {
                String[] parts = line.split("\\|", 6);
                if (!matchesToken(clientId, parts, 1)) {
                    return;
                }
                if (parts.length < 6) {
                    return;
                }
                registerExpose(clientId, parts);
            } else if (line.startsWith(FrpProtocol.PREFIX_MAP + FrpProtocol.SEPARATOR)) {
                String[] parts = line.split("\\|", 6);
                if (!matchesToken(clientId, parts, 1)) {
                    return;
                }
                if (parts.length < 6) {
                    return;
                }
                registerMap(clientId, parts);
            } else if (line.startsWith(FrpProtocol.PREFIX_EXPOSE_DEL + FrpProtocol.SEPARATOR)) {
                String[] parts = line.split("\\|", 3);
                if (!matchesToken(clientId, parts, 1)) {
                    return;
                }
                unregisterExpose(clientId, parts.length > 2 ? parts[2].trim() : "");
            } else if (line.startsWith(FrpProtocol.PREFIX_MAP_DEL + FrpProtocol.SEPARATOR)) {
                String[] parts = line.split("\\|", 3);
                if (!matchesToken(clientId, parts, 1)) {
                    return;
                }
                unregisterMap(clientId, parts.length > 2 ? parts[2].trim() : "");
            } else if (line.startsWith(FrpProtocol.PREFIX_OPEN + FrpProtocol.SEPARATOR)) {
                String[] parts = line.split("\\|", 5);
                if (!matchesToken(clientId, parts, 1)) {
                    return;
                }
                handleOpen(clientId, parts);
            } else if (line.startsWith(FrpProtocol.PREFIX_CLOSE + FrpProtocol.SEPARATOR)) {
                String[] parts = line.split("\\|", 3);
                if (!matchesToken(clientId, parts, 1)) {
                    return;
                }
                handleClose(clientId, parts);
            } else if (line.startsWith(FrpProtocol.PREFIX_PING + FrpProtocol.SEPARATOR)) {
                // 心跳探活：回 PONG 确认连接存活
                String[] parts = line.split("\\|", 3);
                String token = parts.length > 1 ? parts[1] : "";
                if (matchesToken(clientId, parts, 1)) {
                    send(clientId, FrpProtocol.line(FrpProtocol.PREFIX_PONG, token));
                }
            } else {
                log.debug("忽略未知 FRP 信令: {}", line);
            }
        } catch (Exception e) {
            log.warn("FRP 信令处理异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 校验信令行携带的会话令牌与当前连接一致，防止伪造命令。
     *
     * @param clientId 客户端标识
     * @param parts    已按分隔符拆分的信令字段
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
            log.warn("FRP 信令令牌校验失败，拒绝: clientId={}, command={}", clientId,
                    parts[0]);
            return false;
        }
        return true;
    }

    /**
     * 注册暴露规则（EXPOSE|token|ruleId|targetHost|targetPort|localPort）。
     *
     * @param clientId 提供方客户端标识
     * @param parts    信令字段
     */
    private void registerExpose(String clientId, String[] parts) {
        String ruleId = parts[2];
        String targetHost = parts[3];
        int targetPort = parseInt(parts[4]);
        int localPort = parseInt(parts[5]);
        String key = targetHost + ":" + targetPort;
        ExposeRule previous = exposeRules.get(ruleId);
        if (previous != null) {
            exposeIndex.remove(previous.key());
        }
        exposeRules.put(ruleId, new ExposeRule(ruleId, key, clientId, targetHost, targetPort, localPort));
        exposeIndex.put(key, clientId);
        send(clientId, FrpProtocol.line(FrpProtocol.PREFIX_EXPOSE_OK, ruleId));
        log.info("FRP 暴露规则注册: {} -> [{}] (localPort={})", key, clientId, localPort);
    }

    /**
     * 撤销暴露规则。
     *
     * @param clientId 提供方客户端标识
     * @param ruleId   规则标识
     */
    private void unregisterExpose(String clientId, String ruleId) {
        ExposeRule rule = exposeRules.remove(ruleId);
        if (rule != null && rule.clientId().equals(clientId)) {
            exposeIndex.remove(rule.key());
            log.info("FRP 暴露规则撤销: {} -> {}", rule.key(), clientId);
        }
    }

    /**
     * 注册映射规则（MAP|token|ruleId|targetHost|targetPort|localPort）。
     *
     * @param clientId 访问方客户端标识
     * @param parts    信令字段
     */
    private void registerMap(String clientId, String[] parts) {
        String ruleId = parts[2];
        String targetHost = parts[3];
        int targetPort = parseInt(parts[4]);
        int localPort = parseInt(parts[5]);
        mapRules.put(ruleId, new MapRule(ruleId, targetHost, targetPort, localPort, clientId));
        send(clientId, FrpProtocol.line(FrpProtocol.PREFIX_MAP_OK, ruleId));
        log.info("FRP 映射规则注册: [{}] -> {}:{} (localPort={})", clientId,
                targetHost, targetPort, localPort);
    }

    /**
     * 撤销映射规则。
     *
     * @param clientId 访问方客户端标识
     * @param ruleId   规则标识
     */
    private void unregisterMap(String clientId, String ruleId) {
        MapRule rule = mapRules.get(ruleId);
        if (rule != null && rule.clientId().equals(clientId)) {
            mapRules.remove(ruleId);
            log.info("FRP 映射规则撤销: {} -> {}:{}", clientId, rule.targetHost(), rule.targetPort());
        }
    }

    /**
     * 处理隧道开启请求：按目标精确路由到注册了该目标的提供方。
     *
     * @param clientId 访问方客户端标识
     * @param parts    信令字段（OPEN|token|requestId|targetHost|targetPort）
     */
    private void handleOpen(String clientId, String[] parts) {
        String requestId = parts.length > 2 ? parts[2] : "";
        String targetHost = parts.length > 3 ? parts[3] : "";
        int targetPort = parts.length > 4 ? parseInt(parts[4]) : 0;
        if (targetHost.isEmpty() || targetPort <= 0) {
            send(clientId, FrpProtocol.line(FrpProtocol.PREFIX_ERROR, requestId, "目标格式非法"));
            return;
        }
        String key = targetHost + ":" + targetPort;
        String providerId = exposeIndex.get(key);
        if (providerId == null) {
            send(clientId, FrpProtocol.line(FrpProtocol.PREFIX_ERROR, requestId,
                    "no provider for target: " + key));
            return;
        }
        if (providerId.equals(clientId)) {
            send(clientId, FrpProtocol.line(FrpProtocol.PREFIX_ERROR, requestId,
                    "cannot open own expose: " + key));
            return;
        }
        SignalConnection provider = registry.get(providerId);
        if (provider == null) {
            send(clientId, FrpProtocol.line(FrpProtocol.PREFIX_ERROR, requestId,
                    "provider offline: " + key));
            return;
        }
        String channelId = UUID.randomUUID().toString();
        tunnelChannels.put(channelId, new TunnelChannel(clientId, providerId));
        dataChannels.computeIfAbsent(channelId, DataChannel::new);
        provider.send(FrpProtocol.line(FrpProtocol.PREFIX_TUNNEL_OPEN, provider.token(),
                channelId, targetHost, String.valueOf(targetPort)));
        send(clientId, FrpProtocol.line(FrpProtocol.PREFIX_OPENED, requestId, channelId));
        log.info("FRP 隧道建立: {} <-> {} via {}", clientId, providerId, channelId);
    }

    /**
     * 处理隧道关闭。
     *
     * @param clientId 关闭方客户端标识
     * @param parts    信令字段（CLOSE|token|channelId）
     */
    private void handleClose(String clientId, String[] parts) {
        String channelId = parts.length > 2 ? parts[2].trim() : "";
        closeChannel(channelId);
    }

    /**
     * 处理数据平面连接：会话 token 派生的签名校验后与对端桥接裸字节流。
     *
     * @param in        输入流
     * @param out       输出流
     * @param firstLine 连接握手行（CONNECT|channelId|role|signature）
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
            log.warn("FRP 数据平面通道不存在: channelId={}", channelId);
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
            log.warn("FRP 数据平面签名校验失败: channelId={}, role={}", channelId, role);
            return;
        }
        DataChannel dataChannel = dataChannels.get(channelId);
        if (dataChannel == null) {
            return;
        }
        dataChannel.bind(role, in, out);
        log.debug("FRP 数据平面连接绑定: channel={}, role={}", channelId, role);
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
        disconnectRulesOf(clientId);
        log.info("FRP 客户端断开: {}", clientId);
    }

    /**
     * 清理某客户端的映射规则与参与的通道路由（服务端主动踢连接时复用）。
     *
     * @param clientId 客户端标识
     */
    private void disconnectRulesOf(String clientId) {
        // 移除该客户端提供的暴露规则
        List<String> removedExposeKeys = exposeIndex.entrySet().stream()
                .filter(entry -> entry.getValue().equals(clientId))
                .map(Map.Entry::getKey)
                .toList();
        removedExposeKeys.forEach(exposeIndex::remove);
        exposeRules.entrySet().removeIf(entry -> entry.getValue().clientId().equals(clientId));
        // 移除该客户端的映射规则
        mapRules.entrySet().removeIf(entry -> entry.getValue().clientId().equals(clientId));
        // 关闭该客户端参与的隧道
        List<String> closed = new java.util.ArrayList<>();
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
        } catch (Exception e) {
            log.debug("[frp] 操作失败，已忽略: {}", e.getMessage());
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
        // CLOSE 行携带对端会话 token（客户端按 CLOSE|token|channelId 三字段解析）
        SignalConnection a = registry.get(channel.aId());
        if (a != null) {
            send(channel.aId(), FrpProtocol.line(FrpProtocol.PREFIX_CLOSE, a.token(), channelId));
        }
        SignalConnection b = registry.get(channel.bId());
        if (b != null) {
            send(channel.bId(), FrpProtocol.line(FrpProtocol.PREFIX_CLOSE, b.token(), channelId));
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
            log.warn("FRP 信令写入失败，清理僵尸连接: clientId={}", clientId);
            registry.remove(clientId);
            sessionTokens.remove(conn.token());
            disconnectRulesOf(clientId);
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
            log.debug("FRP 写响应异常: {}", e.getMessage());
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
                log.error("FRP 连接回调异常", e);
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
                log.error("FRP 断开回调异常", e);
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
     * @return 结果值
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
     * 暴露规则详情。
     *
     * @param ruleId     规则标识
     * @param key        目标键（targetHost:targetPort）
     * @param clientId   提供方客户端标识
     * @param targetHost 目标地址
     * @param targetPort 目标端口
     * @param localPort  本机服务端口
     * @return 结果值
     */
    private record ExposeRule(String ruleId, String key, String clientId,
                              String targetHost, int targetPort, int localPort) {
    }

    /**
     * 暴露规则视图（管理端只读快照）。
     *
     * @param ruleId     规则标识
     * @param clientId   提供方客户端标识
     * @param targetHost 目标地址
     * @param targetPort 目标端口
     * @param localPort  本机服务端口
     * @return 结果值
     */
    public record ExposeRuleView(String ruleId, String clientId, String targetHost, int targetPort, int localPort) {
    }

    /**
     * 映射规则详情。
     *
     * @param ruleId     规则标识
     * @param targetHost 目标地址
     * @param targetPort 目标端口
     * @param localPort  本机监听端口
     * @param clientId   访问方客户端标识
     * @return 结果值
     */
    private record MapRule(String ruleId, String targetHost, int targetPort, int localPort, String clientId) {
    }

    /**
     * 映射规则视图（管理端只读快照）。
     *
     * @param ruleId     规则标识
     * @param clientId   访问方客户端标识
     * @param targetHost 目标地址
     * @param targetPort 目标端口
     * @param localPort  本机监听端口
     * @return 结果值
     */
    public record MapRuleView(String ruleId, String clientId, String targetHost, int targetPort, int localPort) {
    }

    /**
     * 隧道通道两端路由信息。
     *
     * @param aId 访问方客户端标识
     * @param bId 服务提供方客户端标识
     * @return 结果值
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
        private final CountDownLatch closedLatch = new CountDownLatch(1);

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
            if (FrpProtocol.ROLE_VISITOR.equals(role)) {
                this.visitor = endpoint;
            } else if (FrpProtocol.ROLE_PROVIDER.equals(role)) {
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
            ThreadUtils.startVirtualThread("frp-data-bridge-" + channelId + "-" + role, () -> {
                try {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while (!closed && (n = source.in.read(buf)) != -1) {
                        synchronized (target.out) {
                            target.out.write(buf, 0, n);
                            target.out.flush();
                        }
                    }
                } catch (IOException e) {
                    log.debug("[frp] 关闭流失败，已忽略: {}", e.getMessage());
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
             * 关闭端点。
             */
            void close() {
                try {
                    in.close();
                } catch (IOException e) {
                    log.debug("[frp] 关闭流失败，已忽略: {}", e.getMessage());
                }
                try {
                    out.close();
                } catch (IOException e) {
                    log.debug("[frp] 关闭流失败，已忽略: {}", e.getMessage());
                }
            }
        }
    }
}