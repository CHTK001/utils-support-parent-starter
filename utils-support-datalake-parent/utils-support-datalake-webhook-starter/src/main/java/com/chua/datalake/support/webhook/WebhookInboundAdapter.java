package com.chua.datalake.support.webhook;

import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.spi.pipeline.PipelineEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Webhook 入站适配器：HTTP POST → JSON → 数据envelope → pipelineengine。
 *
 * <p>暴露 HTTP 端点接收外部系统推送的 JSON 数据，自动解析并交给 PipelineEngine 处理。</p>
 *
 * <p>端点：</p>
 * <ul>
 *   <li>{@code POST /api/datalake/webhook/{pipelineId}} — 推送单条数据</li>
 *   <li>{@code POST /api/datalake/webhook/{pipelineId}/batch} — 推送批量数据</li>
 *   <li>{@code GET /api/datalake/webhook/health} — 健康检查</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class WebhookInboundAdapter {

    /**
     * JDK HTTP 服务器
    */
    private volatile HttpServer server;

    /**
     * 端口（启动后填充，端口=0 时为系统分配的 ephemeral 端口）
    */
    private int actualPort;

    /**
     * 端口
    */
    private final int port;

    /**
     * 管线引擎
    */
    private final PipelineEngine pipelineEngine;

    /**
     * 消息计数器
    */
    private final AtomicLong messageCount = new AtomicLong(0);

    /**
     * JSON 解析器
    */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 运行状态
    */
    private volatile boolean running = false;

    /**
     * webhookinbound适配器。
     * @param builder 构建器
     */
    private WebhookInboundAdapter(Builder builder) {
        this.port = builder.port;
        this.pipelineEngine = builder.pipelineEngine;
    }

    /**
     * 创建构建器。
     *
     * @return 新构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 启动 Webhook 服务。
     */
    public void start() {
        if (running) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            this.actualPort = server.getAddress().getPort();

 // POST /api/数据湖/webhook/{pipelineid} — 单条推送
            server.createContext("/api/datalake/webhook/", this::handleRequest);

            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
            server.start();
            running = true;
            log.info("[datalake-webhook] 启动成功: port={}", port);
        } catch (Exception e) {
            log.error("[datalake-webhook] 启动失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 停止 Webhook 服务。
     */
    public void stop() {
        if (!running || server == null) {
            return;
        }
        server.stop(0);
        running = false;
        log.info("[datalake-webhook] 已停止, 共处理 {} 条消息", messageCount.get());
    }

    /**
     * 处理 HTTP 请求。
     *
     * @param exchange 交换对象
     */
    @SuppressWarnings("unchecked")
    private void handleRequest(HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            // 健康检查
            if (path.equals("/api/datalake/webhook/health") && "GET".equalsIgnoreCase(method)) {
                String json = objectMapper.writeValueAsString(Map.of("status", "ok", "count", messageCount.get()));
                sendResponse(exchange, 200, json);
                return;
            }

            // 非 POST 方法拒绝
            if (!"POST".equalsIgnoreCase(method)) {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }

 // 解析 pipelineid: /api/数据湖/webhook/{pipelineid} 或 /api/数据湖/webhook/{pipelineid}/批量
            String prefix = "/api/datalake/webhook/";
            String remaining = path.substring(prefix.length());
            boolean isBatch = remaining.endsWith("/batch");
            String pipelineId = isBatch ? remaining.replace("/batch", "") : remaining;

            if (pipelineId.isEmpty()) {
                sendResponse(exchange, 400, "{\"error\":\"Missing pipelineId\"}");
                return;
            }

 // 读取 主体
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            if (isBatch) {
                // 批量推送
                java.util.List<Map<String, Object>> rows = objectMapper.readValue(body, java.util.List.class);
                long count = 0;
                for (Map<String, Object> row : rows) {
                    DataEnvelope envelope = DataEnvelope.builder()
                            .parsed(row)
                            .pipelineId(pipelineId)
                            .traceId("webhook-" + messageCount.incrementAndGet())
                            .timestamp(System.currentTimeMillis())
                            .build();
                    pipelineEngine.execute(pipelineId, envelope);
                    count++;
                }
                sendResponse(exchange, 200, objectMapper.writeValueAsString(Map.of("ok", true, "count", count, "total", messageCount.get())));
            } else {
                // 单条推送
                Map<String, Object> data = objectMapper.readValue(body, Map.class);
                DataEnvelope envelope = DataEnvelope.builder()
                        .parsed(data)
                        .pipelineId(pipelineId)
                        .traceId("webhook-" + messageCount.incrementAndGet())
                        .timestamp(System.currentTimeMillis())
                        .build();
                pipelineEngine.execute(pipelineId, envelope);
                sendResponse(exchange, 200, objectMapper.writeValueAsString(Map.of("ok", true, "count", messageCount.get())));
            }
        } catch (Exception e) {
            log.error("[datalake-webhook] 请求处理失败: {}", e.getMessage(), e);
            try {
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            } catch (Exception ex) {
                // ignore
            }
        }
    }

    /**
     * 发送 HTTP 响应。
     *
     * @param exchange 交换对象
     * @param status   状态码
     * @param body     响应体
     */
    private void sendResponse(HttpExchange exchange, int status, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * 返回实际监听的端口（启动后有效，端口=0 时为系统分配端口）。
     *
     * @return 实际端口号
     */
    public int getServerPort() {
        return actualPort;
    }

    /**
     * 返回已处理消息数。
     *
     * @return 消息计数
     */
    public long getMessageCount() {
        return messageCount.get();
    }

    /**
     * 是否运行中。
     *
     * @return true 表示已启动
     */
    public boolean isRunning() {
        return running;
    }

 // ━━━━━━━━━━━━━━ 构建器 ━━━━━━━━━━━━━━

    /**
     * Webhook 入站适配器构建器。
     * @author CH
     * @since 4.0.0
     */
    public static class Builder {
        private int port = 8700; // 端口
        private PipelineEngine pipelineEngine; // pipelineengine

        /**
         * 端口。
         * @param port 端口
         * @return 端口的结果
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }
        /**
         * pipelineengine。
         * @param engine engine
         * @return pipelineEngine的结果
         */
        public Builder pipelineEngine(PipelineEngine engine) {
            this.pipelineEngine = engine;
            return this;
        }

        /**
         * 构建。
         * @return 构建的结果
         */
        public WebhookInboundAdapter build() {
            if (pipelineEngine == null) {
                throw new IllegalArgumentException("pipelineEngine 不能为空");
            }
            return new WebhookInboundAdapter(this);
        }
    }
}
