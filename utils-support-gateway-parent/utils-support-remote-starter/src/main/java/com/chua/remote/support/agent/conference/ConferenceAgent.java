package com.chua.remote.support.agent.conference;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 会议 Agent — 替代 Rust agent-conference
 *
 * <p>通过 TCP 连接 Gateway (端口 9001)，以 CONFERENCE 协议注册，
 * 为浏览器提供 WebRTC Mesh 信令中继服务。</p>
 *
 * <h3>消息流程</h3>
 * <pre>
 * ┌─────────┐   WS(8082)   ┌─────────┐   TCP(9001)   ┌──────────┐
 * │ Browser │◄───────────►│ Gateway │◄─────────────►│   Agent   │
 * │  (A)    │              │         │               │(本类)     │
 * └─────────┘              └─────────┘               └──────────┘
 *     │                        │                          │
 *     │  connect(roomId)       │                          │
 *     ├───────────────────────►│   CONNECT(sessionId,A)   │
 *     │                        ├─────────────────────────►│
 *     │                        │                          ├── 加入 room
 *     │                        │  room_joined(部分列表)     │
 *     │                        │◄─────────────────────────┤
 *     │◄───────────────────────┤                          │
 *     │                        │  participant_joined(A)   │
 *     │                        │◄─────────────────────────┤ 广播给 room 其他人
 *     │                        │                          │
 *     │  webrtc_offer(A,sdp)   │                          │
 *     ├───────────────────────►│  转发 webrtc_offer       │
 *     │                        ├─────────────────────────►│
 *     │                        │                          ├── 查 room
 *     │                        │  webrtc_offer(B, +from)  │
 *     │                        │◄─────────────────────────┤
 *     │◄───────────────────────┤                          │ 转发给 B
 *     │                        │                          │
 *     │  webrtc_answer(B,sdp)  │                          │
 *     │◄───────────────────────┤  转发 webrtc_answer      │
 *     │                        │◄─────────────────────────┤
 *     │                        │                          │
 * </pre>

 * @author CH
 */@Slf4j
public class ConferenceAgent {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── 配置 ────────────────────────────────────────────────
    private String gatewayHost = "127.0.0.1";
    private int gatewayPort = 9001;
    private String agentId = "agent-conference";
    private String agentSecret = "gateway-agent-secret";

    // ── 连接状态 ────────────────────────────────────────────
    private Socket socket;
    private PrintWriter writer;
    private BufferedReader reader;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService heartbeatExecutor;
    private long heartbeatIntervalMs = 30000;

    // ── 房间管理 ────────────────────────────────────────────
    /** roomId → 有序 sessionId 列表 */
    private final ConcurrentHashMap<String, List<String>> rooms = new ConcurrentHashMap<>();
    /** sessionId → roomId */
    private final ConcurrentHashMap<String, String> sessionRooms = new ConcurrentHashMap<>();

    // ── 配置方法 ────────────────────────────────────────────

    /**
     * gatewayHost
     * @param host 参数
     * @return gatewayHost结果
     */
    public ConferenceAgent gatewayHost(String host) { this.gatewayHost = host; return this; }
    /**
     * gatewayPort
     * @param port 参数
     * @return gatewayPort结果
     */
    public ConferenceAgent gatewayPort(int port) { this.gatewayPort = port; return this; }
    /**
     * agentId
     * @param id 参数
     * @return agentId结果
     */
    public ConferenceAgent agentId(String id) { this.agentId = id; return this; }
    /**
     * agentSecret
     * @param secret 参数
     * @return agentSecret结果
     */
    public ConferenceAgent agentSecret(String secret) { this.agentSecret = secret; return this; }

    // ── 生命周期 ────────────────────────────────────────────

    /**
     * 启动
     */
    public void start() throws Exception {
        running.set(true);
        socket = new Socket();
        socket.connect(new InetSocketAddress(gatewayHost, gatewayPort), 5000);
        socket.setTcpNoDelay(true);
        writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

        log.info("[ConferenceAgent] TCP 已连接: {}:{}", gatewayHost, gatewayPort);

        // 1. 读取 challenge
        String challengeLine = reader.readLine();
        if (challengeLine == null) { throw new RuntimeException("未收到 challenge"); }
        Map<String, Object> challenge = mapper.readValue(challengeLine, new TypeReference<>() {});
        log.info("[ConferenceAgent] 收到 challenge: type={}", challenge.get("type"));

        // 2. 发送注册
        Map<String, Object> reg = new LinkedHashMap<>();
        reg.put("type", "register");
        reg.put("agent_id", agentId);
        reg.put("secret", agentSecret);
        reg.put("protocols", List.of("CONFERENCE"));
        reg.put("codecs", List.of());
        reg.put("transport", "TCP");
        reg.put("capabilities", Map.of("agent.type", "java/conference"));
        reg.put("agent_type", "java/agent-conference");
        send(reg);

        // 3. 读取注册响应
        String regResp = reader.readLine();
        if (regResp == null) { throw new RuntimeException("注册无响应"); }
        Map<String, Object> regResult = mapper.readValue(regResp, new TypeReference<>() {});
        String status = (String) regResult.get("status");
        if (!"OK".equals(status)) {
            throw new RuntimeException("注册失败: " + regResp);
        }
        Object hb = regResult.get("heartbeat_interval");
        if (hb instanceof Number) {
            heartbeatIntervalMs = ((Number) hb).longValue() * 1000;
        }
        log.info("[ConferenceAgent] 注册成功, 心跳间隔={}ms", heartbeatIntervalMs);

        // 4. 启动心跳
        startHeartbeat();

        // 5. 消息循环
        log.info("[ConferenceAgent] 开始消息循环");
        String line;
        while (running.get() && (line = reader.readLine()) != null) {
            try {
                handleMessage(line);
            }
 catch (Exception e) {
                log.warn("[ConferenceAgent] 消息处理异常: {}", e.getMessage());
            }
        }

        log.warn("[ConferenceAgent] Gateway 连接已关闭");
        stop();
    }

