package com.chua.remote.controller;

import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.core.transport.RemoteTransport;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.ext.web.Router;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class WebControllerServer {

    private final int port;
    private final String gatewayHttpUrl;
    private final Vertx vertx;
    private HttpServer httpServer;
    private final Map<String, GatewayBridge> bridges = new ConcurrentHashMap<>();

    public WebControllerServer(int port, String gatewayHttpUrl) {
        this.port = port;
        this.gatewayHttpUrl = gatewayHttpUrl;
        this.vertx = Vertx.vertx();
    }

    public void start() {
        Router router = Router.router(vertx);
        router.route("/api/verify").handler(ctx -> {
            ctx.request().bodyHandler(buffer -> proxyVerify(ctx, buffer.toString()));
        });

        httpServer = vertx.createHttpServer();
        httpServer.webSocketHandler(this::handleWebSocket);
        httpServer.requestHandler(router);
        httpServer.listen(port, "0.0.0.0", ar -> {
            if (ar.succeeded()) {
                log.info("Web控制端已启动: port={}, gatewayHttp={}", port, gatewayHttpUrl);
            } else {
                log.error("Web控制端启动失败", ar.cause());
            }
        });
    }

    public void stop() {
        bridges.values().forEach(GatewayBridge::close);
        bridges.clear();
        if (httpServer != null) httpServer.close();
        vertx.close();
    }

    private void handleWebSocket(ServerWebSocket ws) {
        String bridgeId = ws.textHandlerID();
        log.info("浏览器WebSocket已连接: id={}", bridgeId);
        GatewayBridge bridge = new GatewayBridge(bridgeId, ws);
        bridges.put(bridgeId, bridge);
        ws.binaryMessageHandler(buffer -> bridge.onBrowserBinary(buffer));
        ws.textMessageHandler(text -> bridge.onBrowserText(text));
        ws.closeHandler(v -> { bridge.close(); bridges.remove(bridgeId); });
        ws.exceptionHandler(e -> log.warn("浏览器WS异常: id={}", bridgeId, e));
    }

    private void proxyVerify(io.vertx.ext.web.RoutingContext ctx, String body) {
        vertx.executeBlocking(promise -> {
            try {
                var client = java.net.http.HttpClient.newHttpClient();
                var request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(gatewayHttpUrl + "/verify"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                        .build();
                var resp = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                promise.complete(resp);
            } catch (Exception e) { promise.fail(e); }
        }, ar -> {
            if (ar.succeeded()) {
                var resp = (java.net.http.HttpResponse<String>) ar.result();
                ctx.response().putHeader("Content-Type", "application/json").end(resp.body());
            } else {
                ctx.response().setStatusCode(500)
                        .end("{\"success\":false,\"message\":\"" + ar.cause().getMessage() + "\"}");
            }
        });
    }

    private static class GatewayBridge {
        private final String id;
        private final ServerWebSocket browserWs;
        private RemoteTransport gatewayTransport;

        GatewayBridge(String id, ServerWebSocket browserWs) {
            this.id = id;
            this.browserWs = browserWs;
        }

        void connectToGateway(String clientId, String serverUrl) {
            if (gatewayTransport != null) return;
            this.gatewayTransport = new RemoteTransport(clientId, serverUrl);
            gatewayTransport.on(MessageType.DATA, this::onGatewayData);
            gatewayTransport.on(MessageType.SSH, this::onGatewaySSH);
            gatewayTransport.on(MessageType.SIGNAL, this::onGatewaySignal);
            gatewayTransport.connect();
            log.info("已连接到网关: bridgeId={}, clientId={}", id, clientId);
        }

        void onBrowserBinary(io.vertx.core.buffer.Buffer buffer) {
            if (gatewayTransport == null) return;
            Frame frame = FrameCodec.decode(buffer.getBytes());
            if (frame != null) gatewayTransport.send(frame);
        }

        void onBrowserText(String text) {
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> msg = mapper.readValue(text, Map.class);
                String action = (String) msg.get("action");
                if ("connect".equals(action)) {
                    connectToGateway((String) msg.get("clientId"),
                            (String) msg.getOrDefault("serverUrl", "ws://localhost:9000"));
                } else if ("ssh".equals(action)) {
                    forwardSSH(msg);
                } else if ("ctrl".equals(action)) {
                    forwardCtrl(msg);
                }
            } catch (Exception e) { log.debug("文本消息解析失败: {}", e.getMessage()); }
        }

        void onGatewayData(Frame frame) {
            browserWs.writeBinaryMessage(io.vertx.core.buffer.Buffer.buffer(FrameCodec.encode(frame)));
        }

        void onGatewaySSH(Frame frame) {
            try {
                var meta = frame.getMetadata() != null ? frame.getMetadata() : Map.<String,String>of();
                Map<String, Object> msg = Map.of("type", "ssh", "sshAction", meta.getOrDefault("sshAction", "output"),
                        "sshSessionId", meta.getOrDefault("sshSessionId", ""), "stream", meta.getOrDefault("stream", "stdout"),
                        "data", frame.getPayload() != null ? new String(frame.getPayload(), StandardCharsets.UTF_8) : "");
                browserWs.writeTextMessage(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(msg));
            } catch (Exception e) { log.debug("SSH帧转JSON失败", e); }
        }

        void onGatewaySignal(Frame frame) {
            try {
                var meta = frame.getMetadata() != null ? frame.getMetadata() : Map.<String,String>of();
                Map<String, Object> msg = Map.of("type", "signal", "kind", meta.getOrDefault("kind", ""),
                        "sessionId", frame.getSessionId() != null ? frame.getSessionId() : "");
                browserWs.writeTextMessage(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(msg));
            } catch (Exception e) { log.debug("信令帧转JSON失败", e); }
        }

        void forwardSSH(Map<String, Object> msg) {
            if (gatewayTransport == null) return;
            String sessionId = (String) msg.getOrDefault("sessionId", id);
            Map<String, String> meta = new ConcurrentHashMap<>();
            meta.put("sshAction", (String) msg.getOrDefault("sshAction", "start"));
            meta.put("sshSessionId", sessionId);
            msg.forEach((k, v) -> { if (v instanceof String s) meta.put(k, s); });
            byte[] payload = msg.containsKey("data") ? Base64.getDecoder().decode((String) msg.get("data")) : new byte[0];
            gatewayTransport.send(FrameCodec.sshFrame(sessionId, payload, meta));
        }

        void forwardCtrl(Map<String, Object> msg) {
            if (gatewayTransport == null) return;
            String sessionId = (String) msg.getOrDefault("sessionId", id);
            byte[] payload = msg.containsKey("data") ? Base64.getDecoder().decode((String) msg.get("data")) : new byte[0];
            Map<String, String> meta = new ConcurrentHashMap<>();
            msg.forEach((k, v) -> { if (v instanceof String s && !"action".equals(k) && !"data".equals(k)) meta.put(k, s); });
            gatewayTransport.send(Frame.builder().type(MessageType.CTRL).sessionId(sessionId).payload(payload).metadata(meta).build());
        }

        void close() {
            if (gatewayTransport != null) gatewayTransport.disconnect();
        }
    }

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        String gatewayHttp = args.length > 1 ? args[1] : "http://localhost:9001";
        new WebControllerServer(port, gatewayHttp).start();
    }
}
