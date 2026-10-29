package com.chua.openai.support;

import com.chua.common.support.ai.chat.DecisionsClient;
import com.chua.common.support.ai.chat.DecisionsProtocol;
import com.chua.common.support.ai.chat.DecisionsRequest;
import com.chua.common.support.ai.chat.DecisionsResult;
import com.chua.common.support.ai.chat.DecisionsSetting;
import com.chua.common.support.ai.chat.DecisionAnswer;
import com.chua.common.support.ai.chat.DecisionQuestion;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import lombok.extern.slf4j.Slf4j;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 用本机 HttpServer 模拟 OpenAI /v1/decisions + SystemOne /v1/systemone 响应：
 * 断言发送给对方的请求体（model/input/questions/state）以及
 * 从标准 OpenAI 格式返回的 DecisionsResult。
 */
@Slf4j
class OpenAiDecisionsClientTest {

    /** 本地 Mock 端点 */
    private static final int PORT = 18923;

    /** 真实 API Key（书生·端砚 Intern InkStone，上海 AI 实验室） */
    private static final String API_KEY = "sk-test-placeholder-0000000000000000";

    /** 模拟地址 */
    private static final String BASE_URL = "http://127.0.0.1:" + PORT + "/v1";

    /**
     * 入站请求日志。
     *
     * <p>注意：{@code headers} 在无 API Key 时为 null（ ConcurrentHashMap 不允许 null 值），
     * 所以使用 LinkedHashMap。
     */
    private final Map<String, Object> received = new LinkedHashMap<>();

    /** 是否收到过请求 */
    private final AtomicBoolean hit = new AtomicBoolean(false);

    /** 是否产生错误响应（400） */
    private final AtomicBoolean error400 = new AtomicBoolean(false);

    private HttpServer server;

    /**
     * 标准答案：NOUL / CHOICE / SCORE。
     *
     * <p>CHOICE 携带 choice 字段，SCORE 携带 score 数值，答案格式与标准
     * 输出文档中 {@code DecisionsResult} 的解析一致。
     */
    private static List<Map<String, Object>> standardAnswers() {
        List<Map<String, Object>> answers = new ArrayList<>(3);
        answers.add(Map.of("name", "hasDuplicate", "type", "noul", "label", "false"));
        answers.add(Map.of("name", "department", "type", "choice", "label", "billing", "choice", "billing"));
        answers.add(Map.of("name", "amount", "type", "score", "label", "3", "score", 3.2));
        return answers;
    }

    /**
     * SystemOne 协议的答案：每个请求只返回 1 个 NOUL 答案。
     */
    private static List<Map<String, Object>> systemOneAnswers() {
        List<Map<String, Object>> answers = new ArrayList<>(1);
        answers.add(Map.of("name", "hasDuplicate", "type", "noul", "label", "false"));
        return answers;
    }

    /**
     * 400 错误体。
     */
    private static String errorBody() {
        return "{\"error\":{\"message\":\"bad request\",\"type\":\"client_error\"}}";
    }

