package com.chua.remote.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.impl.JdkHttpServer;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.remote.core.RemoteServer;
import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.core.transport.RemoteTransport;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import com.chua.remote.protocol.model.AgentInfo;
import com.chua.remote.protocol.model.ControllerInfo;
import com.chua.remote.protocol.model.Session;
import com.chua.remote.protocol.spi.RemoteServerSPI;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Spi("remote-gateway")
public class GatewayServer implements RemoteServerSPI {

    private final RemoteServer server;
    private final AuthManager authManager;
    private final SessionManager sessionManager;
    final RouteManager routeManager;
    final TranscodeEngine transcodeEngine;
    private GatewayCallback gatewayCallback;
    private final JdkHttpServer httpServer;
    private final int httpPort;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ControllerWebSocketServer controllerWs;

    public GatewayServer(ServerSetting setting) {
        this.server = new RemoteServer(setting);
        this.authManager = new AuthManager();
        this.sessionManager = new SessionManager();
        this.routeManager = new RouteManager(sessionManager);
        this.transcodeEngine = new TranscodeEngine();
        this.httpPort = setting.getPort() + 1;
        ServerSetting httpSetting = ServerSetting.builder()
                .port(httpPort)
                .contextPath("/")
                .build();
        this.httpServer = new JdkHttpServer(httpSetting) {
            @Override
            protected void doStart() {
                try {
                    com.sun.net.httpserver.HttpServer delegate =
                            com.sun.net.httpserver.HttpServer.create(
                                    new java.net.InetSocketAddress(httpPort), 0);
                    delegate.createContext("/verify", exchange -> handleVerify(exchange));
                    delegate.createContext("/api/remote/config", exchange -> handleConfig(exchange));
                    delegate.createContext("/api/remote/gateways", exchange -> handleGateways(exchange));
                    delegate.createContext("/api/remote/agents", exchange -> handleAgents(exchange));
                    delegate.createContext("/api/remote/access-codes", exchange -> handleAccessCodes(exchange));
                    delegate.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
                    delegate.start();
                    log.info("HTTP 验证服务器已启动 on port:{}", httpPort);
                } catch (Exception e) {
                    throw new RuntimeException("HTTP 验证服务器启动失败: port=" + httpPort, e);
                }
            }

            @Override
            protected void doStop() {
                log.info("HTTP 验证服务器已停止");
            }
        };
        initHandlers();
    }

    private void initHandlers() {
        server.getTransport().on(MessageType.SIGNAL, this::handleSignal);
        server.getTransport().on(MessageType.CTRL, this::handleControl);
        server.getTransport().on(MessageType.DATA, this::handleData);
        server.getTransport().on(MessageType.SSH, this::handleSSH);
    }

    public void start() {
        server.start();
        httpServer.start();
        controllerWs = new ControllerWebSocketServer(httpPort + 2, server.getTransport());
        controllerWs.setReuseAddr(true);
        controllerWs.start();
        log.info("远控网关已启动 (帧端口:{}, HTTP端口:{}, ControllerWS端口:{})", httpPort - 1, httpPort, httpPort + 2);
    }

