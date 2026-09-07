package com.chua.remote.controller;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.impl.JdkHttpServer;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

@Slf4j
public class WebControllerServer {

    private final JdkHttpServer httpServer;
    private final int port;
    private final String gatewayHttpUrl;

    public WebControllerServer(int port, String gatewayHttpUrl) {
        this.port = port;
        this.gatewayHttpUrl = gatewayHttpUrl;
        ServerSetting setting = ServerSetting.builder()
                .port(port)
                .contextPath("/")
                .build();
        this.httpServer = new JdkHttpServer(setting) {
            @Override
            protected void doStart() {
                try {
                    com.sun.net.httpserver.HttpServer delegate =
                            com.sun.net.httpserver.HttpServer.create(
                                    new java.net.InetSocketAddress(port), 0);
                    delegate.createContext("/api/verify", WebControllerServer.this::handleVerify);
                    delegate.createContext("/", WebControllerServer.this::handleStatic);
                    delegate.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
                    delegate.start();
                    log.info("Web控制端已启动 on port:{}", port);
                } catch (Exception e) {
                    throw new RuntimeException("Web控制端启动失败: port=" + port, e);
                }
            }

            @Override
            protected void doStop() {
                log.info("Web控制端已停止");
            }
        };
    }

    public void start() {
        httpServer.start();
    }

    public void stop() {
        httpServer.stop();
    }

    private void handleVerify(com.sun.net.httpserver.HttpExchange exchange) {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(gatewayHttpUrl + "/verify"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();
            java.net.http.HttpResponse<String> resp = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            sendJson(exchange, resp.statusCode(), resp.body());
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"success\":false,\"message\":\"" + e.getMessage() + "\"}");
        }
    }

    private void handleStatic(com.sun.net.httpserver.HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        if ("/".equals(path)) path = "/index.html";
        try {
            byte[] bytes = ("静态文件: " + path + " - 请从 vue-support-parent-starter 构建部署").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) { os.write(bytes); }
        } catch (Exception e) {
            log.warn("静态文件处理失败: {}", path, e);
        }
    }

    private void sendJson(com.sun.net.httpserver.HttpExchange exchange, int code, String json) {
        try {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            try (var os = exchange.getResponseBody()) { os.write(bytes); }
        } catch (Exception e) {
            log.warn("发送HTTP响应失败", e);
        }
    }

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        String gatewayHttp = args.length > 1 ? args[1] : "http://localhost:9001";
        new WebControllerServer(port, gatewayHttp).start();
        try { Thread.currentThread().join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
