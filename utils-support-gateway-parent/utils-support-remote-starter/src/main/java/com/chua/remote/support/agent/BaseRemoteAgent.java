package com.chua.remote.support.agent;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.remote.support.spi.RemoteTransportProvider;
import com.chua.remote.support.agent.launch.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.LineBasedFrameDecoder;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 远程控制 Agent 抽象基类
 * 负责连接 Gateway、注册、心跳、消息分发 * @author CH
 */
public abstract class BaseRemoteAgent {

    private static final Logger log = LoggerFactory.getLogger(BaseRemoteAgent.class);

    // properties
    protected final AgentProperties properties;
    // mapper
    public final ObjectMapper mapper = new ObjectMapper();
    // sessions
    public final Map<String, AgentSessionContext> sessions = new ConcurrentHashMap<>();

    // Worker线程组
    protected EventLoopGroup workerGroup;
    // agent channel
    protected Channel agentChannel;
    // scheduler
    protected ScheduledExecutorService scheduler;
    // 运行状态标志
    protected volatile boolean running;
    private volatile int reconnectAttempt;
    private ScheduledExecutorService reconnectExecutor;
    /** 上次收到心跳响应的时间戳，用于检测网关端心跳超时 */
    private volatile long lastHeartbeatResponseAt;
    /** 心跳间隔（秒），从注册响应中获取 */
    private volatile int heartbeatInterval = 30;
    /** SPI 远程传输提供者映射（transport名 -> 提供者） */
    protected Map<String, RemoteTransportProvider> transportProviders = new ConcurrentHashMap<>();
    /** 每个会话使用的传输协议（sessionId -> transport名） */
    protected final Map<String, String> sessionTransports = new ConcurrentHashMap<>();

    public BaseRemoteAgent(AgentProperties properties) {
        this.properties = properties;
    }

    /**
     * 连接 Gateway 并注册
     */
    public void start() throws Exception {
        running = true;
        // 清理旧资源，避免重连时资源泄漏
        if (agentChannel != null) {
            try {
                if (agentChannel.isActive()) {
                    agentChannel.close();
                }
            }
 catch (Exception ignored) {
                log.trace("操作失败", ignored);
            }
            agentChannel = null;
        }
        if (workerGroup == null || workerGroup.isShuttingDown() || workerGroup.isShutdown()) {
            if (workerGroup != null) {
                try {
                    workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).await(2000);
                }
 catch (Exception ignored) {
                    log.trace("操作失败", ignored);
                }
            }
            workerGroup = new NioEventLoopGroup(1);
        }

