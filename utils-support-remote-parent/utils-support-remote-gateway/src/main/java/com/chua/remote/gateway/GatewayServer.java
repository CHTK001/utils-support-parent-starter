package com.chua.remote.gateway;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.remote.core.RemoteServer;
import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.core.transport.RemoteTransport;
import com.chua.remote.protocol.capability.CodecProfile;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import com.chua.remote.protocol.model.AgentInfo;
import com.chua.remote.protocol.model.ControllerInfo;
import com.chua.remote.protocol.model.Session;
import com.chua.remote.protocol.spi.RemoteServerSPI;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.net.URI;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * 远控网关入口。
 *
 * <p>负责管理被控端和控制端的连接、鉴权、信令路由和会话生命周期。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("remote-gateway")
public class GatewayServer implements RemoteServerSPI {

    /** 底层网关服务端 */
    private final RemoteServer server;

    /** 鉴权管理器 */
    private final AuthManager authManager;

    /** 会话管理器 */
    private final SessionManager sessionManager;

    /** 信令路由管理器 */
    final RouteManager routeManager;

    /** 转码引擎 */
    final TranscodeEngine transcodeEngine;

    /** 帧回调（用于实际转发到 RemoteTransport） */
    private GatewayCallback gatewayCallback;

    /** HTTP 验证服务器（JDK 内置轻量 HTTP 服务） */
    private final com.sun.net.httpserver.HttpServer httpServer;

    /** HTTP 验证端口 */
    private final int httpPort;

    public GatewayServer(ServerSetting setting) {
        this.server = new RemoteServer(setting);
        this.authManager = new AuthManager();
        this.sessionManager = new SessionManager();
        this.routeManager = new RouteManager(sessionManager);
        this.transcodeEngine = new TranscodeEngine();
        this.httpPort = setting.getPort() + 1;
        try {
            this.httpServer = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(httpPort), 0);
            this.httpServer.createContext("/verify", new VerifyHandler());
            this.httpServer.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        } catch (IOException e) {
            throw new RuntimeException("HTTP 验证服务器启动失败: port=" + httpPort, e);
        }
        initHandlers();
    }

    private void initHandlers() {
        server.getTransport().on(MessageType.SIGNAL, frame -> {
            handleSignal(frame);
        });
        server.getTransport().on(MessageType.CTRL, frame -> {
            handleControl(frame);
        });
        server.getTransport().on(MessageType.DATA, frame -> {
            handleData(frame);
        });
    }

    public void start() {
        server.start();
        log.info("远控网关已启动");
    }

    public void stop() {
        server.stop();
        log.info("远控网关已停止");
    }

    public void setGatewayCallback(GatewayCallback callback) {
        this.gatewayCallback = callback;
    }

    /**
     * 获取底层传输层。
     *
     * <p>供嵌入式部署注入或观测传输帧使用。</p>
     *
     * @return 传输层
     */
    public RemoteTransport getTransport() {
        return server.getTransport();
    }

    /**
     * 处理信令帧：按载荷对象类型分发到被控端注册或控制端接入。
     *
     * @param frame 信令帧
     */
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
        log.debug("处理信令帧: sessionId={}, kind={}", frame.getSessionId(), kind);
    }

    /**
     * 处理控制帧：将键鼠事件路由到会话对应的被控端。
     *
     * @param frame 控制帧（sessionId 为远控会话 id）
     */
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
        // 协商无交集时：网关按会话协商结果兜底转码后转发
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

    /**
     * 路由键鼠事件到被控端。
     *
     * <p>控制帧以远控会话 id 标识，路由前改写为被控端 id 以定位目标连接。</p>
     *
     * @param frame 控制帧
     */
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

    /**
     * 被控端注册。
     *
     * <p>验证码由被控端自行生成并随注册上报，作为后续控制端发起会话的凭据；
     * 重复注册时校验验证码一致性，防止身份冒用。</p>
     *
     * @param agentInfo 被控端信息
     * @return 被控端 id
     */
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

    /**
     * 控制端接入。
     *
     * <p>接入令牌即凭据（capability token）：首次接入完成注册，重复接入更新接入信息；
     * 后续 {@link #authenticate(String)} 与会话校验均以已注册令牌为准。</p>
     *
     * @param controllerInfo 控制端信息
     * @return 接入令牌
     */
    @Override
    public String controllerConnect(ControllerInfo controllerInfo) {
        authManager.registerController(controllerInfo.getAccessToken(), controllerInfo);
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

    /**
     * HTTP 验证处理类。
     *
     * <p>控制端点击连接前先调用此端点验证参数（agentID、验证码、reverseTunnelEnabled）。</p>
     */
    private class VerifyHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> params = parseParams(body);
                String agentId = params.get("agentId");
                String verifyCode = params.get("verifyCode");
                String reverseTunnelEnabled = params.get("reverseTunnelEnabled");
                boolean rte = "true".equalsIgnoreCase(reverseTunnelEnabled);

                if (!authManager.verifyAgent(agentId, verifyCode)) {
                    String json = "{\"success\":false,\"message\":\"验证码校验失败\"}";
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(401, json.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(json.getBytes(StandardCharsets.UTF_8));
                    }
                    return;
                }

                var agentInfo = sessionManager.getAgent(agentId);
                if (agentInfo == null) {
                    String json = "{\"success\":false,\"message\":\"被控端未注册\"}";
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(404, json.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(json.getBytes(StandardCharsets.UTF_8));
                    }
                    return;
                }

                String json = String.format("{\"success\":true,\"agentType\":\"%s\",\"desktopSupported\":%s}",
                        agentInfo.getAgentType(), agentInfo.getDesktopSupported());
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, json.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                log.info("HTTP验证通过: agentId={}, reverseTunnelEnabled={}", agentId, rte);
            } catch (Exception e) {
                String json = "{\"success\":false,\"message\":\"验证异常:" + e.getMessage() + "\"}";
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, json.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
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
    }

    public void start() {
        server.start();
        httpServer.start();
        log.info("远控网关已启动 (WS:{}, HTTP验证:{})", server.getSetting().getPort(), httpPort);
    }

    public void stop() {
        server.stop();
        httpServer.stop(0);
        log.info("远控网关已停止");
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

    /**
     * 部署启动入口。
     *
     * @param args [0]=监听端口（默认 9000）
     */
    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9000;
        ServerSetting setting = ServerSetting.builder().port(port).build();
        GatewayServer server = new GatewayServer(setting);
        server.start();
        // 保持 JVM 存活（传输层为 NIO Reactor 异步线程——主线程须阻塞，否则 main 返回即退出）
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
