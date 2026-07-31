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
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;import io.netty.handler.codec.string.StringEncoder;import io.netty.handler.codec.string.StringDecoder;import io.netty.handler.codec.LineBasedFrameDecoder;import io.netty.channel.SimpleChannelInboundHandler;

/**
 * 远程控制 Agent 抽象基类
 * 负责连接 Gateway、注册、心跳、消息分发
  * @author CH
 */
@Slf4j
public abstract class BaseRemoteAgent {

    public final AgentProperties properties;
    public final ObjectMapper mapper = new ObjectMapper();
    public final Map<String, AgentSessionContext> sessions = new ConcurrentHashMap<>();

    protected EventLoopGroup workerGroup;
    protected Channel agentChannel;
    protected ScheduledExecutorService scheduler;
    protected volatile boolean running;
    private volatile int reconnectAttempt;
    private ScheduledExecutorService reconnectExecutor;
    private volatile long lastHeartbeatResponseAt;
    private volatile int heartbeatInterval = 30;
    protected Map<String, RemoteTransportProvider> transportProviders = new ConcurrentHashMap<>();
    protected final Map<String, String> sessionTransports = new ConcurrentHashMap<>();

    public BaseRemoteAgent(AgentProperties properties) {
        this.properties = properties;
    }

    public boolean isRunning() { return running; }

