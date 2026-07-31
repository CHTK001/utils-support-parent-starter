package com.chua.remote.support.gateway.transport.ws;

import com.chua.remote.support.gateway.agent.AgentInfo;
import com.chua.remote.support.gateway.agent.AgentRegistry;
import com.chua.remote.support.gateway.config.ConnectionMode;
import com.chua.remote.support.gateway.config.GatewayConfigService;
import com.chua.remote.support.gateway.config.Protocol;
import com.chua.remote.support.gateway.core.auth.TokenVerifier;
import com.chua.remote.support.gateway.core.ratelimit.GatewayRateLimiter;
import com.chua.remote.support.gateway.core.router.TargetEntry;
import com.chua.remote.support.gateway.core.router.TargetRegistry;
import com.chua.remote.support.gateway.core.session.ControllerRegistry;
import com.chua.remote.support.gateway.core.session.GatewaySession;
import com.chua.remote.support.gateway.core.session.SessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 远程控制 WebSocket 处理器
 * <p>
 * 网关 DWS 端口（8082）的主处理器，处理 SSH 终端远程控制与桌面远程控制的 WebSocket 协议。
 * 支持以下消息类型：
 * <ul>
 *   <li>connect - 发起连接到目标 Agent（按 targetId 或 verifyCode 两种模式）</li>
 *   <li>disconnect - 断开指定会话</li>
 *   <li>capabilities - 客户端能力声明（编解码器、分辨率、帧率）</li>
 *   <li>input / resize / mouse / key - 终端输入与桌面控制</li>
 *   <li>desktop_control - 桌面远程控制指令</li>
 *   <li>file_op - 文件操作</li>
 *   <li>monitor_subscribe / monitor_unsubscribe - 监控订阅</li>
 *   <li>config_get / config_update - 运行时配置查询与更新</li>
 * </ul>
 * 连接流程：HTTP 握手 → Token 验证（可选）→ WebSocket 升级 → connect 消息 → 会话创建 → 双向中继。
 *
 * @author CH
 * @see AgentRelayHandler Agent 侧对应的中继处理器
 * @see MonitorPushService 监控推送服务
 */
@Slf4j
public class RemoteControlWsHandler extends SimpleChannelInboundHandler<Object> {

    /** 目标注册表，用于查找目标（target）对应的 Agent 和连接信息 */
    private final TargetRegistry targetRegistry;
    /** Agent 注册表，管理所有在线 Agent 的注册信息与通道 */
    private final AgentRegistry agentRegistry;
    /** 会话管理器，负责会话的创建、查询和销毁 */
    private final SessionManager sessionManager;
    /** 网关限流器，用于握手阶段的频率控制 */
    private final GatewayRateLimiter rateLimiter;
    /** 客户端能力管理器，记录每个会话的编解码器、分辨率、帧率等能力信息 */
    private final ClientCapabilityManager capabilityManager;
    /** 网关配置服务，提供运行时配置查询与更新的能力 */
    private final GatewayConfigService configService;
    /** 监控推送服务，定时向订阅的客户端推送会话快照数据 */
    private final MonitorPushService monitorPushService;
    /** 控制端注册表，用于追踪所有已连接的控制端通道 */
    private final ControllerRegistry controllerRegistry;
    /** JSON 序列化/反序列化工具 */
    private final ObjectMapper mapper = new ObjectMapper();
    /** WebSocket 握手器，管理 HTTP 升级到 WebSocket 的握手过程 */
    private WebSocketServerHandshaker handshaker;

    /**
     * 构造函数（简易版，不含 configService / monitorPushService / controllerRegistry）
     *
     * @param tr    目标注册表
     * @param ar    Agent 注册表
     * @param sm    会话管理器
     * @param rl    限流器
     */
    public RemoteControlWsHandler(TargetRegistry tr, AgentRegistry ar, SessionManager sm, GatewayRateLimiter rl) {
        this(tr, ar, sm, rl, new ClientCapabilityManager(), null, null, null);
    }

    /**
     * 构造函数（包含 ClientCapabilityManager）
     *
     * @param tr    目标注册表
     * @param ar    Agent 注册表
     * @param sm    会话管理器
     * @param rl    限流器
     * @param cm    客户端能力管理器
     */
    public RemoteControlWsHandler(TargetRegistry tr, AgentRegistry ar, SessionManager sm,
                                   GatewayRateLimiter rl, ClientCapabilityManager cm) {
        this(tr, ar, sm, rl, cm, null, null, null);
    }