    /**
     * 停止
     */
    public void stop() {
        running.set(false);
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdown();
            heartbeatExecutor = null;
        }
        try { if (socket != null) { socket.close(); } }
 catch (Exception ignored) {}
        rooms.clear();
        sessionRooms.clear();
        log.info("[ConferenceAgent] 已停止");
    }

    // ── 心跳 ────────────────────────────────────────────────

    private void startHeartbeat() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-hb");
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleWithFixedDelay(() -> {
            try {
                send(Map.of("type", "heartbeat", "agent_id", agentId, "sentAt", System.currentTimeMillis()));
            }
 catch (Exception e) {
                log.warn("[ConferenceAgent] 心跳发送失败: {}", e.getMessage());
            }
        }, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
    }

    // ── 消息处理 ────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void handleMessage(String line) throws Exception {
        Map<String, Object> msg = mapper.readValue(line, new TypeReference<>() {});
        String type = (String) msg.get("type");
        if (type == null) { return; }

        switch (type) {
            case "heartbeat" -> log.debug("[ConferenceAgent] 心跳响应: {}", msg.get("status"));
            case "connect" -> handleConnect(msg);
            case "disconnect" -> handleDisconnect(msg);
            case "webrtc_offer", "webrtc_answer", "ice_candidate" -> handleWebRTCSignal(msg);
            case "webrtc_request" -> handleWebRTCRequest(msg);
            default -> log.debug("[ConferenceAgent] 未知消息类型: {}", type);
        }
    }

    /**
     * 处理连接请求。将 session 加入房间，返回 room_joined，并广播 participant_joined。
     */
    @SuppressWarnings("unchecked")
    private void handleConnect(Map<String, Object> msg) throws Exception {
        String sessionId = (String) msg.get("sessionId");
        Map<String, Object> target = (Map<String, Object>) msg.get("target");
        if (sessionId == null || target == null) { return; }

        String roomId = (String) target.get("roomId");
        if (roomId == null || roomId.isEmpty()) { roomId = "default"; }
        String mode = (String) target.get("mode");

        // 加入房间
        sessionRooms.put(sessionId, roomId);
        List<String> participants = rooms.computeIfAbsent(roomId, k -> Collections.synchronizedList(new ArrayList<>()));
        List<String> existingParticipants;
        synchronized (participants) {
            existingParticipants = new ArrayList<>(participants);
            participants.add(sessionId);
        }

        log.info("[ConferenceAgent] session={} 加入 room={} (共 {})", sessionId, roomId, participants.size());

        // 发送 room_joined 给新参与者（包含现有参与者列表）
        List<Map<String, String>> participantList = new ArrayList<>();
        for (String sid : existingParticipants) {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("sessionId", sid);
            String pMode = (String) target.get("mode");
            // 简化: 第一个加入的为 host;
            p.put("mode", existingParticipants.indexOf(sid) == 0 ? "host" : "guest");
            participantList.add(p);
        }
        Map<String, Object> roomJoined = new LinkedHashMap<>();
        roomJoined.put("type", "room_joined");
        roomJoined.put("sessionId", sessionId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("participants", participantList);
        data.put("mode", mode != null ? mode : (existingParticipants.isEmpty() ? "host" : "guest"));
        data.put("participantCount", existingParticipants.size() + 1);
        roomJoined.put("data", data);
        send(roomJoined);

        // 广播 participant_joined 给 room 中的其他参与者
        for (String existingSid : existingParticipants) {
            Map<String, Object> joined = new LinkedHashMap<>();
            joined.put("type", "participant_joined");
            joined.put("sessionId", existingSid);
            Map<String, Object> pdata = new LinkedHashMap<>();
            pdata.put("sessionId", sessionId);
            pdata.put("participantCount", participants.size());
            joined.put("data", pdata);
            send(joined);
        }
    }

    /**
     * 处理断开请求。从房间移除 session，广播 participant_left。
     */
    private void handleDisconnect(Map<String, Object> msg) throws Exception {
        String sessionId = (String) msg.get("sessionId");
        if (sessionId == null) { return; }

        String roomId = sessionRooms.remove(sessionId);
        if (roomId == null) { return; }

        List<String> participants = rooms.get(roomId);
        if (participants != null) {
            synchronized (participants) {
                participants.remove(sessionId);
                if (participants.isEmpty()) {
                    rooms.remove(roomId);
                }
            }
            // 广播 participant_left 给剩余参与者
            for (String remaining : participants) {
                Map<String, Object> left = new LinkedHashMap<>();
                left.put("type", "participant_left");
                left.put("sessionId", remaining);
                Map<String, Object> pdata = new LinkedHashMap<>();
                pdata.put("sessionId", sessionId);
                pdata.put("participantCount", participants.size());
                left.put("data", pdata);
                send(left);
            }
        }
        log.info("[ConferenceAgent] session={} 离开 room={}", sessionId, roomId);
    }

    /**
     * 处理 WebRTC 信令消息。根据 targetSessionId 定向转发或广播给同房间其他人。
     * 添加 fromSessionId 使接收端知道发送者。
     */
    private void handleWebRTCSignal(Map<String, Object> msg) throws Exception {
        String sessionId = (String) msg.get("sessionId");
        if (sessionId == null) { return; }

        String roomId = sessionRooms.get(sessionId);
        if (roomId == null) {
            log.warn("[ConferenceAgent] session={} 不在任何房间中, 丢弃信令", sessionId);
            return;
        }

        String targetSessionId = (String) msg.get("targetSessionId");
        String type = (String) msg.get("type");

        if (targetSessionId != null && !targetSessionId.isEmpty()) {
            // 定向转发给指定 sessionId
            Map<String, Object> forward = new LinkedHashMap<>(msg);
            forward.put("sessionId", targetSessionId);
            forward.put("fromSessionId", sessionId);
            send(forward);
            log.debug("[ConferenceAgent] {}: {} → {} (定向)", type, sessionId, targetSessionId);
        }
 else {
            // 广播给同房间其他人
            List<String> participants = rooms.get(roomId);
            if (participants == null) { return; }
            List<String> others;
            synchronized (participants) {
                others = new ArrayList<>(participants);
            }
            for (String other : others) {
                if (other.equals(sessionId)) { continue; }
                Map<String, Object> forward = new LinkedHashMap<>(msg);
                forward.put("sessionId", other);
                forward.put("fromSessionId", sessionId);
                send(forward);
            }
            log.debug("[ConferenceAgent] {}: {} → room({}) (广播)", type, sessionId, others.size() - 1);
        }
    }

    /**
     * 处理 webrtc_request。将请求转发给房间中的其他参与者，
     * 让它们为此 session 创建 offer。
     */
    private void handleWebRTCRequest(Map<String, Object> msg) throws Exception {
        String sessionId = (String) msg.get("sessionId");
        if (sessionId == null) { return; }

        String roomId = sessionRooms.get(sessionId);
        if (roomId == null) { return; }

        List<String> participants = rooms.get(roomId);
        if (participants == null) { return; }

        for (String other : participants) {
            if (other.equals(sessionId)) { continue; }
            Map<String, Object> forward = new LinkedHashMap<>(msg);
            forward.put("sessionId", other);
            forward.put("fromSessionId", sessionId);
            send(forward);
        }
        log.debug("[ConferenceAgent] webrtc_request: {} → room({})", sessionId, participants.size());
    }

    // ── 辅助方法 ────────────────────────────────────────────

    private void send(Map<String, ?> data) throws Exception {
        String json = mapper.writeValueAsString(data);
        synchronized (writer) {
            writer.println(json);
        }
    }

    private void send(String json) {
        synchronized (writer) {
            writer.println(json);
        }
    }

    // ── 主入口 ──────────────────────────────────────────────

    /**
     * main
     * @param args 参数
     */
    public static void main(String[] args) throws Exception {
        // 简单参数解析 --key=value
        ConferenceAgent agent = new ConferenceAgent();

        for (String arg : args) {
            if (arg.startsWith("--")) {
                String[] parts = arg.substring(2).split("=", 2);
                if (parts.length != 2) { continue; }
                switch (parts[0]) {
                    case "gateway-host" -> agent.gatewayHost(parts[1]);
                    case "gateway-port" -> agent.gatewayPort(Integer.parseInt(parts[1]));
                    case "agent-id" -> agent.agentId(parts[1]);
                    case "agent-secret" -> agent.agentSecret(parts[1]);
                }
            }
        }

        System.out.println("=== Conference Agent 启动 ===");
        System.out.println("Gateway: " + agent.gatewayHost + ":" + agent.gatewayPort);
        System.out.println("Agent ID: " + agent.agentId);
        System.out.println("按 Ctrl+C 停止");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("正在停止...");
            agent.stop();
        }));

        try {
            agent.start();
        }
 catch (Exception e) {
            System.err.println("Agent 异常: " + e.getMessage());
            e.printStackTrace();
            agent.stop();
            System.exit(1);
        }
    }
}