    /**
     * 单条答案的 JSON。
     */
    private static String answerJson(Map<String, Object> a) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"name\":\"").append(a.get("name")).append("\",");
        sb.append("\"type\":\"").append(a.get("type")).append("\",");
        sb.append("\"label\":\"").append(a.get("label")).append("\"");
        if (a.containsKey("choice")) {
            sb.append(",\"choice\":\"").append(a.get("choice")).append("\"");
        }
        if (a.containsKey("score")) {
            sb.append(",\"score\":").append(a.get("score"));
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * 标准 OpenAI 格式的答案。
     */
    private static String bodyOk(List<Map<String, Object>> answers, String model) {
        return "{\"id\":\"dec-20261029\",\"object\":\"list\",\"created\":1730188800,\"model\":\""
                + model + "\",\"answers\":["
                + String.join(",", answers.stream().map(OpenAiDecisionsClientTest::answerJson).toList())
                + "],"
                + "\"usage\":{\"prompt_tokens\":33,\"completion_tokens\":14,\"total_tokens\":47}}"
                + "}";
    }

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/v1/decisions", this::handleDecisions);
        server.createContext("/v1/systemone", this::handleDecisions);
        server.setExecutor(null);
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 两种协议的判读入口。
     */
    private void handleDecisions(HttpExchange exchange) {
        System.out.println("MOCK[DEBUG] " + exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                + " auth=[" + exchange.getRequestHeaders().getFirst("Authorization") + "]");
        hit.set(true);
        try {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            String body = new String(bytes, StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            received.put("method", exchange.getRequestMethod());
            received.put("path", path);
            received.put("headers", exchange.getRequestHeaders().getFirst("Authorization"));
            received.put("body", body);
            log.info("MOCK {} {}  headers=[{}] ({} bytes)", exchange.getRequestMethod(), path,
                    exchange.getRequestHeaders().getFirst("Authorization"), bytes.length);
            if (error400.get()) {
                handleStatus(exchange, 400, errorBody());
                return;
            }
            // 至少响应体随协议走：SystemOne 只有 1 个答案
            String model = path.endsWith("/systemone") ? "jev-latest" : "gpt-6-luna";
            List<Map<String, Object>> answers = path.endsWith("/systemone") ? systemOneAnswers() : standardAnswers();
            handleStatus(exchange, 200, bodyOk(answers, model));
        } catch (Exception e) {
            log.error("Mock failed", e);
        }
    }

    /**
     * 包装状态码 + 响应体。
     */
    private void handleStatus(HttpExchange exchange, int status, String body) throws Exception {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, out.length);
        received.put("status", status);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    /**
     * 四部分断言：模型 + 协议 + 答案标签 + 用量。
     */
    @Test
    void testDecideOpenAi() throws Exception {
        received.clear();
        hit.set(false);
        error400.set(false);
        DecisionsRequest request = DecisionsRequest.create("我被收取了两次款。",
                List.of(
                        DecisionQuestion.noul("hasDuplicate", "是否重复扣费"),
                        DecisionQuestion.choice("department", "哪个部门处理？",
                                Map.of("billing", "账单退款", "technical", "产品问题")),
                        DecisionQuestion.score("amount", "订单金额区间",
                                List.of("低", "中", "高"))));

        DecisionsClient client = new OpenAiDecisionsClient(
                DecisionsSetting.builder()
                        .provider("openai").appKey(API_KEY).baseUrl(BASE_URL)
                        .protocol(DecisionsProtocol.OPENAI).build());

        DecisionsResult result = client.decide(request);
        assertNotNull(result, "result should not be null");
        assertEquals(DecisionsProtocol.OPENAI, result.protocol(), "protocol");
        assertEquals("gpt-6-luna", result.model(), "model");

        Map<String, DecisionAnswer> answers = result.answers();
        assertEquals(3, answers.size(), "answers");
        assertEquals(Boolean.FALSE, answers.get("hasDuplicate").value(), "noul label");
        assertEquals("billing", answers.get("department").value(), "choice label");
        assertEquals(3, answers.get("amount").score().intValue(), "score label");
        assertEquals(Integer.valueOf(33), result.usage().getInputTokens(), "input tokens");
        assertEquals(Integer.valueOf(14), result.usage().getOutputTokens(), "output tokens");
        assertEquals(Integer.valueOf(47), result.usage().getTotalTokens(), "total tokens");

        // Mock 是否拦截到合法请求？
        assertTrue(hit.get(), "server should have received the request");
        assertNotNull(received.get("body"), "body should be recorded");
        String bodyStr = String.valueOf(received.get("body"));
        assertTrue(bodyStr.contains("\"model\":\"gpt-6-luna\""), "model in body: " + bodyStr);
        assertTrue(bodyStr.contains("\"input\":\"我被收取了两次款。\""), "input in body: " + bodyStr);
        assertTrue(bodyStr.contains("\"questions\""), "questions in body: " + bodyStr);
        assertTrue(bodyStr.contains("\"hasDuplicate\""), "question name in body: " + bodyStr);
        assertTrue(bodyStr.contains("\"department\""), "question name in body: " + bodyStr);
        assertTrue(bodyStr.contains("\"amount\""), "question name in body: " + bodyStr);
        assertEquals("Bearer " + API_KEY, received.get("headers"), "auth header: " + received.get("headers"));
        assertEquals("POST", received.get("method"), "method");
        assertEquals("/v1/decisions", received.get("path"), "path");
        log.info("OPENAI REQUEST BODY: {}", bodyStr);
    }

    /**
     * SystemOne 协议：POST /v1/systemone，state + questions（对象）。
     */
    @Test
    void testDecideSystemOne() throws Exception {
        received.clear();
        hit.set(false);
        error400.set(false);
        DecisionsRequest request = DecisionsRequest.create("我被收取了两次款。",
                List.of(DecisionQuestion.noul("hasDuplicate", "是否重复扣费")));

        DecisionsClient client = new OpenAiDecisionsClient(
                DecisionsSetting.builder()
                        .provider("typesafe").appKey(API_KEY).baseUrl(BASE_URL)
                        .protocol(DecisionsProtocol.SYSTEMONE).build());

        DecisionsResult result = client.decide(request);
        assertNotNull(result, "result should not be null");
        assertEquals(DecisionsProtocol.SYSTEMONE, result.protocol(), "protocol");
        assertEquals("jev-latest", result.model(), "model");

        Map<String, DecisionAnswer> answers = result.answers();
        assertEquals(1, answers.size(), "answers");
        assertEquals(Boolean.FALSE, answers.get("hasDuplicate").value(), "noul label");

        assertTrue(hit.get(), "server should have received the request");
        String bodyStr = String.valueOf(received.get("body"));
        assertTrue(bodyStr.contains("\"state\":\"我被收取了两次款。\""), "state in body: " + bodyStr);
        assertTrue(bodyStr.contains("\"questions\""), "questions in body: " + bodyStr);
        assertEquals("Bearer " + API_KEY, received.get("headers"), "auth header: " + received.get("headers"));
        assertEquals("/v1/systemone", received.get("path"), "path");
        log.info("SYSTEMONE REQUEST BODY: {}", bodyStr);
    }

    /**
     * 未显式配置协议时，模型词系也可以推断协议：
     * jev 系列模型属于 SystemOne 端点。
     */
    @Test
    void testModelJevResolvesSystemOne() throws Exception {
        received.clear();
        hit.set(false);
        error400.set(false);
        DecisionsRequest request = DecisionsRequest.create("我被收取了两次款。",
                List.of(DecisionQuestion.noul("hasDuplicate", "是否重复扣费")));

        DecisionsClient client = new OpenAiDecisionsClient(
                DecisionsSetting.builder()
                        .provider("openai").model("jev-latest").baseUrl(BASE_URL).build());

        DecisionsResult result = client.decide(request);
        assertNotNull(result, "result should not be null");
        assertEquals(DecisionsProtocol.SYSTEMONE, result.protocol(), "protocol");
        assertEquals("jev-latest", result.model(), "model");

        assertTrue(hit.get(), "server should have received the request");
        assertEquals("/v1/systemone", received.get("path"), "path should be SystemOne endpoint");
        String bodyStr = String.valueOf(received.get("body"));
        assertTrue(bodyStr.contains("\"state\":\"我被收取了两次款。\""), "state in body: " + bodyStr);
        log.info("JEV MODEL SYSTEMONE REQUEST BODY: {}", bodyStr);
    }

    /**
     * 无 Key 调用：不携带 Authorization 头。
     */
    @Test
    void testNoKey() throws Exception {
        received.clear();
        hit.set(false);
        error400.set(false);
        DecisionsRequest request = DecisionsRequest.create("无密钥", List.of(DecisionQuestion.noul("hasDuplicate", "是不是")));

        DecisionsClient client = new OpenAiDecisionsClient(
                DecisionsSetting.builder()
                        .provider("openai").baseUrl(BASE_URL).build());

        DecisionsResult result = client.decide(request);
        assertNotNull(result, "result should not be null");
        assertEquals(Boolean.FALSE, result.answers().get("hasDuplicate").value(), "noul label");
        assertEquals(null, received.get("headers"), "no auth header should be sent");
        log.info("NO-KEY REQUEST BODY: {}", received.get("body"));
    }

    /**
     * 400 错误：解析 error 段。
     */
    @Test
    void testError400() throws Exception {
        received.clear();
        hit.set(false);
        error400.set(true);
        DecisionsRequest request = DecisionsRequest.create("无密钥", List.of(DecisionQuestion.noul("hasDuplicate", "是不是")));
        try {
            new OpenAiDecisionsClient(
                    DecisionsSetting.builder()
                            .provider("openai").appKey(API_KEY).baseUrl(BASE_URL).build())
                    .decide(request);
            fail("should have thrown");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("bad request"), "message: " + e.getMessage());
        }
        assertTrue(hit.get(), "server should have received the request");
        assertEquals(Integer.valueOf(400), received.get("status"), "status code");
    }

    /**
     * 异步调用。
     */
    @Test
    void testAsyncMode() throws Exception {
        received.clear();
        hit.set(false);
        error400.set(false);
        DecisionsRequest request = DecisionsRequest.create("无密钥", List.of(DecisionQuestion.noul("hasDuplicate", "是不是")));
        DecisionsClient client = new OpenAiDecisionsClient(
                DecisionsSetting.builder()
                        .provider("openai").appKey(API_KEY).baseUrl(BASE_URL).build());
        CountDownLatch latch = new CountDownLatch(1);
        client.decideAsync(request).whenComplete((r, e) -> {
            if (e != null) {
                log.error("async error", e);
                latch.countDown();
                return;
            }
            assertNotNull(r, "async result should not be null");
            assertEquals(Boolean.FALSE, r.answers().get("hasDuplicate").value(), "noul label");
            latch.countDown();
        });
        assertTrue(latch.await(30, TimeUnit.SECONDS), "async should complete");
        assertTrue(hit.get(), "server should have received the request");
        log.info("ASYNC REQUEST BODY: {}", received.get("body"));
    }
}
