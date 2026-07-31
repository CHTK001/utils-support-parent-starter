package com.chua.remote.support.gateway.transport.tcp;

import com.chua.remote.support.gateway.agent.AgentInfo;
import com.chua.remote.support.gateway.agent.AgentRegistry;
import com.chua.remote.support.gateway.config.GatewayProperties;
import com.chua.remote.support.gateway.core.router.TargetRegistry;
import com.chua.remote.support.gateway.core.session.SessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.CharsetUtil;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Agent 注册与心跳处理器
 * <p>
 * 放置于 LineBasedFrameDecoder 之后，处理 Agent 连接的注册（register）和心跳（heartbeat）消息。
 * 非注册/心跳的消息透传给后续的 AgentRelayHandler 进行会话中继处理。
 * <p>
 * Agent 注册流程：连接建立 → 发送 challenge → 收到 register（含 agent_id + secret）→
 * 验证并注册到 AgentRegistry → 返回 register OK 及心跳间隔配置。
 *
 * @author CH
 */
@Slf4j
public class TcpAgentRegisterHandler extends ChannelInboundHandlerAdapter {
    /** Agent 注册表，管理在线 Agent 信息 */
    private final AgentRegistry agentRegistry;
    /** 目标注册表，同步 Agent 注册的目标信息 */
    private final TargetRegistry targetRegistry;
    /** 网关配置属性 */
    private final GatewayProperties properties;
    /** 会话管理器 */
    private final SessionManager sessionManager;
    /** JSON 序列化工具 */
    private final ObjectMapper mapper = new ObjectMapper();
    /** 是否已完成注册 */
    private boolean registered;

    /**
     * 构造 Agent 注册处理器
     *
     * @param agentRegistry   Agent 注册表
     * @param targetRegistry  目标注册表
     * @param properties      网关配置属性
     * @param sessionManager  会话管理器
     */
    public TcpAgentRegisterHandler(AgentRegistry agentRegistry, TargetRegistry targetRegistry,
                                   GatewayProperties properties, SessionManager sessionManager) {
        this.agentRegistry = agentRegistry;
        this.targetRegistry = targetRegistry;
        this.properties = properties;
        this.sessionManager = sessionManager;
    }