        Bootstrap b = new Bootstrap();
        b.group(workerGroup).channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    /**
                     * initChannel
                     * @param ch 参数
                     */
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                new LineBasedFrameDecoder(2097152),
                                new StringDecoder(StandardCharsets.UTF_8),
                                new StringEncoder(StandardCharsets.UTF_8),
                                new AgentMessageHandler());
                    }
                });

        ChannelFuture f = b.connect(properties.getGatewayHost(), properties.getGatewayPort()).sync();
        agentChannel = f.channel();
        log.info("Agent 已连接 Gateway: {}:{}", properties.getGatewayHost(), properties.getGatewayPort());

        // 初始化所有非 TCP 传输提供者
        initRemoteTransportProviders();

        // 注册成功回调由消息处理触发，不在这里阻塞等待
    }

    /**
     * 初始化所有非 TCP 传输提供者
     * 遍历 properties.getTransports()，为每个非 TCP 传输加载对应的 SPI 实现
     */
    protected void initRemoteTransportProviders() {
        for (String transport : properties.getTransports()) {
            if ("TCP".equalsIgnoreCase(transport)) {
                continue;
                // TCP 无需 SPI;
            }
            try {
                RemoteTransportProvider provider = ServiceProvider.of(RemoteTransportProvider.class)
                        .getExtension(transport.toLowerCase());
                if (provider != null) {
                    java.util.Map<String, Object> options = new java.util.HashMap<>();
                    options.put("agentId", properties.getAgentId());
                    if (provider.connect(properties.getGatewayHost(), properties.getGatewayPort(), options)) {
                        transportProviders.put(transport.toUpperCase(), provider);
                        log.info("SPI 传输提供者已初始化: transport={} provider={}",
                                transport, provider.getClass().getSimpleName());
                    }
 else {
                        log.warn("SPI 传输提供者 connect 返回 false: transport={}", transport);
                    }
                }
 else {
                    log.warn("未找到 transport={} 的 SPI 实现", transport);
                }
            }
 catch (Exception e) {
                log.warn("SPI 传输提供者初始化失败: transport={} {}", transport, e.getMessage());
            }
        }
    }

    /**
     * 处理 Gateway 发送的连接请求
     */
    protected abstract void handleConnect(String sessionId, String protocol,
                                           Map<String, Object> target, Map<String, Object> auth);

    /**
     * 处理断开请求
     */
    protected abstract void handleDisconnect(String sessionId);

    /**
     * 处理输入数据（SSH 键盘输入 / 桌面鼠标事件等）
     */
    protected abstract void handleInput(String sessionId, String type, Map<String, Object> payload);

    /**
     * 向 Gateway 发送 JSON 消息
     */
    public void sendToGateway(String json) {
        // SPI 传输优先
        for (RemoteTransportProvider provider : transportProviders.values()) {
            if (provider.isActive() && provider.sendText(json + "\n")) {
                return;
            }
        }
        // 默认 TCP
        if (agentChannel != null && agentChannel.isActive()) {
            agentChannel.writeAndFlush(json + "\n");
            log.debug("[Agent→GW] sent {} bytes", json.length());
        }
 else {
            log.warn("[Agent→GW] DROPPED: channel={} active={}", agentChannel,
                    agentChannel != null ? agentChannel.isActive() : "null");
        }
    }

    /**
     * 向 Gateway 发送桌面二进制帧
     */
    public void sendDesktopFrame(String sessionId, int width, int height, boolean keyFrame, byte[] h264Data) {
        // base64 编码后以 JSON 发送
        String b64 = java.util.Base64.getEncoder().encodeToString(h264Data);
        try {
            String json = mapper.writeValueAsString(Map.of("type", "desktop_frame",
                    "sessionId", sessionId,
                    "width", width,
                    "height", height,
                    "keyFrame", keyFrame,
                    "data", b64));
            sendToGateway(json);
        }
 catch (Exception e) {
            log.warn("发送桌面帧失败: {}", e.getMessage());
        }
    }

    /**
     * 向 Gateway 发送纯二进制帧
     * TCP协议: [1B magic=0xBF][4B payloadLen][1B frameType][2B sidLen][sid...][2B w][2B h][1B keyFrame][encoded data]
     * @param frameType 帧类型: 0xDF=H264, 0xDJ=JPEG
     */
    public void sendBinaryFrame(byte frameType, String sessionId, int width, int height, boolean keyFrame, byte[] encodedData) {
        // 根据会话的传输协议选择 SPI 提供者
        String sessionTransport = sessionTransports.get(sessionId);
        if (sessionTransport != null) {
            RemoteTransportProvider provider = transportProviders.get(sessionTransport);
            if (provider != null && provider.isActive()) {
                if (provider.sendBinaryFrame(frameType, sessionId, width, height, keyFrame, encodedData)) {
                    return;
                }
            }
        }
        // 默认 TCP
        if (agentChannel != null && agentChannel.isActive()) {
            try {
                byte[] sidBytes = sessionId.getBytes(StandardCharsets.UTF_8);
                int payloadLen = 1 + 2 + sidBytes.length + 2 + 2 + 1 + encodedData.length;
                ByteBuf buf = Unpooled.buffer(5 + payloadLen);
                buf.writeByte(0xBF);
                // magic;
                buf.writeInt(payloadLen);
                // total payload length (excludes magic+len);
                buf.writeByte(frameType);
                // frame type;
                buf.writeShort(sidBytes.length);
                buf.writeBytes(sidBytes);
                buf.writeShort(width);
                buf.writeShort(height);
                buf.writeBoolean(keyFrame);
                buf.writeBytes(encodedData);
                agentChannel.writeAndFlush(buf);
                log.debug("[Agent→GW] binary frame: type={} sid={} {}x{} len={}",
                        String.format("0x%02X", frameType), sessionId, width, height, encodedData.length);
            }
 catch (Exception e) {
                log.warn("发送二进制帧失败: {}", e.getMessage());
            }
        }
 else {
            log.warn("[Agent→GW] DROPPED binary: channel={} active={}", agentChannel,
                    agentChannel != null ? agentChannel.isActive() : "null");
        }
    }

    /**
     * 停止 Agent
     */
    public void stop() {
        running = false;
        for (RemoteTransportProvider provider : transportProviders.values()) {
            try { provider.close(); }
 catch (Exception ignored) { log.trace("关闭 provider 失败", ignored); }
        }
        transportProviders.clear();
        sessionTransports.clear();
        if (reconnectExecutor != null) { reconnectExecutor.shutdownNow(); }
        if (scheduler != null) { scheduler.shutdownNow(); }
        if (agentChannel != null) { agentChannel.close(); }
        if (workerGroup != null) { workerGroup.shutdownGracefully(0, 3, TimeUnit.SECONDS); }
        log.info("Agent 已停止");
    }

    /**
     * 安排重连任务（指数退避，上限 30 秒）
     */
    private void scheduleReconnect() {
        if (!running) { return; }
        if (reconnectExecutor == null || reconnectExecutor.isShutdown()) {
            reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "agent-reconnect");
                t.setDaemon(true);
                return t;
            });
        }
        long delayMs = Math.min(1000L * (1L << Math.min(reconnectAttempt, 5)), 30_000);
        reconnectAttempt++;
        log.info("将在 {}ms 后尝试第 {} 次重连...", delayMs, reconnectAttempt);
        reconnectExecutor.schedule(() -> {
            if (!running) { return; }
            try {
                log.info("尝试重新连接 Gateway (第 {} 次)...", reconnectAttempt);
                start();
            }
 catch (Exception e) {
                log.warn("重连失败 (第 {} 次): {}", reconnectAttempt, e.getMessage());
                scheduleReconnect();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Agent 会话上下文
     */
    public static class AgentSessionContext {
        /**
         * 会话 ID
         */
        private final String sessionId;
        private final String protocol;
        private final Map<String, Object> auth;
        /**
         * 是否激活
         */
        private volatile boolean active = true;

        public AgentSessionContext(String sessionId, String protocol, Map<String, Object> auth) {
            this.sessionId = sessionId;
            this.protocol = protocol;
            this.auth = auth;
        }

        /**
         * 获取会话ID
         * @return 获取会话ID结果
         */
        public String getSessionId() { return sessionId; }
        /**
         * 获取协议
         * @return 获取协议结果
         */
        public String getProtocol() { return protocol; }
        /**
         * getAuth
         * @return getAuth结果
         */
        public Map<String, Object> getAuth() { return auth; }
        /**
         * isActive
         * @return isActive结果
         */
        public boolean isActive() { return active; }
        /**
         * setActive
         * @param active 参数
         */
        public void setActive(boolean active) { this.active = active; }
    }

    /**
     * 消息处理器
     */
    private class AgentMessageHandler extends SimpleChannelInboundHandler<String> {

        private boolean registered;
        private boolean registerSent;

        /**
         * channelActive
         * @param ctx 参数
         */
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            log.info("TCP 连接已建立，发送注册请求...");
            sendRegister(ctx);
        }

        /**
         * channelRead0
         * @param ctx 参数
         * @param msg 参数
         */
        @Override
        @SuppressWarnings("unchecked")
        protected void channelRead0(ChannelHandlerContext ctx, String msg) {
            handleMessage(ctx, msg);
        }

        @SuppressWarnings("unchecked")
        private void handleMessage(ChannelHandlerContext ctx, String msg) {
            try {
                Map<String, Object> json = mapper.readValue(msg, Map.class);
                String type = (String) json.get("type");

                if ("challenge".equals(type)) {
                    sendRegister(ctx);

                } else if ("register".equals(type)) {
                    String status = (String) json.get("status");
                    if ("OK".equals(status)) {
                        registered = true;
                        reconnectAttempt = 0;
                        lastHeartbeatResponseAt = System.currentTimeMillis();
                        Number interval = (Number) json.get("heartbeat_interval");
                        int hbInterval = interval != null ? interval.intValue() : 30;
                        Object verifyCode = json.get("verify_code");
                        if (verifyCode != null && !String.valueOf(verifyCode).isBlank()) {
                            properties.setVerifyCode(String.valueOf(verifyCode));
                        }
                        log.info("Agent 注册成功, 心跳间隔={}s", hbInterval);
                        onRegistered();
                        startHeartbeat(hbInterval);
                    }
 else {
                        log.error("Agent 注册失败: {}", status);
                        ctx.close();
                    }

                } else if ("heartbeat".equals(type)) {
                    String st = (String) json.get("status");
                    lastHeartbeatResponseAt = System.currentTimeMillis();
                    log.debug("心跳响应: {}", st);

                } else if ("connect".equals(type)) {
                    String sessionId = (String) json.get("sessionId");
                    String protocol = (String) json.get("protocol");
                    Map<String, Object> target = (Map<String, Object>) json.get("target");
                    Map<String, Object> auth = (Map<String, Object>) json.get("auth");
                    log.info("收到连接请求: sessionId={} protocol={}", sessionId, protocol);
                    handleConnect(sessionId, protocol, target, auth);
                    // 存储会话的传输协议
                    String transport = (String) json.get("transport");
                    if (transport != null && !transport.isEmpty()) {
                        sessionTransports.put(sessionId, transport.toUpperCase());
                    }

                } else if ("disconnect".equals(type)) {
                    String sessionId = (String) json.get("sessionId");
                    log.info("收到断开请求: sessionId={}", sessionId);
                    handleDisconnect(sessionId);
                    sessionTransports.remove(sessionId);

                } else if ("input".equals(type) || "resize".equals(type)
                        || "mouse".equals(type) || "key".equals(type)
                        || "desktop_control".equals(type)
                        || "file_op".equals(type)) {
                    String sessionId = (String) json.get("sessionId");
                    handleInput(sessionId, type, json);

                } else if (handleGatewayMessage(type, json)) {
                    log.debug("扩展消息已处理: type={}", type);

                }
 else {
                    log.debug("未知消息类型: {}", type);
                }
            }
 catch (Exception e) {
                log.warn("消息处理失败: {}", e.getMessage());
            }
        }

        private void sendRegister(ChannelHandlerContext ctx) {
            if (registerSent) {
                return;
            }
            try {
                Map<String, Object> register = new LinkedHashMap<>();
                register.put("type", "register");
                register.put("agent_id", properties.getAgentId());
                register.put("secret", properties.getAgentSecret());
                register.put("protocols", properties.getProtocols());
                register.put("codecs", properties.getCodecs());
                register.put("transport", properties.getTransport());
                register.put("capabilities", properties.getCapabilities());
                if (properties.getVerifyCode() != null && !properties.getVerifyCode().isBlank()) {
                    register.put("verify_code", properties.getVerifyCode());
                }
                String reg = mapper.writeValueAsString(register);
                ctx.writeAndFlush(reg + "\n");
                registerSent = true;
                log.info("已发送注册请求: agentId={}", properties.getAgentId());
            }
 catch (Exception e) {
                log.warn("发送注册请求失败: {}", e.getMessage());
            }
        }

        /**
         * exceptionCaught
         * @param ctx 参数
         * @param cause 参数
         */
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("Agent 连接异常: {}", cause.getMessage(), cause);
        }

        /**
         * channelInactive
         * @param ctx 参数
         */
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            log.warn("Agent 与 Gateway 的连接已断开");
            registered = false;
            sessionTransports.clear();
            // 通知子类清理所有会话
            onChannelInactive();
            if (running) {
                scheduleReconnect();
            }
        }
    }

    /**
     * 注册成功回调
     */
    protected void onRegistered() {
    }

    /**
     * 连接断开回调（子类应清理所有会话资源）
     */
    protected void onChannelInactive() {
    }

    /**
     * 处理扩展协议消息，例如 SOCKS5 反向隧道帧。
     *
     * @param type    消息类型
     * @param payload 原始消息
     * @return true 表示消息已被处理
     */
    protected boolean handleGatewayMessage(String type, Map<String, Object> payload) {
        return false;
    }

    private void startHeartbeat(int interval) {
        this.heartbeatInterval = interval;
        this.lastHeartbeatResponseAt = System.currentTimeMillis();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-heartbeat");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> {
            try {
                Map<String, Object> heartbeat = new LinkedHashMap<>();
                heartbeat.put("type", "heartbeat");
                heartbeat.put("agent_id", properties.getAgentId());
                if (properties.getVerifyCode() != null && !properties.getVerifyCode().isBlank()) {
                    heartbeat.put("verify_code", properties.getVerifyCode());
                }
                heartbeat.put("sentAt", System.currentTimeMillis());
                sendToGateway(mapper.writeValueAsString(heartbeat));
                // 检查是否长时间未收到心跳响应（网关可能已认为本 Agent 超时）
                long elapsed = System.currentTimeMillis() - lastHeartbeatResponseAt;
                long timeoutMs = (long) heartbeatInterval * 4 * 1000;
                if (elapsed > timeoutMs) {
                    log.warn("心跳响应超时 ({}ms > {}ms)，强制断开重连", elapsed, timeoutMs);
                    if (agentChannel != null) {
                        agentChannel.close();
                    }
                }
            }
 catch (Exception e) {
                log.warn("心跳处理失败: {}", e.getMessage());
            }
        }, interval, interval, TimeUnit.SECONDS);
        log.info("心跳已启动, 间隔={}s", interval);
    }
}
