package com.chua.datalake.support.webhook;

import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.datalake.support.engine.DefaultPipelineEngine;
import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.pipeline.DefaultPipelineManager;
import com.chua.datalake.support.sink.RealTimeSink;
import com.chua.datalake.support.spi.pipeline.PipelineManager;
import com.chua.datalake.support.spi.sink.DataSink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Webhook 入站适配器冒烟测试。
 *
 * <p>用真实的 {@link DefaultPipelineEngine} + 真实 Sink 起一个只监听回环的服务端，
 * 再用 HTTP 客户端从外部推数据，逐段验证：</p>
 * <ul>
 *   <li>HTTP 状态码来自管线终态：SINK_OK 才是 200，落盘失败 500，管线不存在 404</li>
 *   <li>入参形态（空体、数组/对象错配、非法 JSON、批量元素、pipelineId 字符集、体积上限）先拒后投</li>
 *   <li>批量请求不会半提交</li>
 *   <li>错误响应体始终是可解析的 JSON</li>
 *   <li>令牌与方法的鉴权，以及 start/stop 的真实语义</li>
 * </ul>
 *
 * <p>遵循项目约定使用 {@code main} 方法直接运行（本模块无 JUnit 依赖）：</p>
 * <pre>
 * 运行方式：{@code java com.chua.datalake.support.webhook.WebhookInboundAdapterSmokeTest}
 * 任一校验失败输出 FAIL 并以退出码 1 结束，全部通过输出 PASS。
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WebhookInboundAdapterSmokeTest {

    /**
     * 成功管线 DSL：落到恒真 Sink
     */
    private static final String DSL_OK =
            "{\"id\":\"p-ok\",\"stages\":{\"default\":{\"sink\":[{\"type\":\"probe-accept\"}]}}}";

    /**
     * 失败管线 DSL：落到实时 Sink，而本测试不登记任何实时通道
     */
    private static final String DSL_FAIL =
            "{\"id\":\"p-fail\",\"stages\":{\"default\":{\"sink\":[{\"type\":\"realtime\"}]}}}";

    /**
     * JSON 解析器
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * HTTP 客户端
     */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /**
     * 被真实管线接受的行
     */
    private static final List<Map<String, Object>> ACCEPTED = Collections.synchronizedList(new ArrayList<>());

    /**
     * 成功计数
     */
    private static int passCount = 0;

    /**
     * 失败计数
     */
    private static int failureCount = 0;

    /**
     * main。
     *
     * @param args 参数
     * @throws Exception 调用失败
     */
    public static void main(String[] args) throws Exception {
        WebhookInboundAdapter adapter = start(WebhookInboundAdapter.builder()
                .port(0)
                .pipelineEngine(realEngine()));
        try {
            lifecycle(adapter);
            // 端口 0 由系统分配，重启后地址会变，必须重新取
            String base = "http://127.0.0.1:" + adapter.getServerPort();
            acceptedPushMapsTo200(base);
            sinkFailureMapsTo500(base);
            unknownPipelineMapsTo404(base);
            batchAccounting(base);
            batchIsNotHalfCommitted(base);
            payloadShapeRejected(base);
            methodAndIdRejected(base);
            errorBodyStaysJson(base);
            bodySizeIsCapped();
            tokenGate();
            startFailureIsThrown();
        } finally {
            adapter.stop();
        }
        summary();
    }

    /**
     * 场景 1：start/stop 的真实语义。
     *
     * @param adapter 适配器
     * @throws Exception 调用失败
     */
    private static void lifecycle(WebhookInboundAdapter adapter) throws Exception {
        check(adapter.isRunning(), "start() 后 isRunning=true");
        check(adapter.getServerPort() > 0, "端口 0 由系统分配, 实际=" + adapter.getServerPort());
        int port = adapter.getServerPort();
        adapter.stop();
        boolean refused = refuses(port);
        System.out.println("OBSERVE stop.refused=" + refused);
        check(refused, "stop() 后端口已释放");
        adapter.start();
        check(adapter.isRunning() && isOk(get("http://127.0.0.1:" + adapter.getServerPort()
                + "/api/datalake/webhook/health")), "stop() 后仍可重新启动并服务");
    }

    /**
     * 场景 2：管线落盘成功对应 200。
     *
     * @param base 服务地址
     * @throws Exception 调用失败
     */
    private static void acceptedPushMapsTo200(String base) throws Exception {
        Reply reply = post(base + "/api/datalake/webhook/p-ok", "{\"id\":\"W1\"}", null);
        System.out.println("OBSERVE okPush status=" + reply.status + " body=" + reply.body);
        check(reply.status == 200, "SINK_OK 对应 HTTP 200（实际=" + reply.status + "）");
        check(reply.json.path("ok").asBoolean(), "应答 ok=true");
        check(reply.json.path("received").asInt() == 1, "应答 received=1");
        check("SINK_OK".equals(reply.json.path("state").asText()), "应答 state=SINK_OK");
        check("W1".equals(String.valueOf(ACCEPTED.get(ACCEPTED.size() - 1).get("id"))),
                "数据真的进入了 Sink");
    }

    /**
     * 场景 3：落盘失败必须让推送方看见。
     *
     * @param base 服务地址
     * @throws Exception 调用失败
     */
    private static void sinkFailureMapsTo500(String base) throws Exception {
        Reply reply = post(base + "/api/datalake/webhook/p-fail", "{\"id\":\"W2\"}", null);
        System.out.println("OBSERVE failPush status=" + reply.status + " body=" + reply.body);
        check(reply.status == 500, "SINK_FAIL 对应 HTTP 500（实际=" + reply.status + "）");
        check(!reply.json.path("ok").asBoolean(), "应答 ok=false");
        check(reply.json.path("failed").asInt() == 1, "应答 failed=1");
        check("SINK_FAIL".equals(reply.json.path("state").asText()), "应答 state=SINK_FAIL");
    }

    /**
     * 场景 4：管线不存在时不能报成功。
     *
     * @param base 服务地址
     * @throws Exception 调用失败
     */
    private static void unknownPipelineMapsTo404(String base) throws Exception {
        Reply reply = post(base + "/api/datalake/webhook/p-none", "{\"id\":\"W3\"}", null);
        System.out.println("OBSERVE unknownPush status=" + reply.status + " body=" + reply.body);
        check(reply.status == 404, "未注册的管线对应 HTTP 404（实际=" + reply.status + "）");
        check("pipeline_not_found".equals(reply.json.path("status").asText()), "应答 status=pipeline_not_found");
        check(reply.json.path("state").asText("").isEmpty(), "未进入管线时不返回 state 字段");
    }

    /**
     * 场景 5：批量计数按真实消化结果。
     *
     * @param base 服务地址
     * @throws Exception 调用失败
     */
    private static void batchAccounting(String base) throws Exception {
        Reply ok = post(base + "/api/datalake/webhook/p-ok/batch",
                "[{\"id\":\"B1\"},{\"id\":\"B2\"},{\"id\":\"B3\"}]", null);
        System.out.println("OBSERVE batchOk status=" + ok.status + " body=" + ok.body);
        check(ok.status == 200, "批量全部成功对应 200");
        check(ok.json.path("received").asInt() == 3, "批量 received=3");
        check(ok.json.path("failed").asInt() == 0, "批量 failed=0");

        Reply bad = post(base + "/api/datalake/webhook/p-fail/batch",
                "[{\"id\":\"B4\"},{\"id\":\"B5\"}]", null);
        System.out.println("OBSERVE batchFail status=" + bad.status + " body=" + bad.body);
        check(bad.status == 500, "批量失败对应 500");
        check(bad.json.path("failed").asInt() == 2, "批量 failed=2, 实际=" + bad.json.path("failed").asInt());
    }

    /**
     * 场景 6：批量元素形态非法时一条都不投递。
     *
     * @param base 服务地址
     * @throws Exception 调用失败
     */
    private static void batchIsNotHalfCommitted(String base) throws Exception {
        int before = ACCEPTED.size();
        Reply reply = post(base + "/api/datalake/webhook/p-ok/batch",
                "[{\"id\":\"C1\"},2,{\"id\":\"C3\"}]", null);
        System.out.println("OBSERVE halfCommit status=" + reply.status + " body=" + reply.body);
        check(reply.status == 400, "非法批量元素返回 400");
        check("invalid_payload".equals(reply.json.path("status").asText()), "应答 status=invalid_payload");
        check(ACCEPTED.size() == before, "校验先于投递，未出现半提交（新增=" + (ACCEPTED.size() - before) + "）");
    }

    /**
     * 场景 7：请求体形态契约。
     *
     * @param base 服务地址
     * @throws Exception 调用失败
     */
    private static void payloadShapeRejected(String base) throws Exception {
        Reply isArray = post(base + "/api/datalake/webhook/p-ok", "[{\"id\":\"S1\"}]", null);
        check(isArray.status == 400 && "invalid_payload".equals(isArray.json.path("status").asText()),
                "单条推送收到数组返回 400, 实际=" + isArray.status);
        Reply objectAsBatch = post(base + "/api/datalake/webhook/p-ok/batch", "{\"id\":\"S2\"}", null);
        check(objectAsBatch.status == 400, "批量推送收到对象返回 400, 实际=" + objectAsBatch.status);
        Reply empty = post(base + "/api/datalake/webhook/p-ok", "", null);
        check(empty.status == 400 && "empty_body".equals(empty.json.path("status").asText()),
                "空请求体返回 400 empty_body");
    }

    /**
     * 场景 8：方法与管线标识的入站校验。
     *
     * @param base 服务地址
     * @throws Exception 调用失败
     */
    private static void methodAndIdRejected(String base) throws Exception {
        Reply get = request("GET", base + "/api/datalake/webhook/p-ok", null, null);
        check(get.status == 405, "GET 推送路径返回 405, 实际=" + get.status);
        Reply nested = post(base + "/api/datalake/webhook/a/b", "{}", null);
        check(nested.status == 400 && "invalid_pipeline_id".equals(nested.json.path("status").asText()),
                "含路径分隔符的 pipelineId 返回 400, 实际=" + nested.status);
        Reply batchSuffixTwice = post(base + "/api/datalake/webhook/p-ok/batch/batch", "[]", null);
        check(batchSuffixTwice.status == 400, "重复 /batch 后缀只剥离一次并被判非法, 实际=" + batchSuffixTwice.status);
        Reply missingId = post(base + "/api/datalake/webhook/", "{}", null);
        check(missingId.status == 400, "缺少 pipelineId 返回 400, 实际=" + missingId.status);
    }

    /**
     * 场景 9：错误响应体本身必须是合法 JSON。
     *
     * @param base 服务地址
     * @throws Exception 调用失败
     */
    private static void errorBodyStaysJson(String base) throws Exception {
        Reply reply = post(base + "/api/datalake/webhook/p-ok", "{\"id\": \"broken", null);
        System.out.println("OBSERVE errorBody status=" + reply.status + " body=" + reply.body);
        check(reply.status == 400 && "invalid_json".equals(reply.json.path("status").asText()),
                "畸形 JSON 返回 400 且应答体可解析, 实际=" + reply.body);
    }

    /**
     * 场景 10：请求体体积上限。
     *
     * @throws Exception 调用失败
     */
    private static void bodySizeIsCapped() throws Exception {
        WebhookInboundAdapter capped = start(WebhookInboundAdapter.builder()
                .port(0)
                .maxBodyBytes(64)
                .pipelineEngine(realEngine()));
        try {
            StringBuilder big = new StringBuilder("{\"id\":\"");
            for (int i = 0; i < 400; i++) {
                big.append('x');
            }
            big.append("\"}");
            Reply reply = post("http://127.0.0.1:" + capped.getServerPort() + "/api/datalake/webhook/p-ok",
                    big.toString(), null);
            System.out.println("OBSERVE tooLarge status=" + reply.status + " body=" + reply.body);
            check(reply.status == 413, "超过上限返回 413, 实际=" + reply.status);
            check("payload_too_large".equals(reply.json.path("status").asText()), "应答 status=payload_too_large");
        } finally {
            capped.stop();
        }
    }

    /**
     * 场景 11：令牌门禁。
     *
     * @throws Exception 调用失败
     */
    private static void tokenGate() throws Exception {
        Map<String, String> wrong = Map.of("x-webhook-token", "nope");
        Map<String, String> right = Map.of("x-webhook-token", "s3cret");
        WebhookInboundAdapter guarded = start(WebhookInboundAdapter.builder()
                .port(0)
                .token("s3cret")
                .pipelineEngine(realEngine()));
        try {
            String base = "http://127.0.0.1:" + guarded.getServerPort();
            Reply anonymous = post(base + "/api/datalake/webhook/p-ok", "{\"id\":\"T1\"}", null);
            System.out.println("OBSERVE tokenAnonymous status=" + anonymous.status);
            check(anonymous.status == 401, "缺令牌返回 401, 实际=" + anonymous.status);
            Reply bad = post(base + "/api/datalake/webhook/p-ok", "{\"id\":\"T1\"}", wrong);
            check(bad.status == 401, "错令牌返回 401, 实际=" + bad.status);
            Reply good = post(base + "/api/datalake/webhook/p-ok", "{\"id\":\"T2\"}", right);
            check(good.status == 200, "正确令牌返回 200, 实际=" + good.status);
            int health = get(base + "/api/datalake/webhook/health");
            check(health == 200, "健康检查不要求令牌, 实际=" + health);
        } finally {
            guarded.stop();
        }
    }

    /**
     * 场景 12：端口被占时 start() 必须抛出。
     *
     * @throws Exception 调用失败
     */
    private static void startFailureIsThrown() throws Exception {
        WebhookInboundAdapter holder = start(WebhookInboundAdapter.builder()
                .port(0)
                .pipelineEngine(realEngine()));
        int taken = holder.getServerPort();
        try {
            WebhookInboundAdapter loser = WebhookInboundAdapter.builder()
                    .port(taken)
                    .pipelineEngine(realEngine())
                    .build();
            boolean thrown = false;
            try {
                loser.start();
            } catch (IllegalStateException e) {
                thrown = true;
                System.out.println("OBSERVE startFail " + e.getMessage());
            }
            check(thrown, "端口被占用时 start() 抛 IllegalStateException");
            check(!loser.isRunning(), "启动失败的适配器不置 running");
            check(holder.isRunning(), "已运行的适配器不受影响");
        } finally {
            holder.stop();
        }
    }

    // ━━━━━━━━━━━━━━ 装配与调用工具 ━━━━━━━━━━━━━━

    /**
     * 构建真实管线执行引擎：p-ok 恒成功，p-fail 走无通道的实时 Sink。
     *
     * @return 执行引擎
     */
    private static DefaultPipelineEngine realEngine() {
        Map<String, DataSink> registry = new HashMap<>();
        registry.put("probe-accept", new AcceptAllSink());
        registry.put("realtime", new RealTimeSink());
        PipelineManager pipelineManager = new DefaultPipelineManager();
        pipelineManager.savePipeline("p-ok", DSL_OK);
        pipelineManager.savePipeline("p-fail", DSL_FAIL);
        return new DefaultPipelineEngine(pipelineManager, registry, null);
    }

    /**
     * 恒真 Sink：记录收到的数据行并报告成功。
     *
     * @author CH
     * @since 4.0.0.42
     */
    private static final class AcceptAllSink implements DataSink {

        @Override
        public String type() {
            return "probe-accept";
        }

        @Override
        public void start() {
            // 无外部资源
        }

        @Override
        public void stop() {
            // 无外部资源
        }

        @Override
        public boolean write(DataEnvelope envelope, Map<String, Object> config) {
            ACCEPTED.add(envelope.getParsed());
            return true;
        }

        @Override
        public EngineDataSource<?> getDataSource() {
            return null;
        }
    }

    /**
     * 构建并启动适配器。
     *
     * @param builder 构建器
     * @return 已启动的适配器
     */
    private static WebhookInboundAdapter start(WebhookInboundAdapter.Builder builder) {
        WebhookInboundAdapter adapter = builder.build();
        adapter.start();
        return adapter;
    }

    /**
     * 应答：状态码 + 原始体 + 解析后的 JSON。
     *
     * @author CH
     * @since 4.0.0.42
     */
    private static final class Reply {

        /**
         * HTTP 状态码
         */
        final int status;

        /**
         * 原始响应体
         */
        final String body;

        /**
         * 解析后的 JSON，非法 JSON 时为空对象
         */
        final JsonNode json;

        /**
         * 构造应答。
         *
         * @param status HTTP 状态码
         * @param body   原始响应体
         * @param json   解析结果
         */
        Reply(int status, String body, JsonNode json) {
            this.status = status;
            this.body = body;
            this.json = json;
        }
    }

    /**
     * 发送 POST。
     *
     * @param url     地址
     * @param body    请求体
     * @param headers 附加请求头
     * @return 应答
     * @throws Exception 调用失败
     */
    private static Reply post(String url, String body, Map<String, String> headers) throws Exception {
        return request("POST", url, body, headers);
    }

    /**
     * 发送请求。
     *
     * @param method  方法
     * @param url     地址
     * @param body    请求体
     * @param headers 附加请求头
     * @return 应答
     * @throws Exception 调用失败
     */
    private static Reply request(String method, String url, String body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5));
        if (headers != null) {
            headers.forEach(builder::header);
        }
        if ("GET".equals(method)) {
            builder.GET();
        } else if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode json;
        try {
            json = MAPPER.readTree(response.body());
        } catch (Exception e) {
            json = MAPPER.createObjectNode();
        }
        if (json == null) {
            json = MAPPER.createObjectNode();
        }
        return new Reply(response.statusCode(), response.body(), json);
    }

    /**
     * GET 并只关心状态码。
     *
     * @param url 地址
     * @return 状态码，连不上时返回 -1
     */
    private static int get(String url) {
        try {
            return request("GET", url, null, null).status;
        } catch (Exception e) {
            System.out.println("        GET 失败: " + e);
            return -1;
        }
    }

    /**
     * 判断端口是否已无人监听。
     *
     * @param port 端口
     * @return true 表示连接被拒
     */
    private static boolean refuses(int port) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 1000);
            return false;
        } catch (ConnectException e) {
            return true;
        } catch (Exception e) {
            System.out.println("        连接探测异常: " + e);
            return false;
        }
    }

    /**
     * 判断状态码是否 200。
     *
     * @param status 状态码
     * @return 是否 200
     */
    private static boolean isOk(int status) {
        return status == 200;
    }

    /**
     * 记录单项校验。
     *
     * @param condition 条件
     * @param message   说明
     */
    private static void check(boolean condition, String message) {
        if (condition) {
            passCount++;
            System.out.println("[PASS] " + message);
        } else {
            failureCount++;
            System.out.println("[FAIL] " + message);
        }
    }

    /**
     * 输出汇总结果。
     */
    private static void summary() {
        System.out.println("WebhookInboundAdapterSmokeTest 结果: PASS=" + passCount + ", FAIL=" + failureCount);
        if (failureCount > 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }
}