    /**
     * 构造函数（包含 configService 和 monitorPushService）
     *
     * @param tr                目标注册表
     * @param ar                Agent 注册表
     * @param sm                会话管理器
     * @param rl                限流器
     * @param cm                客户端能力管理器
     * @param configService     网关配置服务
     * @param monitorPushService 监控推送服务
     */
    public RemoteControlWsHandler(TargetRegistry tr, AgentRegistry ar, SessionManager sm,
                                   GatewayRateLimiter rl, ClientCapabilityManager cm,
                                   GatewayConfigService configService, MonitorPushService monitorPushService) {
        this(tr, ar, sm, rl, cm, configService, monitorPushService, null);
    }

    /**
     * 全参构造函数
     *
     * @param tr                 目标注册表
     * @param ar                 Agent 注册表
     * @param sm                 会话管理器
     * @param rl                 限流器
     * @param cm                 客户端能力管理器
     * @param configService      网关配置服务
     * @param monitorPushService 监控推送服务
     * @param controllerRegistry 控制端注册表
     */
    public RemoteControlWsHandler(TargetRegistry tr, AgentRegistry ar, SessionManager sm,
                                   GatewayRateLimiter rl, ClientCapabilityManager cm,
                                   GatewayConfigService configService, MonitorPushService monitorPushService,
                                   ControllerRegistry controllerRegistry) {
        this.targetRegistry = tr;
        this.agentRegistry = ar;
        this.sessionManager = sm;
        this.rateLimiter = rl;
        this.capabilityManager = cm;
        this.configService = configService;
        this.monitorPushService = monitorPushService;
        this.controllerRegistry = controllerRegistry;
    }

    /**
     * 获取能力管理器
     *
     * @return 客户端能力管理器实例
     */
    public ClientCapabilityManager getCapabilityManager() {
        return capabilityManager;
    }