    /**
     * 通道激活回调。当 Agent TCP 连接建立时发送 challenge 令牌，等待 Agent 的 register 响应。
     *
     * @param ctx 通道处理器上下文
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        String challengeToken = UUID.randomUUID().toString().replace("-", "");
        ctx.writeAndFlush(Unpooled.copiedBuffer(
                "{\"type\":\"challenge\",\"challenge\":\"" + challengeToken + "\"}\n", CharsetUtil.UTF_8));
    }

    /**
     * 通道读事件。解析 JSON 消息并根据 type 分发：
     * <ul>
     *   <li>register - 处理 Agent 注册</li>
     *   <li>heartbeat - 处理心跳</li>
     *   <li>其他 - 保留并透传给后续处理器（AgentRelayHandler）</li>
     * </ul>
     *
     * @param ctx 通道处理器上下文
     * @param msg 读取的消息对象
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf buf)) { ctx.fireChannelRead(msg); return; }
        try {
            String text = buf.toString(StandardCharsets.UTF_8).trim();
            @SuppressWarnings("unchecked")
            Map<String, Object> json = mapper.readValue(text, Map.class);
            String type = (String) json.get("type");
            if ("register".equals(type)) {
                handleReg(ctx, json);
            } else if ("heartbeat".equals(type)) {
                handleHb(ctx, json);
            } else if ("capability_update".equals(type)) {
                handleCapabilityUpdate(ctx, json);
            }
 else {
                // 非注册/心跳消息，保留后透传给 AgentRelayHandler
                log.info("[TcpAgentRegister] 转发消息到 AgentRelay: type={} len={}", type, text.length());
                buf.retain();
                ctx.fireChannelRead(msg);
            }
        }
 catch (Exception e) {
            log.warn("Agent消息解析失败: {}", e.getMessage());
        }
 finally {
            buf.release();
        }
    }

    /**
     * 处理 Agent 注册请求。验证 agentId 和 secret，注册到 AgentRegistry，
     * 同时同步目标信息到 TargetRegistry，返回注册结果和心跳配置。
     * <p>
     * 注册失败可能原因：密钥验证失败、agentId 已被其他在线 Agent 占用。
     *
     * @param ctx  通道处理器上下文
     * @param json 注册消息（含 agent_id、secret、protocols、codecs 等）
     */
    @SuppressWarnings("unchecked")
    private void handleReg(ChannelHandlerContext ctx, Map<String, Object> json) {
        String agentId = (String) json.get("agent_id");
        String secret = (String) json.get("secret");
        List<String> protocols = (List<String>) json.get("protocols");
        List<String> codecs = (List<String>) json.get("codecs");
        Map<String, String> caps = (Map<String, String>) json.get("capabilities");
        String verifyCode = (String) json.get("verify_code");
        String agentType = (String) json.get("agent_type");
        // 解析传输协议列表，默认为 TCP
        String transportStr = (String) json.get("transport");
        List<String> transports = transportStr != null && !transportStr.isEmpty()
                ? Arrays.stream(transportStr.split(","))
                    .map(String::trim).map(String::toUpperCase)
                    .collect(Collectors.toList())
                : List.of("TCP");
        if (!transports.contains("TCP")) {
            transports = new java.util.ArrayList<>(transports);
            transports.add("TCP");
        }
        if (agentId == null || secret == null) { send(ctx, "{\"type\":\"register\",\"status\":\"FAILED\"}"); return; }
        String agentHost = "unknown";
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress addr) {
            agentHost = addr.getHostString();
        }
        AgentInfo agent = agentRegistry.handleRegister(agentId, secret, ctx.channel(), protocols, transports, codecs, caps, verifyCode, agentType);
        if (agent != null) {
            registered = true;
            targetRegistry.syncAgent(agent, agentHost);
            send(ctx, String.format("{\"type\":\"register\",\"status\":\"OK\",\"heartbeat_interval\":%d,\"verify_code\":\"%s\"}",
                    properties.getAgentHeartbeatInterval(), agent.getVerifyCode()));
        }
 else {
            // 区分密钥验证失败和 agentId 被占用
            AgentInfo existing = agentRegistry.getAgent(agentId);
            if (existing != null && existing.isOnline()) {
                send(ctx, "{\"type\":\"register\",\"status\":\"DUPLICATE_ID\",\"msg\":\"agentId already in use: " + agentId + "\"}");
                log.warn("Agent 注册失败: agentId={} 已被占用", agentId);
            }
 else {
                send(ctx, "{\"type\":\"register\",\"status\":\"AUTH_FAILED\"}");
            }
        }
    }

    /**
     * 处理 Agent 心跳消息。更新 Agent 注册表中的在线状态和 RTT，
     * 回复心跳确认。
     *
     * @param ctx  通道处理器上下文
     * @param json 心跳消息（含 agent_id、verify_code、sentAt）
     */
    private void handleHb(ChannelHandlerContext ctx, Map<String, Object> json) {
        String agentId = (String) json.get("agent_id");
        String verifyCode = (String) json.get("verify_code");
        long rttMs = -1;
        Number sentAt = (Number) json.get("sentAt");
        if (sentAt != null) { rttMs = System.currentTimeMillis() - sentAt.longValue(); }
        if (agentId != null) { agentRegistry.handleHeartbeat(agentId, verifyCode, rttMs); }
        send(ctx, "{\"type\":\"heartbeat\",\"status\":\"OK\"}");
    }

    /**
     * 处理 Agent 能力更新消息。将 Agent 上报的新 capabilities 合并到 AgentRegistry。
     *
     * @param ctx  通道处理器上下文
     * @param json 能力更新消息（含 agent_id、capabilities）
     */
    @SuppressWarnings("unchecked")
    private void handleCapabilityUpdate(ChannelHandlerContext ctx, Map<String, Object> json) {
        String agentId = (String) json.get("agent_id");
        Map<String, String> caps = (Map<String, String>) json.get("capabilities");
        if (agentId == null || caps == null || caps.isEmpty()) { return; }
        AgentInfo agent = agentRegistry.getAgent(agentId);
        if (agent != null) {
            Map<String, String> merged = new java.util.LinkedHashMap<>(agent.getCapabilities());
            merged.putAll(caps);
            agent.setCapabilities(merged);
            log.info("[TcpAgentRegister] Agent {} capabilities 已更新: {}", agentId, caps.keySet());
        }
    }

    /**
     * 向 Agent 通道发送 JSON 响应
     *
     * @param ctx  通道处理器上下文
     * @param json 待发送的 JSON 字符串
     */
    private void send(ChannelHandlerContext ctx, String json) {
        if (ctx.channel().isActive()) { ctx.writeAndFlush(Unpooled.copiedBuffer(json + "\n", CharsetUtil.UTF_8)); }
    }

    /**
     * 用户事件触发。检测到读空闲事件时关闭 Agent 连接（心跳超时）。
     *
     * @param ctx 通道处理器上下文
     * @param evt 用户事件
     */
    @Override public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent e && e.state() == IdleState.READER_IDLE) { ctx.close(); }
    }
    /**
     * 异常捕获。发生异常时直接关闭 Agent 连接。
     */
    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { ctx.close(); }

    /**
     * Agent 通道断开时清理注册信息和关联目标。
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        try {
            ctx.fireChannelInactive();
        }
 finally {
            AgentInfo agent = agentRegistry.findByChannel(ctx.channel());
            if (agent != null) {
                String agentId = agent.getAgentId();
                agentRegistry.unregister(agentId);
                targetRegistry.unregister(agentId);
                log.info("Agent 通道断开，已清理注册信息和目标: agentId={}", agentId);
            }
        }
    }
}
