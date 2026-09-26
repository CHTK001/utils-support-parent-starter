package com.chua.datalake.support.webhook;

import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.model.PipelineState;
import com.chua.datalake.support.spi.pipeline.PipelineEngine;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

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
 * <p>应答口径：HTTP 状态码反映管线真实结果——{@code SINK_OK} 才是 200，管线未注册或无
 * default 阶段返回 404，落盘失败返回 500，入参不合法返回 4xx。推送方据此重试，
 * 不能靠恒真的 {@code ok=true} 判断。</p>
 *
 * <p>暴露面：默认只绑定 {@code 127.0.0.1}。改用 {@code 0.0.0.0} 对外提供服务时，
 * 必须同时配置 {@code token(...)}，否则任何能连到端口的进程都能向管线注入数据。</p>
 *
 * <p>限制：请求体即业务数据，没有 topic 通道，因此声明了 topics 的订阅器不会命中
 * Webhook 推送的数据；需要按 topic 路由时应改用管线内的其他 Sink。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class WebhookInboundAdapter {

    /**
     * 路由前缀
     */
    private static final String CONTEXT_PREFIX = "/api/datalake/webhook/";

    /**
     * 健康检查路径
     */
    private static final String HEALTH_PATH = "/api/datalake/webhook/health";

    /**
     * 批量推送后缀
     */
    private static final String BATCH_SUFFIX = "/batch";

    /**
     * 令牌请求头
     */
    private static final String TOKEN_HEADER = "x-webhook-token";

    /**
     * 管线标识合法字符：首字符为字母或数字，总长 1-64
     */
    private static final Pattern PIPELINE_ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.\\-]{0,63}");

    /**
     * 请求体字节上限默认值
     */
    private static final int DEFAULT_MAX_BODY_BYTES = 1024 * 1024;

    /**
     * 工作线程数
     */
    private static final int WORKER_THREADS = 4;

    /**
     * JDK HTTP 服务器
     */
    private volatile HttpServer server;

    /**
     * 请求处理线程池，停止时必须关闭
     */
    private volatile ExecutorService workerPool;

    /**
     * 端口（启动后填充，端口=0 时为系统分配的 ephemeral 端口）
     */
    private volatile int actualPort;

    /**
     * 绑定地址
     */
    private final String host;

    /**
     * 端口
     */
    private final int port;

    /**
     * 校验令牌，null 表示不校验
     */
    private final String token;

    /**
     * 请求体字节上限
     */
    private final int maxBodyBytes;

    /**
     * 管线引擎
     */
    private final PipelineEngine pipelineEngine;

    /**
     * 接收计数器
     */
    private final AtomicLong receivedCount = new AtomicLong(0);

    /**
     * 未被管线消化的计数器
     */
    private final AtomicLong failedCount = new AtomicLong(0);

    /**
     * JSON 解析器
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 业务数据行类型：Map&lt;String, Object&gt;
     */
    private final JavaType rowType = objectMapper.getTypeFactory()
            .constructMapType(LinkedHashMap.class, String.class, Object.class);

    /**
     * 运行状态
     */
    private volatile boolean running = false;

    /**
     * webhookinbound适配器。
     *
     * @param builder 构建器
     */
    private WebhookInboundAdapter(Builder builder) {
        this.host = builder.host;
        this.port = builder.port;
        this.token = builder.token;
        this.maxBodyBytes = builder.maxBodyBytes;
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
     *
     * <p>启动失败直接抛出，调用方不会误以为端口已在监听。</p>
     */
    public synchronized void start() {
        if (running) {
            return;
        }
        HttpServer created = null;
        ExecutorService pool = null;
        try {
            created = HttpServer.create(new InetSocketAddress(InetAddress.getByName(host), port), 0);
            pool = Executors.newFixedThreadPool(WORKER_THREADS);
            created.createContext(CONTEXT_PREFIX, this::handleRequest);
            created.setExecutor(pool);
            created.start();
            this.server = created;
            this.workerPool = pool;
            this.actualPort = created.getAddress().getPort();
            running = true;
            log.info("[datalake-webhook] 启动成功: host={}, port={}", host, actualPort);
        } catch (Exception e) {
            if (created != null) {
                created.stop(0);
            }
            if (pool != null) {
                pool.shutdownNow();
            }
            throw new IllegalStateException("[datalake-webhook] 启动失败: host=" + host
                    + ", port=" + port + ", cause=" + e.getMessage(), e);
        }
    }

    /**
     * 停止 Webhook 服务，并释放监听端口与工作线程。
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        HttpServer current = this.server;
        ExecutorService pool = this.workerPool;
        if (current != null) {
            current.stop(0);
        }
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        this.server = null;
        this.workerPool = null;
        running = false;
        log.info("[datalake-webhook] 已停止, 共接收 {} 条, 未消化 {} 条", receivedCount.get(), failedCount.get());
    }

    /**
     * 处理 HTTP 请求：所有分支只写一次响应，异常统一转成转义后的 JSON 错误体。
     *
     * @param exchange 交换对象
     */
    private void handleRequest(HttpExchange exchange) {
        try {
            route(exchange);
        } catch (WebhookRejectException e) {
            sendJson(exchange, e.getStatus(), WebhookReply.builder()
                    .ok(false)
                    .status(e.getStatusName())
                    .error(e.getMessage())
                    .build());
        } catch (Exception e) {
            log.error("[datalake-webhook] 请求处理失败: {}", e.getMessage(), e);
            sendJson(exchange, 500, WebhookReply.builder()
                    .ok(false)
                    .status("internal_error")
                    .error(describe(e))
                    .build());
        }
    }

    /**
     * 路由分发。
     *
     * @param exchange 交换对象
     * @throws IOException            读写失败
     * @throws WebhookRejectException 入参不合法
     */
    private void route(HttpExchange exchange) throws IOException, WebhookRejectException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        // 健康检查只读，不校验令牌
        if (HEALTH_PATH.equals(path)) {
            if (!"GET".equalsIgnoreCase(method)) {
                throw new WebhookRejectException(405, "method_not_allowed", "健康检查只支持 GET");
            }
            sendJson(exchange, 200, WebhookReply.builder()
                    .ok(true)
                    .status("ok")
                    .receivedTotal(receivedCount.get())
                    .failedTotal(failedCount.get())
                    .build());
            return;
        }

        if (!"POST".equalsIgnoreCase(method)) {
            throw new WebhookRejectException(405, "method_not_allowed", "推送只支持 POST");
        }
        verifyToken(exchange);

        String remaining = path.substring(CONTEXT_PREFIX.length());
        boolean batch = remaining.endsWith(BATCH_SUFFIX);
        String pipelineId = batch ? remaining.substring(0, remaining.length() - BATCH_SUFFIX.length()) : remaining;
        if (!PIPELINE_ID_PATTERN.matcher(pipelineId).matches()) {
            throw new WebhookRejectException(400, "invalid_pipeline_id", "pipelineId 非法或缺失: " + pipelineId);
        }

        String body = readBody(exchange);
        dispatch(exchange, pipelineId, readRows(body, batch));
    }

    /**
     * 逐条交给管线，并按真实结果决定状态码。
     *
     * @param exchange   交换对象
     * @param pipelineId 管线标识
     * @param rows       待处理数据
     */
    private void dispatch(HttpExchange exchange, String pipelineId, List<Map<String, Object>> rows) {
        int failed = 0;
        String lastStatus = null;
        String lastState = null;
        for (Map<String, Object> row : rows) {
            PipelineState state = executeOne(pipelineId, row);
            lastState = state == null ? null : state.name();
            if (state == PipelineState.SINK_OK) {
                lastStatus = "accepted";
            } else if (state == null) {
                lastStatus = "pipeline_not_found";
                failed++;
            } else {
                lastStatus = "sink_failed";
                failed++;
            }
        }
        long totalFailed = failedCount.addAndGet(failed);
        int status = failed == 0 ? 200 : ("pipeline_not_found".equals(lastStatus) ? 404 : 500);
        log.info("[datalake-webhook] 推送完成: pipeline={}, received={}, failed={}, http={}",
                pipelineId, rows.size(), failed, status);
        sendJson(exchange, status, WebhookReply.builder()
                .ok(failed == 0)
                .status(failed == 0 ? "accepted" : lastStatus)
                .state(lastState)
                .received(rows.size())
                .failed(failed)
                .receivedTotal(receivedCount.get())
                .failedTotal(totalFailed)
                .error(failed == 0 ? null
                        : "管线未消化 " + failed + "/" + rows.size() + " 条数据, 最后状态=" + lastStatus)
                .build());
    }

    /**
     * 提交一条数据进管线。
     *
     * @param pipelineId 管线标识
     * @param data       业务数据
     * @return 管线终态，null 表示数据没有进入任何 Sink
     */
    private PipelineState executeOne(String pipelineId, Map<String, Object> data) {
        DataEnvelope envelope = DataEnvelope.builder()
                .parsed(data)
                .pipelineId(pipelineId)
                .traceId("webhook-" + receivedCount.incrementAndGet())
                .timestamp(System.currentTimeMillis())
                .build();
        try {
            pipelineEngine.execute(pipelineId, envelope);
        } catch (Exception e) {
            log.error("[datalake-webhook] 管线执行异常: pipeline={}, error={}", pipelineId, e.getMessage(), e);
            return PipelineState.SINK_FAIL;
        }
        return envelope.getState();
    }

    /**
     * 解析请求体为数据行。
     *
     * <p>先整体校验形态再返回，避免批量请求出现"前几条已投递、后面才报格式错误"的半提交。</p>
     *
     * @param body  请求体
     * @param batch 是否批量
     * @return 数据行列表
     * @throws WebhookRejectException 形态不合法
     */
    private List<Map<String, Object>> readRows(String body, boolean batch) throws WebhookRejectException {
        if (body.isEmpty()) {
            throw new WebhookRejectException(400, "empty_body", "请求体为空");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new WebhookRejectException(400, "invalid_json", "请求体不是合法 JSON: " + describe(e));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        if (batch) {
            if (root == null || !root.isArray()) {
                throw new WebhookRejectException(400, "invalid_payload", "批量推送的请求体必须是 JSON 数组");
            }
            int index = 0;
            for (JsonNode node : root) {
                if (!node.isObject()) {
                    throw new WebhookRejectException(400, "invalid_payload",
                            "批量第 " + index + " 个元素不是 JSON 对象");
                }
                rows.add(toRow(node));
                index++;
            }
            return rows;
        }
        if (root == null || !root.isObject()) {
            throw new WebhookRejectException(400, "invalid_payload", "单条推送的请求体必须是 JSON 对象");
        }
        rows.add(toRow(root));
        return rows;
    }

    /**
     * JSON 对象节点转业务数据行。
     *
     * @param node JSON 对象节点
     * @return 业务数据
     */
    private Map<String, Object> toRow(JsonNode node) {
        return objectMapper.convertValue(node, rowType);
    }

    /**
     * 校验令牌，未配置令牌时直接放行。
     *
     * @param exchange 交换对象
     * @throws WebhookRejectException 令牌缺失或不匹配
     */
    private void verifyToken(HttpExchange exchange) throws WebhookRejectException {
        if (token == null) {
            return;
        }
        String given = exchange.getRequestHeaders().getFirst(TOKEN_HEADER);
        boolean matched = given != null && MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
        if (!matched) {
            throw new WebhookRejectException(401, "unauthorized", "缺少或错误的 " + TOKEN_HEADER + " 请求头");
        }
    }

    /**
     * 读取请求体，超过上限立即拒绝。
     *
     * @param exchange 交换对象
     * @return 请求体文本
     * @throws IOException            读取失败
     * @throws WebhookRejectException 超过大小上限
     */
    private String readBody(HttpExchange exchange) throws IOException, WebhookRejectException {
        try (InputStream in = exchange.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > maxBodyBytes) {
                    throw new WebhookRejectException(413, "payload_too_large",
                            "请求体超过上限 " + maxBodyBytes + " 字节");
                }
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 发送 JSON 响应。
     *
     * @param exchange 交换对象
     * @param status   状态码
     * @param reply    应答体
     */
    private void sendJson(HttpExchange exchange, int status, WebhookReply reply) {
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsString(reply).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            bytes = "{\"ok\":false,\"status\":\"serialize_error\"}".getBytes(StandardCharsets.UTF_8);
        }
        try {
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            log.warn("[datalake-webhook] 响应写出失败: {}", e.getMessage());
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
     * 返回已接收消息数。
     *
     * @return 消息计数
     */
    public long getMessageCount() {
        return receivedCount.get();
    }

    /**
     * 返回未被管线消化的消息数。
     *
     * @return 失败计数
     */
    public long getFailedCount() {
        return failedCount.get();
    }

    /**
     * 是否运行中。
     *
     * @return true 表示已启动
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * 异常摘要，避免 null 文案。
     *
     * @param e 异常
     * @return 摘要
     */
    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    // ━━━━━━━━━━━━━━ 拒绝异常 ━━━━━━━━━━━━━━

    /**
     * 入站校验失败：携带 HTTP 状态码与结果分类。
     *
     * @author CH
     * @since 4.0.0.42
     */
    private static final class WebhookRejectException extends Exception {

        /**
         * 序列化标识
         */
        private static final long serialVersionUID = 1L;

        /**
         * HTTP 状态码
         */
        private final int status;

        /**
         * 结果分类
         */
        private final String statusName;

        /**
         * 构造拒绝异常。
         *
         * @param status     HTTP 状态码
         * @param statusName 结果分类
         * @param message    说明
         */
        private WebhookRejectException(int status, String statusName, String message) {
            super(message);
            this.status = status;
            this.statusName = statusName;
        }

        /**
         * 获取 HTTP 状态码。
         *
         * @return 状态码
         */
        private int getStatus() {
            return status;
        }

        /**
         * 获取结果分类。
         *
         * @return 分类
         */
        private String getStatusName() {
            return statusName;
        }
    }

    // ━━━━━━━━━━━━━━ 构建器 ━━━━━━━━━━━━━━

    /**
     * Webhook 入站适配器构建器。
     *
     * @author CH
     * @since 4.0.0
     */
    public static class Builder {

        /**
         * 绑定地址，默认只监听回环
         */
        private String host = "127.0.0.1";

        /**
         * 端口
         */
        private int port = 8700;

        /**
         * 校验令牌
         */
        private String token;

        /**
         * 请求体字节上限
         */
        private int maxBodyBytes = DEFAULT_MAX_BODY_BYTES;

        /**
         * pipelineengine
         */
        private PipelineEngine pipelineEngine;

        /**
         * 绑定地址。对外暴露时须同时设置 token。
         *
         * @param host 绑定地址
         * @return 构建器
         */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /**
         * 端口。
         *
         * @param port 端口
         * @return 构建器
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * 校验令牌：非空时推送必须携带 {@code x-webhook-token} 请求头。
         *
         * @param token 令牌
         * @return 构建器
         */
        public Builder token(String token) {
            this.token = token;
            return this;
        }

        /**
         * 请求体字节上限。
         *
         * @param maxBodyBytes 上限
         * @return 构建器
         */
        public Builder maxBodyBytes(int maxBodyBytes) {
            this.maxBodyBytes = maxBodyBytes;
            return this;
        }

        /**
         * pipelineengine。
         *
         * @param engine engine
         * @return 构建器
         */
        public Builder pipelineEngine(PipelineEngine engine) {
            this.pipelineEngine = engine;
            return this;
        }

        /**
         * 构建。
         *
         * @return 构建的结果
         */
        public WebhookInboundAdapter build() {
            if (pipelineEngine == null) {
                throw new IllegalArgumentException("pipelineEngine 不能为空");
            }
            if (maxBodyBytes <= 0) {
                throw new IllegalArgumentException("maxBodyBytes 必须为正数");
            }
            return new WebhookInboundAdapter(this);
        }
    }
}