    /**
     * 通道激活回调。当新的 WS 连接建立时记录客户端地址。
     * 注意：控制端注册延迟到 connect 消息处理阶段，避免未认证连接触发 IP 去重导致合法连接被关闭。
     *
     * @param ctx 通道处理器上下文
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.info("[RemoteControl] WS连接: {}", ctx.channel().remoteAddress());
        // controller 注册延迟到握手成功后，避免未认证连接触发 IP 去重导致合法连接被关闭
    }

    /**
     * 通道断开回调。当 WS 连接断开时清理监控订阅和控制端注册。
     *
     * @param ctx 通道处理器上下文
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("[RemoteControl] WS断开: {}", ctx.channel().remoteAddress());
        cleanupController(ctx);
    }

    /**
     * 处理器移除回调。作为 channelInactive 的安全兜底，
     * 某些异常断开场景下 channelInactive 可能不被触发。
     *
     * @param ctx 通道处理器上下文
     */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        // 安全兜底：某些异常断开场景 channelInactive 可能不被触发
        cleanupController(ctx);
    }

    /**
     * 清理控制端资源：取消监控订阅并注销控制端通道
     *
     * @param ctx 通道处理器上下文
     */
    private void cleanupController(ChannelHandlerContext ctx) {
        if (monitorPushService != null) {
            monitorPushService.unsubscribe(ctx.channel());
        }
        if (controllerRegistry != null) {
            controllerRegistry.unregister(ctx.channel());
        }
    }

    /**
     * 处理 WebSocket 握手（HTTP 升级请求）。
     * <p>
     * 流程：限流检查 → Token 验证（若开启）→ WebSocketServerHandshaker 创建 → 握手。
     * 握手成功后不立即注册控制端，等待客户端发送 connect 消息。
     *
     * @param ctx 通道处理器上下文
     * @param req HTTP 升级请求
     */
    public void handleHandshake(ChannelHandlerContext ctx, io.netty.handler.codec.http.FullHttpRequest req) {
        if (!rateLimiter.tryAcquire()) {
            ctx.close();
            return;
        }
        // Token 验证（受 apiTokenEnabled 开关控制）
        if (configService != null && configService.isApiTokenEnabled()) {
            String token = TokenVerifier.extractTokenFromWsReq(req);
            if (!configService.verifyToken(token)) {
                ByteBuf buf = Unpooled.copiedBuffer(
                        "{\"error\":\"unauthorized\",\"msg\":\"valid token required\"}", CharsetUtil.UTF_8);
                FullHttpResponse resp = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED, buf);
                resp.headers().set("Content-Type", "application/json; charset=utf-8");
                resp.headers().set("Content-Length", String.valueOf(buf.readableBytes()));
                ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
                log.warn("[RemoteControl] WS 握手被拒绝: 无效 token");
                return;
            }
        }
        String host = String.valueOf(req.headers().get(HttpHeaderNames.HOST));
        String wsUrl = "ws://" + (host != null && !host.isBlank() ? host : "localhost") + req.uri();
        WebSocketServerHandshakerFactory f = new WebSocketServerHandshakerFactory(
                wsUrl, null, false, 10485760);
        handshaker = f.newHandshaker(req);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
        }
 else {
            handshaker.handshake(ctx.channel(), req).addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    // 不在这里注册控制端，等待客户端发送 connect 消息后再注册
                    log.info("[RemoteControl] WS 握手成功: {}", ctx.channel().remoteAddress());
                }
 else {
                    ctx.close();
                }
            });
        }
    }

    /**
     * 消息入口。根据消息类型分发处理：
     * <ul>
     *   <li>FullHttpRequest → 握手处理</li>
     *   <li>CloseWebSocketFrame → 关闭连接</li>
     *   <li>PingWebSocketFrame → 回复 Pong</li>
     *   <li>TextWebSocketFrame → JSON 文本消息</li>
     *   <li>BinaryWebSocketFrame → 二进制帧（桌面鼠标/键盘等）</li>
     * </ul>
     *
     * @param ctx 通道处理器上下文
     * @param msg 收到的消息对象
     */
    @Override
    @SuppressWarnings("unchecked")
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof FullHttpRequest) {
            handleHandshake(ctx, (FullHttpRequest) msg);
            return;
        }
        if (!(msg instanceof WebSocketFrame frame)) { return; }

        if (frame instanceof CloseWebSocketFrame) {
            handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain());
            return;
        }
        if (frame instanceof PingWebSocketFrame) {
            ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
            return;
        }
        if (frame instanceof TextWebSocketFrame) {
            handleTextFrame(ctx, ((TextWebSocketFrame) frame).text());
            return;
        }
        if (frame instanceof BinaryWebSocketFrame) {
            handleBinaryFrame(ctx, frame.content());
        }
    }

    /**
     * 处理 JSON 文本消息帧。根据 type 字段分发到对应处理方法。
     * <p>
     * 消息类型包括：connect、disconnect、capabilities、input、resize、
     * mouse、key、desktop_control、file_op、monitor_subscribe、
     * monitor_unsubscribe、config_get、config_update。
     *
     * @param ctx  通道处理器上下文
     * @param text 收到的 JSON 字符串
     */
    @SuppressWarnings("unchecked")
    private void handleTextFrame(ChannelHandlerContext ctx, String text) {
        try {
            Map<String, Object> msg = mapper.readValue(text, Map.class);
            String type = (String) msg.get("type");
            if (type == null) { return; }

            switch (type) {
                case "connect":
                    handleConnect(ctx, msg);
                    break;
                case "disconnect":
                    handleDisconnect(ctx, msg);
                    break;
                case "ping":
                    sendWs(ctx, "{\"type\":\"pong\"}");
                    break;
                case "capabilities":
                    handleCapabilities(ctx, msg);
                    break;
                case "input":
                case "resize":
                case "mouse":
                case "key":
                case "desktop_control":
                case "file_op":
                case "webrtc_offer":
                case "webrtc_answer":
                case "ice_candidate":
                case "webrtc_request":
                    try {
                        relayToAgent(ctx, msg);
                    }
 catch (Exception relayEx) {
                        log.warn("[RemoteControl] relayToAgent 失败: type={} err={}", type, relayEx.getMessage());
                        sendWs(ctx, "{\"type\":\"error\",\"msg\":\"relay failed: " + relayEx.getMessage() + "\",\"sessionId\":\"" + (msg.get("sessionId") != null ? msg.get("sessionId") : "") + "\"}");
                    }
                    break;
                case "monitor_subscribe": {
                    int interval = msg.get("interval") instanceof Number ? ((Number) msg.get("interval")).intValue() : 2000;
                    if (monitorPushService != null) {
                        monitorPushService.subscribe(ctx.channel(), interval);
                    }
                    break;
                }
                case "monitor_unsubscribe":
                    if (monitorPushService != null) {
                        monitorPushService.unsubscribe(ctx.channel());
                    }
                    break;
                case "config_get":
                    if (configService != null) {
                        try {
                            String configJson = mapper.writeValueAsString(
                                    Map.of("type", "config_snapshot", "config", configService.toMap()));
                            sendWs(ctx, configJson);
                        }
 catch (Exception e) {
                            log.warn("[RemoteControl] config_get 失败: {}", e.getMessage());
                        }
                    }
                    break;
                case "config_update": {
                    if (configService != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> updates = (Map<String, Object>) msg.get("config");
                        if (updates != null) {
                            Map<String, Object> result = configService.updateConfig(updates);
                            try {
                                result.put("type", "config_result");
                                sendWs(ctx, mapper.writeValueAsString(result));
                            }
 catch (Exception e) {
                                log.warn("[RemoteControl] config_update 序列化失败: {}", e.getMessage());
                            }
                        }
                    }
                    break;
                }
                default:
                    log.warn("[RemoteControl] 未知消息类型: {}", type);
                    sendWs(ctx, mapper.writeValueAsString(
                            Map.of("type", "error", "msg", "未知消息类型: " + type)));
            }
        }
 catch (Exception e) {
            log.warn("[RemoteControl] 消息解析失败: {}", e.getMessage());
            sendWs(ctx, "{\"type\":\"error\",\"msg\":\"消息解析失败\"}");
        }
    }

    /**
     * 处理 connect 消息——建立从控制端到目标 Agent 的连接。
     * <p>
     * 支持两种寻址模式：
     * <ul>
     *   <li><b>targetId 模式</b>：通过 targetId 或 agentId + protocol 查找目标</li>
     *   <li><b>verifyCode 模式</b>：通过验证码查找 Agent（免输入 targetId）</li>
     * </ul>
     * 流程：目标查找 → Agent 在线验证 → 验证码校验 → 协议解析（AUTO 自动选择）→
     * 目标地址覆盖（SSH 场景）→ 控制端注册 → 会话创建 → CONNECT 转发到 Agent。
     *
     * @param ctx 通道处理器上下文
     * @param msg 包含 targetId、verifyCode、protocol、auth 等字段的消息
     * @throws Exception JSON 序列化失败或通道写入异常
     */
    @SuppressWarnings("unchecked")
    private void handleConnect(ChannelHandlerContext ctx, Map<String, Object> msg) throws Exception {
        String targetId = (String) msg.get("targetId");
        String verifyCode = (String) msg.get("verifyCode");
        String protocolStr = (String) msg.get("protocol");
        Map<String, Object> auth = (Map<String, Object>) msg.get("auth");

        // 默认协议为 AUTO
        if (protocolStr == null || protocolStr.isEmpty()) {
            protocolStr = "AUTO";
        }

        // CONFERENCE 协议：targetId 不存在于 TargetRegistry，而是直接作为 roomId 使用
        boolean isConferenceAutoMatch = "CONFERENCE".equalsIgnoreCase(protocolStr);
        if (!isConferenceAutoMatch && (targetId == null || targetId.isEmpty()) && (verifyCode == null || verifyCode.isEmpty())) {
            sendWs(ctx, "{\"type\":\"error\",\"msg\":\"请提供目标 ID 或验证码\"}");
            return;
        }

        AgentInfo agent = null;
        TargetEntry target = null;

        if (isConferenceAutoMatch) {
            // CONFERENCE 协议：targetId 作为 roomId 使用，不走普通 targetId 解析
            String roomId = (targetId != null && !targetId.isEmpty()) ? targetId : "default";
            List<TargetEntry> allConferenceTargets = targetRegistry.lookupByProtocol(Protocol.CONFERENCE);
            if (allConferenceTargets.isEmpty()) {
                sendWs(ctx, "{\"type\":\"error\",\"msg\":\"无可用的会议 Agent\"}");
                return;
            }
            target = allConferenceTargets.get(0);
            targetId = target.getTargetId();
            agent = agentRegistry.getAgent(target.getAgentId());
            if (agent == null || !agent.isOnline()) {
                log.warn("[RemoteControl] 目标 targetId={} 的 Agent({}) 离线，尝试按协议查找其他在线 Agent", targetId, target.getAgentId());
                AgentInfo fallbackAgent = agentRegistry.findAgentByProtocol(Protocol.CONFERENCE);
                if (fallbackAgent == null) {
                    sendWs(ctx, "{\"type\":\"error\",\"msg\":\"无可用的会议 Agent\"}");
                    return;
                }
                List<TargetEntry> agentTargets = targetRegistry.lookupByAgentId(fallbackAgent.getAgentId());
                TargetEntry fallbackTarget = null;
                for (TargetEntry t : agentTargets) {
                    if (t.getProtocol() == Protocol.CONFERENCE) {
                        fallbackTarget = t;
                        break;
                    }
                }
                if (fallbackTarget == null) {
                    sendWs(ctx, "{\"type\":\"error\",\"msg\":\"会议 Agent 无可用目标\"}");
                    return;
                }
                target = fallbackTarget;
                targetId = target.getTargetId();
                agent = fallbackAgent;
                log.info("[RemoteControl] 回退到 targetId={} agentId={}", targetId, agent.getAgentId());
            }
            log.info("[RemoteControl] CONFERENCE 协议: 自动匹配 targetId={} agentId={}, roomId={}", targetId, agent.getAgentId(), roomId);

            if (targetId != null && !targetId.isEmpty()) {
                msg.putIfAbsent("targetId", targetId);
            }
            if (verifyCode != null && !verifyCode.isEmpty()) {
                msg.putIfAbsent("verifyCode", verifyCode);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> existingTarget = (Map<String, Object>) msg.get("target");
            if (existingTarget == null) {
                existingTarget = new java.util.HashMap<>();
                msg.put("target", existingTarget);
            }
            Object maxParticipants = existingTarget.get("maxParticipants");
            existingTarget.putIfAbsent("roomId", roomId);
            if (maxParticipants != null) {
                existingTarget.putIfAbsent("maxParticipants", maxParticipants);
            }
        } else

        if (targetId != null && !targetId.isEmpty()) {
            // ── 有 targetId：按 targetId / agentId + protocol 查找 ──
            target = targetRegistry.lookup(targetId);
            if (target == null) {
                List<TargetEntry> candidates = targetRegistry.lookupByAgentId(targetId);
                for (TargetEntry candidate : candidates) {
                    if (candidate.getProtocol().name().equalsIgnoreCase(protocolStr)
                            || "AUTO".equalsIgnoreCase(protocolStr)) {
                        target = candidate;
                        log.info("[RemoteControl] 通过 agentId+protocol 匹配到 target: {} → {}", targetId, target.getTargetId());
                        break;
                    }
                }
                if (target == null && !candidates.isEmpty()) {
                    target = candidates.get(0);
                    log.info("[RemoteControl] agentId 匹配但协议不完全匹配，使用: {}", target.getTargetId());
                }
            }
            if (target == null) {
                sendWs(ctx, "{\"type\":\"error\",\"msg\":\"目标不存在: " + targetId + "\"}");
                return;
            }
            agent = agentRegistry.getAgent(target.getAgentId());
        }
 else {
            // ── 仅有 verifyCode：通过验证码查找 Agent ──
            agent = agentRegistry.findByVerifyCode(verifyCode);
            if (agent == null) {
                sendWs(ctx, "{\"type\":\"error\",\"msg\":\"验证码无效或被控端离线\"}");
                return;
            }
            // 查找该 Agent 的可用 target
            List<TargetEntry> candidates = targetRegistry.lookupByAgentId(agent.getAgentId());
            for (TargetEntry candidate : candidates) {
                if (candidate.getProtocol().name().equalsIgnoreCase(protocolStr)
                        || "AUTO".equalsIgnoreCase(protocolStr)) {
                    target = candidate;
                    break;
                }
            }
            if (target == null && !candidates.isEmpty()) {
                target = candidates.get(0);
            }
            if (target == null) {
                sendWs(ctx, "{\"type\":\"error\",\"msg\":\"被控端无可用目标: " + agent.getAgentId() + "\"}");
                return;
            }
            targetId = target.getTargetId();
            log.info("[RemoteControl] 通过验证码匹配: verifyCode={} → agentId={} targetId={}", verifyCode, agent.getAgentId(), targetId);
        }

        // 验证 Agent 在线
        if (agent == null || !agent.isOnline() || agent.getChannel() == null) {
            sendWs(ctx, "{\"type\":\"error\",\"msg\":\"被控端离线: " + (target != null ? target.getAgentId() : "unknown") + "\"}");
            return;
        }
        String agentId = agent.getAgentId();

        // 验证码校验（CONFERENCE 协议使用 agent-secret 验证，跳过验证码）
        if (!isConferenceAutoMatch && verifyCode != null && !verifyCode.isEmpty() && !verifyCode.equals(agent.getVerifyCode())) {
            sendWs(ctx, "{\"type\":\"error\",\"msg\":\"验证码错误\"}");
            return;
        }

        // AUTO 协议：根据 target 注册的协议自动选择
        String resolvedProtocol = protocolStr;
        if ("AUTO".equalsIgnoreCase(protocolStr)) {
            resolvedProtocol = target.getProtocol().name();
            log.info("[RemoteControl] AUTO resolved: targetId={} → protocol={}", targetId, resolvedProtocol);
        }

        Protocol protocol;
        try { protocol = Protocol.valueOf(resolvedProtocol.toUpperCase()); }

        catch (IllegalArgumentException e) {
            sendWs(ctx, "{\"type\":\"error\",\"msg\":\"不支持的协议: " + resolvedProtocol + "\"}");
            return;
        }

        // 浏览器可传入 target 或 auth 中的 host/port 覆盖注册信息（如 SSH 目标地址）
        Map<String, Object> targetInfo = new java.util.HashMap<>();
        targetInfo.put("host", target.getHost());
        targetInfo.put("port", target.getPort());
        @SuppressWarnings("unchecked")
        Map<String, Object> clientTarget = (Map<String, Object>) msg.get("target");
        if (clientTarget != null) {
            if (clientTarget.get("host") != null) { targetInfo.put("host", clientTarget.get("host")); }
            if (clientTarget.get("port") != null) { targetInfo.put("port", clientTarget.get("port")); }
            // CONFERENCE 协议：保留 clientTarget 中的 roomId/mode 等自定义字段
            if ("CONFERENCE".equalsIgnoreCase(protocolStr)) {
                targetInfo.putAll(clientTarget);
            }
        }
        // auth 中的 host/port 也作为目标地址（SSH 场景）
        if (auth != null) {
            if (auth.get("host") != null && targetInfo.get("host").equals("0.0.0.0")) {
                targetInfo.put("host", auth.get("host"));
            }
            if (auth.get("port") instanceof Number && ((Number) targetInfo.get("port")).intValue() == 0) {
                targetInfo.put("port", auth.get("port"));
            }
        }

        // 全部验证通过后注册控制端
        if (controllerRegistry != null) {
            controllerRegistry.register(ctx.channel());
        }

        // 创建会话（先清除该 WS 通道的旧会话，避免重连时残留）
        sessionManager.removeSessionsByChannel(ctx.channel());
        GatewaySession session = sessionManager.createSession(
                targetId, agentId,
                ctx.channel().remoteAddress().toString(),
                protocol, ConnectionMode.LONG, ctx.channel());

        // 转发 CONNECT 到 Agent
        Map<String, Object> connectPayload = new java.util.HashMap<>();
        connectPayload.put("type", "connect");
        connectPayload.put("sessionId", session.getSessionId());
        connectPayload.put("protocol", protocolStr);
        connectPayload.put("target", targetInfo);
        // 传输协议由目标 ID 自动确定，控制端无需指定
        String transport = target != null ? target.getTransport() : null;
        if (transport != null) { connectPayload.put("transport", transport); }
        if (auth != null) { connectPayload.put("auth", auth); }
        String connectMsg = mapper.writeValueAsString(connectPayload);
        Channel agentCh = agent.getChannel();
        boolean agentChActive = agentCh != null && agentCh.isActive();
        log.info("[RemoteControl] 转发 CONNECT: targetId={} sessionId={} agentId={} agentCh={} active={}",
                targetId, session.getSessionId(), agentId, agentCh, agentChActive);
        if (agentChActive) {
            agentCh.writeAndFlush(Unpooled.copiedBuffer(connectMsg + "\n", CharsetUtil.UTF_8));
        }
 else {
            log.error("[RemoteControl] Agent channel 不可用! agentId={}", agentId);
            sendWs(ctx, "{\"type\":\"error\",\"msg\":\"agent channel unavailable\"}");
        }
    }

    /**
     * 处理 disconnect 消息——断开指定会话。
     * <p>
     * 向 Agent 转发 disconnect 指令，然后回复 disconnected 确认给客户端，
     * 确认发出后再关闭会话和清理能力记录。
     *
     * @param ctx 通道处理器上下文
     * @param msg 包含 sessionId 的消息
     * @throws Exception JSON 序列化或通道写入异常
     */
    private void handleDisconnect(ChannelHandlerContext ctx, Map<String, Object> msg) throws Exception {
        String sessionId = (String) msg.get("sessionId");
        if (sessionId == null) { return; }

        GatewaySession session = sessionManager.getSession(sessionId);
        if (session != null) {
            Channel agentCh = agentRegistry.getAgentChannel(session.getAgentId());
            if (agentCh != null) {
                agentCh.writeAndFlush(Unpooled.copiedBuffer(
                        "{\"type\":\"disconnect\",\"sessionId\":\"" + sessionId + "\"}\n", CharsetUtil.UTF_8));
            }
        }
        // 先发送 disconnected 回复，确认发出后再关闭 session
        String disconnectedJson = "{\"type\":\"disconnected\",\"sessionId\":\"" + sessionId + "\"}";
        ctx.writeAndFlush(new TextWebSocketFrame(disconnectedJson))
                .addListener((ChannelFutureListener) future -> {
                    if (session != null) { sessionManager.closeSession(sessionId); }
                    capabilityManager.remove(sessionId);
                });
    }

    /**
     * 处理客户端能力声明消息。
     * <p>
     * 记录客户端支持的编解码器、期望分辨率与帧率，
     * 并绑定对应的 WS 通道供后续二进制帧转发使用。
     * 同时将客户端期望的终端/桌面尺寸（resize）转发给 Agent。
     *
     * @param ctx 通道处理器上下文
     * @param msg 包含 sessionId、codecs、width、height、fps 的能力声明
     */
    @SuppressWarnings("unchecked")
    private void handleCapabilities(ChannelHandlerContext ctx, Map<String, Object> msg) {
        String sessionId = (String) msg.get("sessionId");
        java.util.List<String> codecs = (java.util.List<String>) msg.get("codecs");
        int width = msg.get("width") instanceof Number ? ((Number) msg.get("width")).intValue() : 1920;
        int height = msg.get("height") instanceof Number ? ((Number) msg.get("height")).intValue() : 1080;
        int fps = msg.get("fps") instanceof Number ? ((Number) msg.get("fps")).intValue() : 30;
        if (sessionId != null) {
            capabilityManager.register(sessionId, codecs, width, height, fps);
            capabilityManager.bindChannel(sessionId, ctx.channel());
            log.info("[RemoteControl] 客户端能力: sessionId={} codecs={} {}x{}@{}fps",
                    sessionId, codecs, width, height, fps);

            // 转发目标尺寸给 Agent
            GatewaySession session = sessionManager.getSession(sessionId);
            if (session != null) {
                Channel agentCh = agentRegistry.getAgentChannel(session.getAgentId());
                if (agentCh != null) {
                    try {
                        String resizeMsg = mapper.writeValueAsString(Map.of("type", "desktop_control",
                                "sessionId", sessionId,
                                "action", "resize",
                                "width", width,
                                "height", height));
                        agentCh.writeAndFlush(Unpooled.copiedBuffer(resizeMsg + "\n", CharsetUtil.UTF_8));
                    }
 catch (Exception e) {
                        log.warn("[RemoteControl] 发送resize失败: {}", e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * 处理嵌入在 WS 消息中的 resize 请求（客户端窗口尺寸变化时触发）。
     * <p>
     * 更新能力表中的分辨率信息，并将新的尺寸值转发给 Agent 供桌面/终端适配。
     *
     * @param ctx 通道处理器上下文
     * @param msg 包含 sessionId、width、height 的 resize 消息
     */
    @SuppressWarnings("unchecked")
    private void handleResize(ChannelHandlerContext ctx, Map<String, Object> msg) {
        String sessionId = (String) msg.get("sessionId");
        int width = msg.get("width") instanceof Number ? ((Number) msg.get("width")).intValue() : 0;
        int height = msg.get("height") instanceof Number ? ((Number) msg.get("height")).intValue() : 0;
        if (sessionId == null || width <= 0 || height <= 0) { return; }

        // 更新能力表
        ClientCapabilityManager.ClientCapability cap = capabilityManager.getCapability(sessionId);
        if (cap != null) {
            capabilityManager.register(sessionId, java.util.List.copyOf(cap.codecs()), width, height, cap.fps());
        }

        // 转发给 Agent
        GatewaySession session = sessionManager.getSession(sessionId);
        if (session != null) {
            Channel agentCh = agentRegistry.getAgentChannel(session.getAgentId());
            if (agentCh != null) {
                try {
                    String resizeMsg = mapper.writeValueAsString(Map.of("type", "desktop_control",
                            "sessionId", sessionId,
                            "action", "resize",
                            "width", width,
                            "height", height));
                    agentCh.writeAndFlush(Unpooled.copiedBuffer(resizeMsg + "\n", CharsetUtil.UTF_8));
                }
 catch (Exception e) {
                    log.warn("[RemoteControl] 转发resize失败: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 将从客户端收到的消息中继转发给目标 Agent。
     * <p>
     * 根据 sessionId 查找会话和目标 Agent 通道，将原始 JSON 转发到 Agent 侧 TCP 连接。
     * 对移动类鼠标事件做日志降噪（仅非"move"动作记录日志）。
     *
     * @param ctx 通道处理器上下文
     * @param msg 包含 sessionId 和 type 的待转发消息
     * @throws Exception JSON 序列化或通道写入异常
     */
    @SuppressWarnings("unchecked")
    private void relayToAgent(ChannelHandlerContext ctx, Map<String, Object> msg) throws Exception {
        String sessionId = (String) msg.get("sessionId");
        if (sessionId == null) { return; }

        GatewaySession session = sessionManager.getSession(sessionId);
        if (session == null) {
            sendWs(ctx, "{\"type\":\"error\",\"sessionId\":\"" + sessionId + "\",\"msg\":\"session not found\"}");
            return;
        }

        Channel agentCh = agentRegistry.getAgentChannel(session.getAgentId());
        if (agentCh == null) {
            sendWs(ctx, "{\"type\":\"error\",\"sessionId\":\"" + sessionId + "\",\"msg\":\"agent offline\"}");
            return;
        }

        // 转发原始 JSON 到 Agent
        String json = mapper.writeValueAsString(msg);
        String type = (String) msg.get("type");
        if (!"mouse".equals(type) || !"move".equals(msg.get("action"))) {
            log.info("[Relay→Agent] type={} sessionId={} agent={}", type, sessionId, session.getAgentId());
        }
        agentCh.writeAndFlush(Unpooled.copiedBuffer(json + "\n", CharsetUtil.UTF_8));
        session.addBytesReceived(json.getBytes(StandardCharsets.UTF_8).length);
    }

    /**
     * 处理二进制帧（桌面鼠标/键盘等输入）。
     * <p>
     * 二进制帧格式：[4B sessionId 长度][sessionId UTF8 字节][payload]。
     * 解析出 sessionId 后查找会话和目标 Agent，将剩余 payload 转发到 Agent。
     *
     * @param ctx  通道处理器上下文
     * @param data 二进制帧数据（含 sessionId 前缀）
     */
    private void handleBinaryFrame(ChannelHandlerContext ctx, ByteBuf data) {
        // 二进制帧（桌面鼠标/键盘等）转发给 Agent
        // 格式: [4-byte sessionId长度][sessionId UTF8][payload]
        if (data.readableBytes() < 4) { return; }
        int sidLen = data.readInt();
        if (data.readableBytes() < sidLen) { return; }
        byte[] sidBytes = new byte[sidLen];
        data.readBytes(sidBytes);
        String sessionId = new String(sidBytes, StandardCharsets.UTF_8);

        GatewaySession session = sessionManager.getSession(sessionId);
        if (session == null) { return; }

        Channel agentCh = agentRegistry.getAgentChannel(session.getAgentId());
        if (agentCh == null) { return; }

        // 转发二进制数据到 Agent（桌面帧输入）
        int payloadLen = data.readableBytes();
        ByteBuf out = Unpooled.buffer();
        out.writeBytes(data);
        agentCh.writeAndFlush(out);
        session.addBytesReceived(payloadLen);
    }

    // ====== 被 AgentRelayHandler 调用的静态方法 ======

    /**
     * 向 WS 客户端发送文本帧（由 AgentRelayHandler 调用）
     */
    public static void sendTextToClient(Channel wsChannel, String sessionId, String text) {
        if (wsChannel != null && wsChannel.isActive()) {
            wsChannel.writeAndFlush(new TextWebSocketFrame(text));
        }
    }

    /**
     * 向 WS 客户端发送桌面二进制帧（由 AgentRelayHandler 调用）
     */
    public static void sendDesktopFrameToClient(Channel wsChannel, String sessionId,
                                                  int width, int height, boolean keyFrame, byte[] data) {
        if (wsChannel != null && wsChannel.isActive()) {
            // 客户端协议: 前 4+4+4+1 字节 header + payload
            byte[] sidBytes = sessionId.getBytes(StandardCharsets.UTF_8);
            ByteBuf buf = Unpooled.buffer(13 + data.length);
            buf.writeInt(sidBytes.length);
            buf.writeBytes(sidBytes);
            buf.writeInt(width);
            buf.writeInt(height);
            buf.writeBoolean(keyFrame);
            buf.writeBytes(data);
            wsChannel.writeAndFlush(new BinaryWebSocketFrame(buf));
        }
    }

    /**
     * 向 WS 客户端发送 JSON 文本帧
     *
     * @param ctx  通道处理器上下文
     * @param json 待发送的 JSON 字符串
     */
    private void sendWs(ChannelHandlerContext ctx, String json) {
        if (ctx.channel().isActive()) {
            ctx.channel().writeAndFlush(new TextWebSocketFrame(json));
        }
    }

    /**
     * 从 HTTP 请求的 Host 头获取 WebSocket 服务地址
     *
     * @param req HTTP 升级请求
     * @return WebSocket 地址字符串（ws://host）
     */
    private static String getLocation(io.netty.handler.codec.http.FullHttpRequest req) {
        CharSequence host = req.headers().get("Host");
        return "ws://" + (host != null ? host.toString() : "localhost");
    }

    /**
     * 获取 WebSocket 服务器初始化 pipeline，包含日志、HTTP 编解码、聚合和压缩处理器
     *
     * @return ChannelHandler 数组，按顺序组成 pipeline
     */
    public static ChannelHandler[] initialPipeline() {
        return new ChannelHandler[]{
                new LoggingHandler(LogLevel.DEBUG), new HttpServerCodec(),
                new HttpObjectAggregator(65536), new WebSocketServerCompressionHandler()
        };
    }

    /**
     * 异常捕获。记录警告日志并关闭通道。
     *
     * @param ctx   通道处理器上下文
     * @param cause 异常原因
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("[RemoteControl] 异常: {}", cause.getMessage());
        ctx.close();
    }
}
