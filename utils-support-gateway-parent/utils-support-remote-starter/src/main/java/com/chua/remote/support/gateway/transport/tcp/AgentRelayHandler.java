package com.chua.remote.support.gateway.transport.tcp;

import com.chua.remote.support.gateway.agent.AgentRegistry;
import com.chua.remote.support.gateway.core.session.GatewaySession;
import com.chua.remote.support.gateway.core.session.SessionManager;
import com.chua.remote.support.gateway.transport.ws.LiveKitProxyHandler;
import com.chua.remote.support.gateway.transport.ws.RemoteControlWsHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

/**
 * Agent 中继处理器

 * @author CH
 */@Slf4j
public class AgentRelayHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private final SessionManager sessionManager;
    private final AgentRegistry agentRegistry;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentRelayHandler(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
        this.agentRegistry = null;
    }

    public AgentRelayHandler(SessionManager sessionManager, AgentRegistry agentRegistry) {
        this.sessionManager = sessionManager;
        this.agentRegistry = agentRegistry;
    }

    /**
     * channelRead0
     * @param ctx 参数
     * @param buf 参数
     */
    @Override
    @SuppressWarnings("unchecked")
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) {
        String text = buf.toString(StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) { return; }

        try {
            Map<String, Object> msg = mapper.readValue(text, Map.class);
            String type = (String) msg.get("type");
            String sessionId = (String) msg.get("sessionId");
            log.info("[AgentRelay] 收到消息: type={} sessionId='{}' len={}", type, sessionId, text.length());
            if (type == null || sessionId == null || sessionId.isEmpty()) {
                log.warn("[AgentRelay] 消息缺少 type/sessionId: {}", text.substring(0, Math.min(200, text.length())));
                return;
            }

            if (LiveKitProxyHandler.handleAgentRelayMessage(type, sessionId, msg)) {
                return;
            }

            GatewaySession session = sessionManager.getSession(sessionId);
            if (session == null) {
                log.warn("[AgentRelay] session 不存在: {}", sessionId);
                return;
            }

            Channel wsChannel = session.getClientChannel();
            if (wsChannel == null || !wsChannel.isActive()) {
                log.warn("[AgentRelay] WS 客户端已断开: sessionId={}", sessionId);
                sessionManager.closeSession(sessionId);
                return;
            }

            switch (type) {
                case "connected":
                case "error":
                case "disconnected":
                case "terminal_output":
                case "room_joined":
                case "livekit_info":
                case "participant_joined":
                case "participant_left":
                case "host_changed":
                case "raise_hand":
                case "hand_raised":
                case "webrtc_offer":
                case "webrtc_answer":
                case "ice_candidate":
                case "webrtc_request":
                case "rustdesk_credentials":
                    RemoteControlWsHandler.sendTextToClient(wsChannel, sessionId, text);
                    session.addBytesSent(text.length());
                    break;

                case "desktop_frame": {
                    Object widthObj = msg.get("width");
                    Object heightObj = msg.get("height");
                    Object keyFrameObj = msg.get("keyFrame");
                    String dataB64 = (String) msg.get("data");
                    if (dataB64 != null) {
                        int w = widthObj instanceof Number ? ((Number) widthObj).intValue() : 0;
                        int h = heightObj instanceof Number ? ((Number) heightObj).intValue() : 0;
                        boolean kf = keyFrameObj instanceof Boolean && (Boolean) keyFrameObj;
                        byte[] raw = Base64.getDecoder().decode(dataB64);
                        RemoteControlWsHandler.sendDesktopFrameToClient(wsChannel, sessionId, w, h, kf, raw);
                        session.addFrameSent(raw.length);
                    }
                    break;
                }
                case "desktop_frame_info":
                case "desktop_metrics":
                case "file_op":
                    RemoteControlWsHandler.sendTextToClient(wsChannel, sessionId, text);
                    session.addBytesSent(text.length());
                    break;
                default:
                    log.debug("[AgentRelay] 未处理类型: {} sessionId={}", type, sessionId);
            }
        }
 catch (Exception e) {
            log.warn("[AgentRelay] 消息处理失败: {}", e.getMessage());
        }
    }

    /**
     * exceptionCaught
     * @param ctx 参数
     * @param cause 参数
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("[AgentRelay] 异常: {}", cause.getMessage());
        ctx.close();
    }

    /**
     * channelInactive
     * @param ctx 参数
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("[AgentRelay] Agent 断开连接: {}", ctx.channel().remoteAddress());
        if (agentRegistry != null) {
            com.chua.remote.support.gateway.agent.AgentInfo agentInfo = agentRegistry.findByChannel(ctx.channel());
            String agentId = agentInfo != null ? agentInfo.getAgentId() : "unknown";
            sessionManager.getSessionsByAgent(agentId).forEach(session -> {
                String sessionId = session.getSessionId();
                Channel wsChannel = session.getClientChannel();
                if (wsChannel != null && wsChannel.isActive()) {
                    String json = "{\"type\":\"agent_disconnected\",\"sessionId\":" + '"' + sessionId + '"' + ",\"msg\":\"被控端断开，正在重连...\"}";
                    wsChannel.writeAndFlush(new io.netty.handler.codec.http.websocketx.TextWebSocketFrame(json));
                }
                session.setAgentChannel(null);
                if (agentInfo != null) {
                    agentInfo.setOnline(false);
                }
            });
        }
    }
}
