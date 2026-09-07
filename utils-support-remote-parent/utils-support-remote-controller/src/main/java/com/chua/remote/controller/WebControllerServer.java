package com.chua.remote.controller;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.impl.JdkHttpServer;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Map;

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
                    delegate.createContext("/", WebControllerServer.this::handleRequest);
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

    private void handleRequest(com.sun.net.httpserver.HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        if ("/".equals(path) || "/index.html".equals(path)) {
            serveIndex(exchange);
        } else if ("/verify".equals(path) && "POST".equals(method)) {
            proxyVerify(exchange);
        } else {
            sendJson(exchange, 404, "{\"error\":\"not found\"}");
        }
    }

    private void serveIndex(com.sun.net.httpserver.HttpExchange exchange) {
        String html = """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                    <meta charset="UTF-8">
                    <title>远控控制端</title>
                    <style>
                        body{font-family:sans-serif;max-width:600px;margin:40px auto;padding:0 20px}
                        .form-group{margin:12px 0}
                        label{display:block;margin-bottom:4px;font-weight:bold}
                        input[type=text],input[type=password],select{width:100%;padding:8px;box-sizing:border-box}
                        button{padding:10px 24px;background:#1a73e8;color:#fff;border:none;cursor:pointer;border-radius:4px}
                        button:hover{background:#1557b0}
                        #result{margin-top:16px;padding:12px;border-radius:4px;display:none}
                        .success{background:#d4edda;color:#155724;border:1px solid #c3e6cb}
                        .error{background:#f8d7da;color:#721c24;border:1px solid #f5c6cb}
                    </style>
                </head>
                <body>
                    <h2>远控控制端</h2>
                    <form id="connForm">
                        <div class="form-group">
                            <label>被控端ID</label>
                            <input type="text" id="agentId" required>
                        </div>
                        <div class="form-group">
                            <label>验证码</label>
                            <input type="text" id="verifyCode" required>
                        </div>
                        <div class="form-group">
                            <label>连接方式</label>
                            <select id="connectionMethod">
                                <option value="ssh">SSH (WebSocket)</option>
                                <option value="rdp">远程桌面 (WebRTC)</option>
                            </select>
                        </div>
                        <div class="form-group">
                            <label><input type="checkbox" id="reverseTunnel"> 开启反向隧道</label>
                        </div>
                        <button type="submit">连接</button>
                    </form>
                    <div id="result"></div>
                    <script>
                    document.getElementById('connForm').addEventListener('submit', async function(e) {
                        e.preventDefault();
                        const result = document.getElementById('result');
                        const agentId = document.getElementById('agentId').value;
                        const verifyCode = document.getElementById('verifyCode').value;
                        const reverseTunnel = document.getElementById('reverseTunnel').checked;
                        const connMethod = document.getElementById('connectionMethod').value;
                        try {
                            const resp = await fetch('/verify', {
                                method: 'POST',
                                headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                                body: 'agentId=' + encodeURIComponent(agentId)
                                    + '&verifyCode=' + encodeURIComponent(verifyCode)
                                    + '&reverseTunnelEnabled=' + reverseTunnel
                            });
                            const data = await resp.json();
                            result.style.display = 'block';
                            if (data.success) {
                                result.className = 'success';
                                result.textContent = '验证通过! Agent类型:' + data.agentType
                                    + ', 连接方式:' + connMethod;
                            } else {
                                result.className = 'error';
                                result.textContent = '验证失败: ' + data.message;
                            }
                        } catch(ex) {
                            result.style.display = 'block';
                            result.className = 'error';
                            result.textContent = '请求异常: ' + ex.message;
                        }
                    });
                    </script>
                </body>
                </html>
                """;
        try {
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception e) {
            log.warn("serveIndex失败", e);
        }
    }

    private void proxyVerify(com.sun.net.httpserver.HttpExchange exchange) {
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
            sendJson(exchange, 500, "{\"success\":false,\"message\":\"代理验证异常:" + e.getMessage() + "\"}");
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

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        String gatewayHttp = args.length > 1 ? args[1] : "http://localhost:9001";
        WebControllerServer server = new WebControllerServer(port, gatewayHttp);
        server.start();
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