    public void start() throws Exception {
        running = true;
        if (agentChannel != null) {
            try {
                if (agentChannel.isActive()) {
                    agentChannel.close();
                }
            } catch (Exception ignored) {
            }
            agentChannel = null;
        }
        resetTransportProviders();
        if (workerGroup == null || workerGroup.isShuttingDown() || workerGroup.isShutdown()) {
            if (workerGroup != null) {
                try { workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).await(2000); } catch (Exception ignored) {}
            }
            workerGroup = new NioEventLoopGroup(1);
        }
        try {
            connectGateway();
            log.info("Agent 已连接 Gateway: {}:{}", properties.getGatewayHost(), properties.getGatewayPort());
            initRemoteTransportProviders();
        } catch (Exception e) {
            if (!running) {
                throw e;
            }
            log.warn("Agent 连接 Gateway 失败: {}:{} {}", properties.getGatewayHost(), properties.getGatewayPort(), e.getMessage());
            scheduleReconnect();
        }
    }

    private void connectGateway() throws Exception {
        Bootstrap b = new Bootstrap();
        b.group(workerGroup).channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<SocketChannel>() {
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
    }

    protected void initRemoteTransportProviders() {
        for (String transport : properties.getTransports()) {
            if ("TCP".equalsIgnoreCase(transport)) {
                continue;
            }
            try {
                RemoteTransportProvider provider = ServiceProvider.of(RemoteTransportProvider.class)
                        .getExtension(transport.toLowerCase());
                if (provider != null) {
                    Map<String, Object> options = new HashMap<>();
                    options.put("agentId", properties.getAgentId());
                    if (provider.connect(properties.getGatewayHost(), properties.getGatewayPort(), options)) {
                        transportProviders.put(transport.toUpperCase(), provider);
                        log.info("SPI 传输提供者已初始化 transport={} provider={}", transport, provider.getClass().getSimpleName());
                    }
                }
            } catch (Exception e) {
                log.warn("SPI 传输提供者初始化失败: transport={} {}", transport, e.getMessage());
            }
        }
    }

    private void resetTransportProviders() {
        for (RemoteTransportProvider provider : transportProviders.values()) {
            try { provider.close(); } catch (Exception ignored) {}
        }
        transportProviders.clear();
    }

    protected abstract void handleConnect(String sessionId, String protocol, Map<String, Object> target, Map<String, Object> auth);
    protected abstract void handleDisconnect(String sessionId);
    protected abstract void handleInput(String sessionId, String type, Map<String, Object> payload);
    protected boolean handleGatewayMessage(String type, Map<String, Object> payload) { return false; }

    public EventLoopGroup getWorkerGroup() { return workerGroup; }

    public void sendToGateway(String json) {
        for (RemoteTransportProvider provider : transportProviders.values()) {
            if (provider.isActive() && provider.sendText(json + "\n")) {
                return;
            }
        }
        if (agentChannel != null && agentChannel.isActive()) {
            agentChannel.writeAndFlush(json + "\n");
        }
    }

    public void sendDesktopFrame(String sessionId, int width, int height, boolean keyFrame, byte[] h264Data) {
        String b64 = java.util.Base64.getEncoder().encodeToString(h264Data);
        try {
            String json = mapper.writeValueAsString(Map.of("type", "desktop_frame",
                    "sessionId", sessionId, "width", width, "height", height, "keyFrame", keyFrame, "data", b64));
            sendToGateway(json);
        } catch (Exception e) { log.warn("发送桌面帧失败: {}", e.getMessage()); }
    }

    public void sendBinaryFrame(byte frameType, String sessionId, int width, int height, boolean keyFrame, byte[] encodedData) {
        String sessionTransport = sessionTransports.get(sessionId);
        log.info("[sendBinaryFrame] sessionId={} type=0x{} keyFrame={} dataLen={} transport={} agentChannelActive={}",
                sessionId, String.format("%02X", frameType), keyFrame, encodedData.length,
                sessionTransport, agentChannel != null && agentChannel.isActive());
        if (sessionTransport != null) {
            RemoteTransportProvider provider = transportProviders.get(sessionTransport);
            if (provider != null && provider.isActive() && provider.sendBinaryFrame(frameType, sessionId, width, height, keyFrame, encodedData)) {
                return;
            }
        }
        if (agentChannel != null && agentChannel.isActive()) {
            try {
                byte[] sidBytes = sessionId.getBytes(StandardCharsets.UTF_8);
                int payloadLen = 1 + 2 + sidBytes.length + 2 + 2 + 1 + encodedData.length;
                ByteBuf buf = Unpooled.buffer(5 + payloadLen);
                buf.writeByte(0xBF);
                buf.writeInt(payloadLen);
                buf.writeByte(frameType);
                buf.writeShort(sidBytes.length);
                buf.writeBytes(sidBytes);
                buf.writeShort(width);
                buf.writeShort(height);
                buf.writeBoolean(keyFrame);
                buf.writeBytes(encodedData);
                agentChannel.writeAndFlush(buf);
                log.info("[sendBinaryFrame] 已发送: sessionId={} frameType=0x{} payloadLen={}", sessionId, String.format("%02X", frameType), payloadLen);
            } catch (Exception e) { log.warn("发送二进制帧失败: {}", e.getMessage()); }
        }
    }

    public void stop() {
        running = false;
        reconnectAttempt = 0;
        resetTransportProviders();
        sessionTransports.clear();
        if (reconnectExecutor != null) {
            reconnectExecutor.shutdownNow();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (agentChannel != null) {
            agentChannel.close();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 3, TimeUnit.SECONDS);
        }
    }

    private void scheduleReconnect() {
        if (!running) {
            return;
        }
        if (reconnectExecutor == null || reconnectExecutor.isShutdown()) {
            reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "agent-reconnect");
                t.setDaemon(true);
                return t;
            });
        }
        reconnectAttempt++;
        int maxAttempts = properties.getReconnectMaxAttempts();
        if (maxAttempts > 0 && reconnectAttempt >= maxAttempts) {
            log.warn("Agent 已达到最大连接重试次数 {}，停止自动重连", maxAttempts);
            stop();
            return;
        }
        long delayMs = Math.min(1000L * (1L << Math.min(Math.max(reconnectAttempt - 1, 0), 5)), 30_000);
        log.info("将在 {}ms 后尝试第 {} 次连接..", delayMs, reconnectAttempt + 1);
        reconnectExecutor.schedule(() -> {
            if (!running) {
                return;
            }
            try { start(); } catch (Exception e) { scheduleReconnect(); }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    public static class AgentSessionContext {
        private final String sessionId;
        private final String protocol;
        private final Map<String, Object> auth;
        private volatile boolean active = true;
        public AgentSessionContext(String sessionId, String protocol, Map<String, Object> auth) {
            this.sessionId = sessionId; this.protocol = protocol; this.auth = auth;
        }
        public String getSessionId() { return sessionId; }
        public String getProtocol() { return protocol; }
        public Map<String, Object> getAuth() { return auth; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
    }

    private class AgentMessageHandler extends SimpleChannelInboundHandler<String> {
        private boolean registered;

        @Override
        public void channelActive(ChannelHandlerContext ctx) { log.info("TCP 连接已建立，等待 challenge..."); }

        @Override
        @SuppressWarnings("unchecked")
        protected void channelRead0(ChannelHandlerContext ctx, String msg) {
            try {
                Map<String, Object> json = mapper.readValue(msg, Map.class);
                String type = (String) json.get("type");

                if ("challenge".equals(type)) {
                    Map<String, Object> reg = new LinkedHashMap<>();
                    reg.put("type", "register");
                    reg.put("agent_id", properties.getAgentId());
                    reg.put("secret", properties.getAgentSecret());
                    reg.put("protocols", properties.getProtocols());
                    reg.put("codecs", properties.getCodecs());
                    reg.put("transport", properties.getTransport());
                    reg.put("capabilities", properties.getCapabilities());
                    if (properties.getVerifyCode() != null && !properties.getVerifyCode().isBlank()) {
                        reg.put("verify_code", properties.getVerifyCode());
                    }
                    ctx.writeAndFlush(mapper.writeValueAsString(reg) + "\n");
                    log.info("已发送注册请求 agentId={}", properties.getAgentId());

                } else if ("register".equals(type)) {
                    if ("OK".equals(json.get("status"))) {
                        registered = true;
                        reconnectAttempt = 0;
                        lastHeartbeatResponseAt = System.currentTimeMillis();
                        Number interval = (Number) json.get("heartbeat_interval");
                        int hbInterval = interval != null ? interval.intValue() : 30;
                        String verifyCode = (String) json.get("verify_code");
                        if (verifyCode != null && !verifyCode.isBlank()) {
                            properties.setVerifyCode(verifyCode);
                        }
                        log.info("Agent 注册成功, 心跳间隔={}s", hbInterval);
                        onRegistered();
                        startHeartbeat(hbInterval);
                    } else {
                        log.error("Agent 注册失败: {}", json.get("status"));
                        ctx.close();
                    }

                } else if ("heartbeat".equals(type)) {
                    lastHeartbeatResponseAt = System.currentTimeMillis();

                } else if ("connect".equals(type)) {
                    String sessionId = (String) json.get("sessionId");
                    String protocol = (String) json.get("protocol");
                    Map<String, Object> target = (Map<String, Object>) json.get("target");
                    Map<String, Object> auth = (Map<String, Object>) json.get("auth");
                    log.info("收到连接请求: sessionId={} protocol={}", sessionId, protocol);
                    handleConnect(sessionId, protocol, target, auth);
                    String transport = (String) json.get("transport");
                    if (transport != null && !transport.isEmpty()) {
                        sessionTransports.put(sessionId, transport.toUpperCase());
                    }

                } else if ("disconnect".equals(type)) {
                    String sessionId = (String) json.get("sessionId");
                    handleDisconnect(sessionId);
                    sessionTransports.remove(sessionId);

                } else if ("input".equals(type) || "resize".equals(type) || "mouse".equals(type)
                        || "key".equals(type) || "desktop_control".equals(type) || "file_op".equals(type)) {
                    handleInput((String) json.get("sessionId"), type, json);

                } else if (!handleGatewayMessage(type, json)) {
                    log.debug("未知消息类型: {}", type);
                }
            } catch (Exception e) { log.warn("消息处理失败: {}", e.getMessage()); }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("Agent 连接异常: {}", cause.getMessage());
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            log.warn("Agent 与 Gateway 的连接已断开");
            registered = false;
            sessionTransports.clear();
            onChannelInactive();
            if (running) {
                scheduleReconnect();
            }
        }
    }

    protected void onRegistered() {}
    protected void onChannelInactive() {}

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
                Map<String, Object> hb = new LinkedHashMap<>();
                hb.put("type", "heartbeat");
                hb.put("agent_id", properties.getAgentId());
                hb.put("verify_code", properties.getVerifyCode());
                hb.put("sentAt", System.currentTimeMillis());
                sendToGateway(mapper.writeValueAsString(hb));
                long elapsed = System.currentTimeMillis() - lastHeartbeatResponseAt;
                if (elapsed > (long) heartbeatInterval * 4 * 1000) {
                    log.warn("心跳响应超时，强制断开重连");
                    if (agentChannel != null) {
                        agentChannel.close();
                    }
                }
            } catch (Exception e) { log.warn("心跳处理失败: {}", e.getMessage()); }
        }, interval, interval, TimeUnit.SECONDS);
    }
}
