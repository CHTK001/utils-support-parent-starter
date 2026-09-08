package com.chua.remote.gateway;

import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.core.transport.RemoteTransport;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

@Slf4j
public class ControllerWebSocketServer extends WebSocketServer {

    private final RemoteTransport transport;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<WebSocket, String> wsToAgent = new ConcurrentHashMap<>();
    /** sshSessionId → ws（agent 响应帧带 sshSessionId——回流按会话路由，而非 agentId） */
    private final Map<String, WebSocket> sshSessions = new ConcurrentHashMap<>();

    public ControllerWebSocketServer(int port, RemoteTransport transport) {
        super(new InetSocketAddress(port));
        this.transport = transport;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String path = handshake.getResourceDescriptor();
        if (path == null) path = "";
        String agentId = extractAgentId(path);
        if (agentId == null) {
            log.warn("Controller WS: 无效路径 {}", path);
            conn.close(4000, "path must be /ws/ssh/{agentId}");
            return;
        }
        wsToAgent.put(conn, agentId);
        log.info("Controller WS connected: agentId={}, path={}", agentId, path);
        try {
            conn.send(mapper.writeValueAsString(toJsonMsg("connected", agentId)));
        } catch (Exception e) {
            log.warn("Controller WS 发送连接消息失败", e);
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        wsToAgent.remove(conn);
        log.info("Controller WS closed: code={}, reason={}", code, reason);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        String agentId = wsToAgent.get(conn);
        if (agentId == null) return;
        try {
            JsonNode node = mapper.readTree(message);
            String action = node.path("action").asText("");
            if (!"ssh".equals(action)) return;

            String sshAction = node.path("sshAction").asText("");
            String sessionId = node.path("sessionId").asText("");
            String host = node.path("host").asText("local");

            var meta = new java.util.HashMap<String, String>();
            meta.put("sshAction", sshAction);
            meta.put("sshSessionId", sessionId);
            meta.put("host", host);
            if (node.has("cols")) meta.put("cols", node.path("cols").asText("80"));
            if (node.has("rows")) meta.put("rows", node.path("rows").asText("24"));

            byte[] payload = new byte[0];
            if ("input".equals(sshAction)) {
                String data = node.path("data").asText("");
                if (!data.isEmpty()) {
                    payload = Base64.getDecoder().decode(data);
                }
            }

            Frame frame = FrameCodec.sshFrame(agentId, payload, meta);
            transport.send(agentId, frame);
            if (!sessionId.isEmpty()) {
                sshSessions.put(sessionId, conn);
            }
            log.debug("WS→Agent: agentId={}, action={}, sessionId={}", agentId, sshAction, sessionId);
        } catch (Exception e) {
            log.error("WS消息处理失败: {}", e.getMessage());
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        log.error("Controller WS error", ex);
    }

    /** 二进制消息（库版本可能按二进制投递文本帧——验证用） */
    @Override
    public void onMessage(WebSocket conn, java.nio.ByteBuffer bytes) {
        log.info("收到二进制消息: len={}", bytes.remaining());
        try {
            String message = new String(bytes.array(), bytes.arrayOffset(), bytes.remaining(), java.nio.charset.StandardCharsets.UTF_8);
            onMessage(conn, message);
        } catch (Exception e) {
            log.error("二进制消息处理失败", e);
        }
    }

    @Override
    public void onStart() {
        log.info("Controller WS server started on port {}", getPort());
    }

    void onAgentSSHFrame(Frame frame) {
        String agentId = frame.getSessionId();
        Map<String, String> meta = frame.getMetadata();
        if (meta == null) return;
        String action = meta.get("sshAction");
        byte[] data = frame.getPayload();

        String text;
        if (data != null && data.length > 0) {
            text = new String(data);
        } else {
            text = "";
        }

        String json = toJsonMsg(action, text, meta.getOrDefault("stream", "stdout"));
        log.debug("Agent→WS: sessionId={}, action={}, len={}", frame.getSessionId(), action, data != null ? data.length : 0);

        // 优先按 sshSessionId 路由（agent 响应帧带会话 id——与 wsToAgent 的 agentId 不一致）
        WebSocket sessionWs = sshSessions.get(frame.getSessionId());
        if (sessionWs != null && sessionWs.isOpen()) {
            sessionWs.send(json);
            return;
        }
        for (WebSocket ws : getConnections()) {
            if (agentId.equals(wsToAgent.get(ws))) {
                ws.send(json);
            }
        }
    }

    private String extractAgentId(String path) {
        if (path.startsWith("/ws/ssh/")) {
            return path.substring("/ws/ssh/".length());
        }
        return null;
    }

    private String toJsonMsg(String type, String data) {
        return toJsonMsg(type, data, "stdout");
    }

    private String toJsonMsg(String type, String data, String stream) {
        try {
            return mapper.writeValueAsString(Map.of("type", "ssh", "data", data, "stream", stream));
        } catch (Exception e) {
            return "{}";
        }
    }
}
