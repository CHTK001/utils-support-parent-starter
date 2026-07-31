package com.chua.remote.support.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 远程控制 WebSocket 处理器。
 *
 * <p>浏览器通过此 WebSocket 连接到 Gateway，接收桌面帧并发送鼠标/键盘事件。</p>
 *
 * <h3>消息协议</h3>
 * <pre>
 * 浏览器 → 服务端:
 *   {"type":"connect","sessionId":"xxx","protocol":"DESKTOP"}
 *   {"type":"input","sessionId":"xxx","msgType":"mouse","data":{"action":"click","x":100,"y":200,"button":"left"}}
 *   {"type":"input","sessionId":"xxx","msgType":"key","data":{"action":"down","keyCode":65}}
 *   {"type":"resize","sessionId":"xxx","width":1920,"height":1080}
 *
 * 服务端 → 浏览器:
 *   {"type":"connected","sessionId":"xxx"}
 *   {"type":"desktop_frame","sessionId":"xxx","width":1920,"height":1080,"keyFrame":true,"data":"base64..."}
 *   {"type":"desktop_frame_info","sessionId":"xxx","width":1920,"height":1080}
 *   {"type":"error","sessionId":"xxx","msg":"..."}
 * </pre>
 *
 * @author CH
 */
@Slf4j
public class RemoteControlWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * sessionId -> WebSocketSession
     */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String sessionId = session.getId();
        log.info("[WebSocket] 连接建立: sessionId={}", sessionId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = session.getId();
        try {
            JsonNode json = mapper.readTree(message.getPayload());
            String type = json.path("type").asText("");

            switch (type) {
                case "connect" -> handleConnect(session, json);
                case "input" -> handleInput(session, json);
                case "resize" -> handleResize(session, json);
                case "disconnect" -> handleDisconnect(session, json);
                default -> log.debug("[WebSocket] 未知消息类型: {}", type);
            }
        } catch (Exception e) {
            log.error("[WebSocket] 消息处理失败: {}", e.getMessage());
            sendError(session, sessionId, "消息处理失败: " + e.getMessage());
        }
    }

    @Override
    public void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        // 预留：处理二进制帧（如 H.264 原始数据）
        log.debug("[WebSocket] 收到二进制消息: {} bytes", message.getPayloadLength());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = session.getId();
        sessions.remove(sessionId);
        log.info("[WebSocket] 连接关闭: sessionId={} status={}", sessionId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("[WebSocket] 传输错误: sessionId={}", session.getId(), exception);
        sessions.remove(session.getId());
    }

    // ===== 消息处理 =====

    private void handleConnect(WebSocketSession session, JsonNode json) throws IOException {
        String sessionId = json.path("sessionId").asText(session.getId());
        String protocol = json.path("protocol").asText("DESKTOP");

        // 保存会话
        sessions.put(sessionId, session);

        // TODO: 通过 Gateway API 发起连接请求
        // gateway.connect(sessionId, protocol, target, auth);

        sendText(session, Map.of(
                "type", "connected",
                "sessionId", sessionId,
                "protocol", protocol
        ));
        log.info("[WebSocket] 远控连接请求: sessionId={} protocol={}", sessionId, protocol);
    }

    private void handleInput(WebSocketSession session, JsonNode json) throws IOException {
        String sessionId = json.path("sessionId").asText(session.getId());
        String msgType = json.path("msgType").asText();
        JsonNode data = json.path("data");

        // TODO: 转发到 Gateway → Agent
        log.debug("[WebSocket] 输入事件: sessionId={} type={} data={}", sessionId, msgType, data);
    }

    private void handleResize(WebSocketSession session, JsonNode json) throws IOException {
        String sessionId = json.path("sessionId").asText(session.getId());
        int width = json.path("width").asInt(1920);
        int height = json.path("height").asInt(1080);

        // TODO: 转发到 Gateway → Agent
        log.debug("[WebSocket] 窗口调整: sessionId={} {}x{}", sessionId, width, height);
    }

    private void handleDisconnect(WebSocketSession session, JsonNode json) throws IOException {
        String sessionId = json.path("sessionId").asText(session.getId());
        sessions.remove(sessionId);

        // TODO: 通过 Gateway 断开连接
        // gateway.disconnect(sessionId);

        log.info("[WebSocket] 远控断开: sessionId={}", sessionId);
    }

    // ===== 发送方法（供 Gateway 调用） =====

    /**
     * 向浏览器推送桌面帧。
     *
     * @param sessionId 会话 ID
     * @param width     帧宽度
     * @param height    帧高度
     * @param keyFrame  是否关键帧
     * @param frameData 帧数据（JPEG base64 或 H.264）
     */
    public void pushDesktopFrame(String sessionId, int width, int height, boolean keyFrame, String frameData) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            sendText(session, Map.of(
                    "type", "desktop_frame",
                    "sessionId", sessionId,
                    "width", width,
                    "height", height,
                    "keyFrame", keyFrame,
                    "data", frameData
            ));
        } catch (Exception e) {
            log.warn("[WebSocket] 推送帧失败: sessionId={}", sessionId);
        }
    }

    /**
     * 向浏览器推送桌面帧信息（不含数据，用于协商分辨率）。
     */
    public void pushFrameInfo(String sessionId, int width, int height) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            sendText(session, Map.of(
                    "type", "desktop_frame_info",
                    "sessionId", sessionId,
                    "width", width,
                    "height", height
            ));
        } catch (Exception e) {
            log.warn("[WebSocket] 推送帧信息失败: sessionId={}", sessionId);
        }
    }

    // ===== 工具方法 =====

    private void sendText(WebSocketSession session, Object payload) throws IOException {
        String json = mapper.writeValueAsString(payload);
        session.sendMessage(new TextMessage(json));
    }

    private void sendError(WebSocketSession session, String sessionId, String msg) throws IOException {
        sendText(session, Map.of("type", "error", "sessionId", sessionId, "msg", msg));
    }
}
