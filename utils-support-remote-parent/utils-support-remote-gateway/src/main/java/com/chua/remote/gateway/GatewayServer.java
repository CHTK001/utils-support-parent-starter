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
import com.chua.remote.gateway.store.AccessCodeStore;
import com.chua.remote.gateway.store.AccessCodeStores;
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
                    createCorsContext(delegate, "/verify", exchange -> handleVerify(exchange));
                    createCorsContext(delegate, "/api/remote/config", exchange -> handleConfig(exchange));
                    createCorsContext(delegate, "/api/remote/gateways", exchange -> handleGateways(exchange));
                    createCorsContext(delegate, "/api/remote/agents", exchange -> handleAgents(exchange));
                    createCorsContext(delegate, "/api/remote/access-codes", exchange -> handleAccessCodes(exchange));
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
        // 接入码存储：SPI 加载（spring 生态 MyBatis 实现持久化到数据库；独立运行回退内存）——启动从存储恢复
        accessCodeStore = AccessCodeStores.load();
        for (AccessCodeInfo info : accessCodeStore.findAll()) {
            accessCodes.put(info.getCode(), info);
        }
        // 默认接入码（与本地 agent 启动参数对齐——保证开箱即用）
        if (accessCodes.isEmpty()) {
            AccessCodeInfo def = new AccessCodeInfo();
            def.setCode("0000");
            def.setType("接入码");
            def.setStatus("启用");
            def.setCreatedAt(java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            accessCodes.put("0000", def);
            accessCodeStore.save(def);
        }
    }

    private void initHandlers() {
        server.getTransport().on(MessageType.SIGNAL, this::handleSignal);
        server.getTransport().on(MessageType.CTRL, this::handleControl);
        server.getTransport().on(MessageType.DATA, this::handleData);
        server.getTransport().on(MessageType.SSH, this::handleSSH);
        server.getTransport().on(MessageType.VNC, this::handleVNC);
        server.getTransport().on(MessageType.RDP, this::handleRDP);
    }

    public void start() {
        server.start();
        httpServer.start();
        // Controller WS 独立端口（9003——SSH/VNC/RDP 会话），HTTP 由 JdkHttpServer 承载（9001）
        controllerWs = new ControllerWebSocketServer(httpPort + 2, server.getTransport(), sessionManager, null);
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
            // 接入码是 agent 接入网关的鉴权凭证（agent 注册时校验）——控制端连接仅校验 agentId+验证码
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

    /**
     * 注册带 CORS 的 HTTP 上下文：浏览器跨域（前端 8091 → 网关 9001）访问必须放行，
     * 否则 /verify 等请求被浏览器拦截——连接永远建立不起来。
     *
     * @param server  JDK HttpServer
     * @param path    上下文路径
     * @param handler 实际处理器
     */
    private void createCorsContext(com.sun.net.httpserver.HttpServer server, String path,
                                   com.sun.net.httpserver.HttpHandler handler) {
        server.createContext(path, exchange -> {
            var headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");
            headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            headers.add("Access-Control-Allow-Headers", "Content-Type, Authorization");
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            handler.handle(exchange);
        });
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

    private volatile String savedConfig = loadConfigFile();

    private static final java.io.File CONFIG_FILE = new java.io.File(
            System.getProperty("user.dir"), "remote-config.json");

    private static String loadConfigFile() {
        try {
            if (CONFIG_FILE.exists()) {
                return java.nio.file.Files.readString(CONFIG_FILE.toPath(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("读取配置文件失败: {}", e.getMessage());
        }
        return "{}";
    }

    private void handleConfig(com.sun.net.httpserver.HttpExchange exchange) {
        if ("GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 200, savedConfig);
            return;
        }
        if ("POST".equals(exchange.getRequestMethod())) {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                savedConfig = body;
                java.nio.file.Files.writeString(CONFIG_FILE.toPath(), body, StandardCharsets.UTF_8);
                sendJson(exchange, 200, "{\"success\":true}");
            } catch (Exception e) {
                log.error("保存配置失败", e);
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

    /** 平台接入码表：code → 接入码信息（含过期/上限/接入统计） */
    private final java.util.Map<String, AccessCodeInfo> accessCodes = new java.util.concurrent.ConcurrentHashMap<>();

    /** 接入码存储 SPI（spring 生态 MyBatis 实现持久化到数据库；独立运行回退内存） */
    private AccessCodeStore accessCodeStore;

    private void handleAccessCodes(com.sun.net.httpserver.HttpExchange exchange) {
        try {
            if ("GET".equals(exchange.getRequestMethod())) {
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (AccessCodeInfo info : accessCodes.values()) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append(toAccessCodeJson(info));
                }
                sb.append("]");
                sendJson(exchange, 200, sb.toString());
                return;
            }
            if ("POST".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> params = parseJsonParams(body);
                String code = params.get("code");
                if (code == null || code.isBlank()) {
                    sendJson(exchange, 400, "{\"success\":false,\"message\":\"接入码不能为空\"}");
                    return;
                }
                AccessCodeInfo existing = accessCodes.get(code);
                if (existing == null) {
                    existing = new AccessCodeInfo();
                    existing.setCode(code);
                    existing.setCreatedAt(java.time.LocalDateTime.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                    existing.setType("接入码");
                    existing.setStatus("启用");
                    accessCodes.put(code, existing);
                }
                if (params.containsKey("type") && !params.get("type").isBlank()) existing.setType(params.get("type"));
                if (params.containsKey("status") && !params.get("status").isBlank()) existing.setStatus(params.get("status"));
                if (params.containsKey("expiresAt")) existing.setExpiresAt(params.get("expiresAt"));
                if (params.containsKey("ipWhitelist")) existing.setIpWhitelist(params.get("ipWhitelist"));
                if (params.containsKey("maxAgents")) {
                    try {
                        existing.setMaxAgents(Integer.parseInt(params.get("maxAgents")));
                    } catch (Exception ignore) {
                    }
                }
                accessCodeStore.save(existing);
                sendJson(exchange, 200, "{\"success\":true,\"code\":\"" + code + "\"}");
                return;
            }
            if ("DELETE".equals(exchange.getRequestMethod())) {
                String code = parseQueryParam(exchange.getRequestURI().getQuery(), "code");
                if (code == null || !accessCodes.containsKey(code)) {
                    sendJson(exchange, 404, "{\"success\":false,\"message\":\"接入码不存在\"}");
                    return;
                }
                accessCodes.remove(code);
                accessCodeStore.delete(code);
                sendJson(exchange, 200, "{\"success\":true}");
                return;
            }
            sendJson(exchange, 405, "{\"error\":\"method not allowed\"}");
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"success\":false,\"message\":\"" + e.getMessage() + "\"}");
        }
    }

    /** 接入码 → JSON（含过期/上限/接入统计） */
    private String toAccessCodeJson(AccessCodeInfo info) {
        StringBuilder agents = new StringBuilder("[");
        boolean first = true;
        for (AccessCodeInfo.AgentAccessStat stat : info.getAgents()) {
            if (!first) agents.append(",");
            first = false;
            agents.append(String.format("{\"agentId\":\"%s\",\"ip\":\"%s\",\"time\":\"%s\"}",
                    stat.getAgentId(), stat.getIp(), stat.getTime()));
        }
        agents.append("]");
        return String.format(
                "{\"code\":\"%s\",\"type\":\"%s\",\"status\":\"%s\",\"createdAt\":\"%s\",\"expiresAt\":\"%s\",\"ipWhitelist\":\"%s\",\"maxAgents\":%d,\"usedAgents\":%d,\"agents\":%s}",
                info.getCode(),
                info.getType() != null ? info.getType() : "接入码",
                info.getStatus() != null ? info.getStatus() : "启用",
                info.getCreatedAt() != null ? info.getCreatedAt() : "",
                info.getExpiresAt() != null ? info.getExpiresAt() : "",
                info.getIpWhitelist() != null ? info.getIpWhitelist() : "",
                info.getMaxAgents(), info.getUsedAgents(), agents);
    }

    /** 解析 query 参数（?code=xxx） */
    private String parseQueryParam(String query, String key) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && key.equals(kv[0])) {
                try {
                    return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return kv[1];
                }
            }
        }
        return null;
    }

    private void handleSignal(Frame frame) {
        String kind = frame.getMetadata() != null
                ? frame.getMetadata().get(FrameCodec.METADATA_KIND) : null;
        if (AgentInfo.class.getSimpleName().equals(kind)) {
            AgentInfo agentInfo = FrameCodec.decodeSignal(frame, AgentInfo.class);
            if (agentInfo != null) {
                String remoteIp = frame.getMetadata() != null
                        ? frame.getMetadata().get(com.chua.remote.core.transport.FrameServer.META_REMOTE_ADDR) : null;
                try {
                    agentRegister(agentInfo, remoteIp);
                } catch (SecurityException se) {
                    log.warn("接入码校验拒绝: {}", se.getMessage());
                }
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

    private void handleVNC(Frame frame) {
        Map<String, String> meta = frame.getMetadata();
        String action = meta != null ? meta.get("vncAction") : null;

        if ("frame".equals(action) || "started".equals(action) || "error".equals(action) || "stopped".equals(action)) {
            if (controllerWs != null) {
                controllerWs.onAgentVncFrame(frame);
            }
            return;
        }

        String agentId = frame.getSessionId();
        Frame routed = Frame.builder()
                .type(MessageType.VNC)
                .sessionId(agentId)
                .payload(frame.getPayload())
                .metadata(frame.getMetadata())
                .build();
        server.getTransport().send(agentId, routed);
        log.debug("路由VNC帧到被控端: agentId={}, action={}", agentId, action);
    }

    private void handleRDP(Frame frame) {
        Map<String, String> meta = frame.getMetadata();
        String action = meta != null ? meta.get("rdpAction") : null;

        if ("frame".equals(action) || "started".equals(action) || "error".equals(action)
                || "stopped".equals(action) || (action != null && action.startsWith("webrtc-"))) {
            if (controllerWs != null) {
                controllerWs.onAgentRdpFrame(frame);
            }
            return;
        }

        String agentId = frame.getSessionId();
        Frame routed = Frame.builder()
                .type(MessageType.RDP)
                .sessionId(agentId)
                .payload(frame.getPayload())
                .metadata(frame.getMetadata())
                .build();
        server.getTransport().send(agentId, routed);
        log.debug("路由RDP帧到被控端: agentId={}, action={}", agentId, action);
    }

    @Override
    public String agentRegister(AgentInfo agentInfo, String remoteIp) {
        // 平台接入码校验：被控端接入平台必须提供平台颁发的接入码（未授权/停用/过期/达上限拒绝）
        String code = agentInfo.getAccessCode();
        AccessCodeInfo acInfo = code == null ? null : accessCodes.get(code);
        if (acInfo == null) {
            throw new SecurityException("被控端接入被拒绝: 未授权接入码 agentId=" + agentInfo.getId());
        }
        if (!"启用".equals(acInfo.getStatus()) || acInfo.isExpired() || acInfo.isFull()) {
            throw new SecurityException("被控端接入被拒绝: 接入码停用/过期/达上限 agentId=" + agentInfo.getId());
        }
        // 白名单 IP 校验：agent 上报的全部 IP（多网卡）+ 连接来源 IP，命中其一即通过；白名单空=不限制
        java.util.List<String> agentIps = new java.util.ArrayList<>();
        if (agentInfo.getIps() != null) {
            agentIps.addAll(agentInfo.getIps());
        }
        if (remoteIp != null && !remoteIp.isBlank() && !agentIps.contains(remoteIp)) {
            agentIps.add(remoteIp);
        }
        if (!acInfo.isAllowedIp(agentIps)) {
            throw new SecurityException("被控端接入被拒绝: IP 不在白名单 agentId=" + agentInfo.getId());
        }
        AgentInfo existing = sessionManager.getAgent(agentInfo.getId());
        if (existing != null && !Objects.equals(existing.getVerifyCode(), agentInfo.getVerifyCode())) {
            throw new SecurityException("被控端重复注册且验证码不一致: agentId=" + agentInfo.getId());
        }
        sessionManager.registerAgent(agentInfo);
        authManager.registerAgent(agentInfo.getId(), agentInfo.getVerifyCode());
        authManager.registerAgentAccessCode(agentInfo.getAccessCode());
        // 记录/刷新接入统计（agentId/IP/时间——按 agentId 去重；重连时刷新 IP 与接入时间）
        String statIp = remoteIp != null && !remoteIp.isBlank() ? remoteIp : String.join(",", agentIps);
        AccessCodeInfo.AgentAccessStat stat = acInfo.getAgents().stream()
                .filter(a -> agentInfo.getId().equals(a.getAgentId()))
                .findFirst().orElse(null);
        if (stat == null) {
            acInfo.getAgents().add(new AccessCodeInfo.AgentAccessStat(
                    agentInfo.getId(), statIp, java.time.LocalDateTime.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
            acInfo.setUsedAgents(acInfo.getAgents().size());
        } else {
            stat.setIp(statIp);
            stat.setTime(java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        }
        accessCodeStore.save(acInfo);
        log.info("接入统计: agentId={}, remoteIp={}, agentIps={}, statIp={}",
                agentInfo.getId(), remoteIp, agentIps, statIp);
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
        log.info("网关主线程阻塞等待中...");
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

@FunctionalInterface
interface GatewayCallback {
    void onFrame(Frame frame);
}
