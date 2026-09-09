package com.chua.remote.gateway;

import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.core.transport.RemoteTransport;
import com.chua.remote.protocol.frame.Frame;
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

@Slf4j
public class ControllerWebSocketServer extends WebSocketServer {

    private final RemoteTransport transport;
    private final SessionManager sessionManager;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<WebSocket, String> wsToAgent = new ConcurrentHashMap<>();
    private final Map<WebSocket, String> wsToType = new ConcurrentHashMap<>();
    /** sshSessionId → ws（agent 响应帧带 sshSessionId——回流按会话路由） */
    private final Map<String, WebSocket> sshSessions = new ConcurrentHashMap<>();
    /** vncSessionId → ws（agent 画面帧带 vncSessionId——回流按会话路由） */
    private final Map<String, WebSocket> vncSessions = new ConcurrentHashMap<>();

    public ControllerWebSocketServer(int port, RemoteTransport transport, SessionManager sessionManager) {
        super(new InetSocketAddress(port));
        this.transport = transport;
        this.sessionManager = sessionManager;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String path = handshake.getResourceDescriptor();
        if (path == null) path = "";
        String agentId = extractAgentId(path);
        String type = extractType(path);
        if (agentId == null || type == null) {
            log.warn("Controller WS: 无效路径 {}", path);
            conn.close(4000, "path must be /ws/{ssh|vnc}/{agentId}");
            return;
        }
        wsToAgent.put(conn, agentId);
        wsToType.put(conn, type);
        log.info("Controller WS connected: agentId={}, type={}, path={}", agentId, type, path);
        try {
            conn.send(mapper.writeValueAsString(toJsonMsg("connected", agentId, type)));
        } catch (Exception e) {
            log.warn("Controller WS 发送连接消息失败", e);
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String agentId = wsToAgent.get(conn);
        log.info("Controller WS closed: code={}, reason={}, remote={}, agent={}", code, reason, remote, agentId);
        wsToAgent.remove(conn);
        wsToType.remove(conn);
        sshSessions.values().remove(conn);
        vncSessions.values().remove(conn);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        String agentId = wsToAgent.get(conn);
        String type = wsToType.get(conn);
        if (agentId == null || type == null) return;
        try {
            JsonNode node = mapper.readTree(message);
            String action = node.path("action").asText("");
            if ("ssh".equals(action)) {
                handleSshMessage(conn, agentId, node);
            } else if ("vnc".equals(action)) {
                handleVncMessage(conn, agentId, node);
            }
        } catch (Exception e) {
            log.error("WS消息处理失败: {}", e.getMessage());
        }
    }

    private void handleSshMessage(WebSocket conn, String agentId, JsonNode node) {
        String sshAction = node.path("sshAction").asText("");
        String sessionId = node.path("sessionId").asText("");
        String host = node.path("host").asText("local");
        if ("start".equals(sshAction) && !sessionId.isEmpty() && sessionManager.getSession(sessionId) == null) {
            // 浏览器 wsUrl 直连路径未走 HTTP 会话创建——自动补建简化会话
            sessionManager.addSession(com.chua.remote.protocol.model.Session.builder()
                    .sessionId(sessionId)
                    .controllerSessionId(sessionId)
                    .agentId(agentId)
                    .agentType(com.chua.remote.protocol.model.AgentInfo.AgentType.FORWARD)
                    .status(com.chua.remote.protocol.model.Session.SessionStatus.ACTIVE)
                    .createTime(System.currentTimeMillis())
                    .build());
            log.info("自动补建会话: sessionId={}, agentId={}", sessionId, agentId);
        }

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
        log.debug("WS→Agent ssh: agentId={}, action={}, sessionId={}", agentId, sshAction, sessionId);
    }

    private void handleVncMessage(WebSocket conn, String agentId, JsonNode node) {
        String vncAction = node.path("vncAction").asText("");
        String sessionId = node.path("sessionId").asText("");

        var meta = new java.util.HashMap<String, String>();
        meta.put("vncAction", vncAction);
        meta.put("vncSessionId", sessionId);

        byte[] payload = new byte[0];
        if ("input".equals(vncAction)) {
            String data = node.path("data").asText("");
            if (!data.isEmpty()) {
                payload = Base64.getDecoder().decode(data);
            }
        }

        Frame frame = FrameCodec.vncFrame(agentId, payload, meta);
        transport.send(agentId, frame);
        if (!sessionId.isEmpty()) {
            vncSessions.put(sessionId, conn);
        }
        log.debug("WS→Agent vnc: agentId={}, action={}, sessionId={}", agentId, vncAction, sessionId);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        log.error("Controller WS error", ex);
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
        WebSocket sessionWs = sshSessions.get(frame.getSessionId());
        if (sessionWs != null && sessionWs.isOpen()) {
            sessionWs.send(json);
            return;
        }
        for (WebSocket ws : getConnections()) {
            if (agentId.equals(wsToAgent.get(ws)) && "ssh".equals(wsToType.get(ws))) {
                ws.send(json);
            }
        }
    }

    void onAgentVncFrame(Frame frame) {
        String agentId = frame.getSessionId();
        Map<String, String> meta = frame.getMetadata();
        if (meta == null) return;
        String action = meta.get("vncAction");
        byte[] data = frame.getPayload();

        String sessionId = meta.get("vncSessionId");
        WebSocket sessionWs = sessionId != null ? vncSessions.get(sessionId) : null;
        if (sessionWs == null || !sessionWs.isOpen()) {
            for (WebSocket ws : getConnections()) {
                if (agentId.equals(wsToAgent.get(ws)) && "vnc".equals(wsToType.get(ws))) {
                    sessionWs = ws;
                    break;
                }
            }
        }
        if (sessionWs == null || !sessionWs.isOpen()) {
            return;
        }

        if ("frame".equals(action) && data != null && data.length > 0) {
            sessionWs.send(ByteBuffer.wrap(data));
        } else {
            try {
                String json = mapper.writeValueAsString(
                        Map.of("type", "vnc", "data", action, "stream", "stdout"));
                sessionWs.send(json);
            } catch (Exception e) {
                log.warn("VNC 状态帧序列化失败", e);
            }
        }
    }

    private String extractAgentId(String path) {
        if (path.startsWith("/ws/ssh/")) {
            return path.substring("/ws/ssh/".length());
        }
        if (path.startsWith("/ws/vnc/")) {
            return path.substring("/ws/vnc/".length());
        }
        return null;
    }

    private String extractType(String path) {
        if (path.startsWith("/ws/ssh/")) {
            return "ssh";
        }
        if (path.startsWith("/ws/vnc/")) {
            return "vnc";
        }
        return null;
    }

    private String toJsonMsg(String type, String data) {
        return toJsonMsg(type, data, "stdout");
    }

    private String toJsonMsg(String type, String data, String stream) {
        try {
            return mapper.writeValueAsString(Map.of("type", type, "data", data, "stream", stream));
        } catch (Exception e) {
            return "{}";
        }
    }
}