    public void stop() {
        server.stop();
        httpServer.stop();
        if (controllerWs != null) {
            try {
                controllerWs.stop(1000, "gateway shutdown");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("远控网关已停止");
    }

    public void setGatewayCallback(GatewayCallback callback) {
        this.gatewayCallback = callback;
    }

    public RemoteTransport getTransport() {
        return server.getTransport();
    }

    private void handleVerify(com.sun.net.httpserver.HttpExchange exchange) {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"success\":false,\"message\":\"仅支持POST\"}");
            return;
        }
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = parseJsonParams(body);
            String agentId = params.get("agentId");
            String verifyCode = params.get("verifyCode");

            if (agentId == null || verifyCode == null) {
                sendJson(exchange, 400, "{\"success\":false,\"message\":\"参数不完整\"}");
                return;
            }
            if (!authManager.verifyAgent(agentId, verifyCode)) {
                sendJson(exchange, 401, "{\"success\":false,\"message\":\"验证码校验失败\"}");
                return;
            }
            var agentInfo = sessionManager.getAgent(agentId);
            if (agentInfo == null) {
                sendJson(exchange, 404, "{\"success\":false,\"message\":\"被控端未注册\"}");
                return;
            }
            String json = String.format(
                    "{\"success\":true,\"agentType\":\"%s\",\"desktopSupported\":%s}",
                    agentInfo.getAgentType(),
                    agentInfo.getDesktopSupported() != null ? agentInfo.getDesktopSupported() : "true");
            sendJson(exchange, 200, json);
            log.info("HTTP验证通过: agentId={}", agentId);
        } catch (Exception e) {
            sendJson(exchange, 500,
                    "{\"success\":false,\"message\":\"验证异常:" + e.getMessage() + "\"}");
        }
    }

    private void sendJson(com.sun.net.httpserver.HttpExchange exchange, int code, String json) {
        try {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception e) {
            log.warn("发送HTTP响应失败", e);
        }
    }

    private Map<String, String> parseParams(String body) {
        Map<String, String> params = new HashMap<>();
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                params.put(kv[0], java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            } else if (kv.length == 1) {
                params.put(kv[0], "");
            }
        }
        return params;
    }

    private Map<String, String> parseJsonParams(String body) {
        Map<String, String> params = new HashMap<>();
        try {
            JsonNode node = MAPPER.readTree(body);
            node.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isNull()) {
                    params.put(entry.getKey(), entry.getValue().asText());
                }
            });
        } catch (Exception e) {
            log.warn("JSON解析失败: {}", body, e);
        }
        return params;
    }

    private volatile String savedConfig = "{}";

    private void handleConfig(com.sun.net.httpserver.HttpExchange exchange) {
        if ("GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 200, savedConfig);
            return;
        }
        if ("POST".equals(exchange.getRequestMethod())) {
            try {
                savedConfig = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                sendJson(exchange, 200, "{\"success\":true}");
            } catch (Exception e) {
                sendJson(exchange, 500, "{\"success\":false}");
            }
            return;
        }
        sendJson(exchange, 405, "{\"error\":\"method not allowed\"}");
    }

    private void handleGateways(com.sun.net.httpserver.HttpExchange exchange) {
        String json = "[{\"url\":\"tcp://localhost:9000\",\"name\":\"本地网关\"}]";
        sendJson(exchange, 200, json);
    }

    private void handleAgents(com.sun.net.httpserver.HttpExchange exchange) {
        if ("GET".equals(exchange.getRequestMethod())) {
            var all = sessionManager.getAgents();
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (var entry : all.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                var info = entry.getValue();
                sb.append(String.format(
                        "{\"id\":\"%s\",\"agentType\":\"%s\",\"platform\":\"%s\",\"desktopSupported\":%s,\"status\":\"在线\"}",
                        info.getId(), info.getAgentType(),
                        info.getPlatform() != null ? info.getPlatform() : "",
                        info.getDesktopSupported() != null ? info.getDesktopSupported() : false));
            }
            sb.append("]");
            sendJson(exchange, 200, sb.toString());
            return;
        }
        if ("DELETE".equals(exchange.getRequestMethod())) {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> params = parseParams(body);
                String agentId = params.get("agentId");
                if (agentId != null) {
                    sessionManager.removeAgent(agentId);
                }
                sendJson(exchange, 200, "{\"success\":true}");
            } catch (Exception e) {
                sendJson(exchange, 500, "{\"success\":false}");
            }
            return;
        }
        sendJson(exchange, 405, "{\"error\":\"method not allowed\"}");
    }

    private volatile java.util.List<String> accessCodeList = new java.util.concurrent.CopyOnWriteArrayList<>();

    private void handleAccessCodes(com.sun.net.httpserver.HttpExchange exchange) {
        if ("GET".equals(exchange.getRequestMethod())) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (String code : accessCodeList) {
                if (!first) sb.append(",");
                first = false;
                sb.append(String.format("{\"code\":\"%s\",\"type\":\"接入码\",\"status\":\"启用\"}", code));
            }
            sb.append("]");
            sendJson(exchange, 200, sb.toString());
            return;
        }
        if ("POST".equals(exchange.getRequestMethod())) {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                accessCodeList = java.util.Arrays.asList(
                        body.replaceAll("[\\[\\]\\s]", "").split(","));
                sendJson(exchange, 200, "{\"success\":true}");
            } catch (Exception e) {
                sendJson(exchange, 500, "{\"success\":false}");
            }
            return;
        }
        sendJson(exchange, 405, "{\"error\":\"method not allowed\"}");
    }

    private void handleSignal(Frame frame) {
        String kind = frame.getMetadata() != null
                ? frame.getMetadata().get(FrameCodec.METADATA_KIND) : null;
        if (AgentInfo.class.getSimpleName().equals(kind)) {
            AgentInfo agentInfo = FrameCodec.decodeSignal(frame, AgentInfo.class);
            if (agentInfo != null) {
                agentRegister(agentInfo);
            }
            return;
        }
        if (ControllerInfo.class.getSimpleName().equals(kind)) {
            ControllerInfo controllerInfo = FrameCodec.decodeSignal(frame, ControllerInfo.class);
            if (controllerInfo != null) {
                controllerConnect(controllerInfo);
            }
            return;
        }
        if (Session.class.getSimpleName().equals(kind)) {
            handleSessionRequest(frame);
            return;
        }
        log.debug("处理信令帧: sessionId={}, kind={}", frame.getSessionId(), kind);
    }

    private void handleSessionRequest(Frame frame) {
        Session request = FrameCodec.decodeSignal(frame, Session.class);
        if (request == null) return;
        String controllerId = request.getControllerSessionId();
        String agentId = request.getAgentId();
        String verifyCode = frame.getMetadata().get("verifyCode");
        boolean reverseTunnel = frame.getMetadata().get("reverseTunnelEnabled") != null
                && "true".equalsIgnoreCase(frame.getMetadata().get("reverseTunnelEnabled"));

        try {
            Session session = createSession(controllerId, agentId, verifyCode, reverseTunnel);
            var agentInfo = sessionManager.getAgent(agentId);

            if (agentInfo != null) {
                if (reverseTunnel && agentInfo.getExtra() != null) {
                    String tunnelPort = agentInfo.getExtra().get("reverseTunnelPort");
                    if (tunnelPort != null) {
                        log.info("通过反向隧道连接agent: agentId={}, tunnelPort={}", agentId, tunnelPort);
                        connectToAgentViaTunnel(agentId, Integer.parseInt(tunnelPort));
                    }
                }
                var notifyFrame = FrameCodec.encodeSignal(MessageType.SIGNAL, agentId, session);
                server.getTransport().send(agentId, notifyFrame);
            }

            var controllerFrame = FrameCodec.encodeSignal(MessageType.SIGNAL, controllerId, session);
            server.getTransport().send(controllerId, controllerFrame);
            log.info("会话建立: sessionId={}, agentId={}, controllerId={}, reverseTunnel={}",
                    session.getSessionId(), agentId, controllerId, reverseTunnel);
        } catch (Exception e) {
            log.error("会话建立失败: agentId={}, controllerId={}", agentId, controllerId, e);
        }
    }

    private void connectToAgentViaTunnel(String agentId, int tunnelPort) {
        try {
            var client = new com.chua.remote.core.transport.FrameClient(agentId, "tcp://127.0.0.1:" + tunnelPort);
            client.setListener(frame -> server.getTransport().dispatch(frame));
            client.connect();
            log.info("已通过反向隧道连接到agent: agentId={}, port={}", agentId, tunnelPort);
        } catch (Exception e) {
            log.error("反向隧道连接失败: agentId={}, port={}", agentId, tunnelPort, e);
        }
    }

    private void handleControl(Frame frame) {
        routeInputEvent(frame);
    }

    private void handleData(Frame frame) {
        log.debug("处理数据帧: sessionId={}", frame.getSessionId());
        if (frame.getType() == MessageType.DATA) {
            routeData(frame);
        }
    }

    private void routeData(Frame frame) {
        Frame routed = frame;
        Session session = sessionManager.getSession(frame.getSessionId());
        if (session != null && session.getNegotiatedCodec() != null
                && session.getNegotiatedCodec().isTranscoded()) {
            byte[] payload = transcodeEngine.transcode(session, frame.getPayload());
            if (payload != frame.getPayload()) {
                routed = Frame.builder()
                        .type(MessageType.DATA)
                        .sessionId(frame.getSessionId())
                        .payload(payload)
                        .metadata(frame.getMetadata())
                        .build();
            }
        }
        if (gatewayCallback != null) {
            gatewayCallback.onFrame(routed);
        }
        server.getTransport().publish(routed);
        log.debug("路由数据帧到控制端: sessionId={}", frame.getSessionId());
    }

    private void routeInputEvent(Frame frame) {
        Session session = sessionManager.getSession(frame.getSessionId());
        if (session == null) {
            log.warn("会话不存在，丢弃控制帧: sessionId={}", frame.getSessionId());
            return;
        }
        Frame routed = Frame.builder()
                .type(MessageType.CTRL)
                .sessionId(session.getAgentId())
                .payload(frame.getPayload())
                .metadata(frame.getMetadata())
                .build();
        if (gatewayCallback != null) {
            gatewayCallback.onFrame(routed);
        }
        server.getTransport().send(session.getAgentId(), routed);
        log.debug("路由键鼠事件到被控端: agentId={}, sessionId={}",
                session.getAgentId(), session.getSessionId());
    }

    private void handleSSH(Frame frame) {
        Map<String, String> meta = frame.getMetadata();
        String action = meta != null ? meta.get("sshAction") : null;
        log.info("handleSSH: action={}, sessionId={}, agentId={}", action, frame.getSessionId(), frame.getMetadata());

        if ("output".equals(action) || "started".equals(action) || "error".equals(action) || "stopped".equals(action)) {
            if (controllerWs != null) {
                controllerWs.onAgentSSHFrame(frame);
            }
            return;
        }

        Session session = sessionManager.getSession(frame.getSessionId());
        if (session == null) {
            log.debug("SSH帧无会话: sessionId={}", frame.getSessionId());
            return;
        }
        String agentId = session.getAgentId();
        Frame routed = Frame.builder()
                .type(MessageType.SSH)
                .sessionId(agentId)
                .payload(frame.getPayload())
                .metadata(frame.getMetadata())
                .build();
        server.getTransport().send(agentId, routed);
        log.debug("路由SSH帧到被控端: agentId={}, sessionId={}", agentId, frame.getSessionId());
    }

    @Override
    public String agentRegister(AgentInfo agentInfo) {
        AgentInfo existing = sessionManager.getAgent(agentInfo.getId());
        if (existing != null && !Objects.equals(existing.getVerifyCode(), agentInfo.getVerifyCode())) {
            throw new SecurityException("被控端重复注册且验证码不一致: agentId=" + agentInfo.getId());
        }
        sessionManager.registerAgent(agentInfo);
        authManager.registerAgent(agentInfo.getId(), agentInfo.getVerifyCode());
        authManager.registerAgentAccessCode(agentInfo.getAccessCode());
        log.info("被控端注册成功: id={}, type={}, accessCode={}",
                agentInfo.getId(), agentInfo.getAgentType(), agentInfo.getAccessCode());
        return agentInfo.getId();
    }

    @Override
    public String controllerConnect(ControllerInfo controllerInfo) {
        authManager.registerController(controllerInfo.getAccessToken(), controllerInfo);
        sessionManager.registerController(controllerInfo);
        log.info("控制端接入成功: accessToken={}, targetAgentId={}",
                controllerInfo.getAccessToken(), controllerInfo.getTargetAgentId());
        return controllerInfo.getAccessToken();
    }

    @Override
    public Session createSession(String controllerId, String agentId, String verifyCode) {
        return createSession(controllerId, agentId, verifyCode, false);
    }

    @Override
    public Session createSession(String controllerId, String agentId, String verifyCode, boolean reverseTunnelEnabled) {
        if (!authManager.verifyAgent(agentId, verifyCode)) {
            throw new SecurityException("验证码校验失败");
        }
        var agentInfo = sessionManager.getAgent(agentId);
        var controllerInfo = sessionManager.getController(controllerId);
        if (agentInfo == null || controllerInfo == null) {
            throw new IllegalStateException("被控端或控制端未注册");
        }
        if (controllerInfo.isReverseTunnelEnabled()) {
            reverseTunnelEnabled = true;
        }
        var negotiated = transcodeEngine.negotiate(
                agentInfo.getEncodingCapability(),
                controllerInfo.getDecodingCapability());
        var session = Session.builder()
                .sessionId(java.util.UUID.randomUUID().toString())
                .controllerSessionId(controllerId)
                .agentId(agentId)
                .status(Session.SessionStatus.ACTIVE)
                .negotiatedCodec(negotiated)
                .agentType(agentInfo.getAgentType())
                .createTime(System.currentTimeMillis())
                .reverseTunnelEnabled(reverseTunnelEnabled)
                .build();
        sessionManager.addSession(session);
        log.info("会话创建成功: sessionId={}, agentId={}, controllerId={}, transcoded={}, reverseTunnelEnabled={}",
                session.getSessionId(), agentId, controllerId, negotiated.isTranscoded(), reverseTunnelEnabled);
        return session;
    }

    @Override
    public boolean authenticate(String token) {
        return authManager.verifyController(token) || authManager.verifyAgentToken(token);
    }

    @Override
    public void closeSession(String sessionId) {
        sessionManager.closeSession(sessionId);
        log.info("会话已关闭: sessionId={}", sessionId);
    }

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9000;
        ServerSetting setting = ServerSetting.builder().port(port).build();
        GatewayServer server = new GatewayServer(setting);
        server.start();
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                log.error("未捕获异常 thread={}", t.getName(), e));
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}

@FunctionalInterface
interface GatewayCallback {
    void onFrame(Frame frame);
}
