import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.prometheus.support.client.PrometheusClient;
import com.chua.prometheus.support.engine.PrometheusEngine;
import com.chua.prometheus.support.model.PrometheusAlert;
import com.chua.prometheus.support.model.PrometheusMetric;
import com.chua.prometheus.support.model.PrometheusRule;
import com.chua.prometheus.support.model.PrometheusTarget;
import com.chua.prometheus.support.model.QueryResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 引擎验收运行器 —— 阶段八：Prometheus 引擎 + 客户端（本地 HTTP stub，真实读写）。
 *
 * <p>前七阶段里 Prometheus 只有"契约面"（本机无 Prometheus 服务）。但它的底层
 * {@link PrometheusClient} 是纯 JDK {@code HttpClient} + 一组固定的
 * {@code /api/v1/*} 端点，完全可以在本机用 {@link HttpServer} 起一个桩服务，
 * 从而把链路真正跑通：URL 拼接 → 发起请求 → 解析 JSON → 映射模型 → 错误语义 → 关闭。</p>
 *
 * <p>桩覆盖 8 个端点：{@code query / query_range / series / label/names /
 * label/&lt;name&gt;/values / targets / rules / alerts}，并由 {@code QUERY_MODE}
 * 切换 {@code /query} 的返回：正常 vector、scalar、非 JSON、HTTP 500、
 * {@code status=error}、不支持的 resultType，用于验证失败路径不静默。</p>
 *
 * <p>两条通道都验：直接使用 {@link PrometheusClient}（含 Basic 认证头与关闭语义），
 * 以及经 SPI {@code Engine.create("prometheus")} 的 {@link PrometheusEngine}。</p>
 */
public final class EnginePrometheus {

    /** 桩服务。 */
    private static HttpServer server;

    /** 桩服务端口。 */
    private static int port;

    /** {@code /query} 的返回模式。 */
    private static final AtomicReference<String> QUERY_MODE = new AtomicReference<>("vector");

    /** 桩收到的最后一次 {@code Authorization} 头。 */
    private static final AtomicReference<String> AUTH_HEADER = new AtomicReference<>();

    /** 桩收到的最后一次 {@code /query} 查询串。 */
    private static final AtomicReference<String> QUERY_PARAMS = new AtomicReference<>();

    /** 桩收到的最后一次 {@code /query_range} 查询串。 */
    private static final AtomicReference<String> RANGE_PARAMS = new AtomicReference<>();

    private EnginePrometheus() {
    }

    public static void main(String[] args) {
        EngineFunctional.emit("=".repeat(78));
        EngineFunctional.emit("  引擎验收 阶段八：Prometheus 引擎 + 客户端（本地 HTTP stub）");
        EngineFunctional.emit("=".repeat(78));

        try {
            startStub();
        } catch (Throwable t) {
            EngineFunctional.recordFail("无法启动本地 HTTP 桩: " + EngineFunctional.chain(t));
            EngineFunctional.finishAndExit("EnginePrometheus-report.txt");
            return;
        }
        try {
            runQuietly("client", EnginePrometheus::clientSuite);
            runQuietly("engine", EnginePrometheus::engineSuite);
        } finally {
            stopStub();
        }

        EngineFunctional.finishAndExit("EnginePrometheus-report.txt");
    }

    // ==================== 桩服务 ====================

    private static String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private static void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/query_range", ex -> {
            RANGE_PARAMS.set(ex.getRequestURI().getQuery());
            respond(ex, 200, MATRIX_JSON);
        });
        server.createContext("/api/v1/query", ex -> {
            AUTH_HEADER.set(ex.getRequestHeaders().getFirst("Authorization"));
            QUERY_PARAMS.set(ex.getRequestURI().getQuery());
            switch (QUERY_MODE.get()) {
                case "http500" -> respond(ex, 500, "boom");
                case "notjson" -> respond(ex, 200, "this-is-not-json");
                case "apierror" -> respond(ex, 200,
                        "{\"status\":\"error\",\"errorType\":\"bad_data\",\"error\":\"boom\"}");
                case "unsupported" -> respond(ex, 200,
                        "{\"status\":\"success\",\"data\":{\"resultType\":\"graph\",\"result\":[]}}");
                case "scalar" -> respond(ex, 200,
                        "{\"status\":\"success\",\"data\":{\"resultType\":\"scalar\",\"result\":[1701261657.078,\"42\"]}}");
                default -> respond(ex, 200, VECTOR_JSON);
            }
        });
        server.createContext("/api/v1/series", ex -> respond(ex, 200, SERIES_JSON));
        server.createContext("/api/v1/label/names", ex -> respond(ex, 200, LABELS_JSON));
        server.createContext("/api/v1/label/job/values", ex -> respond(ex, 200, LABEL_VALUES_JSON));
        server.createContext("/api/v1/targets", ex -> respond(ex, 200, TARGETS_JSON));
        server.createContext("/api/v1/rules", ex -> respond(ex, 200, RULES_JSON));
        server.createContext("/api/v1/alerts", ex -> respond(ex, 200, ALERTS_JSON));
        server.start();
        port = server.getAddress().getPort();
        EngineFunctional.emit("  [stub] 已启动 127.0.0.1:" + port);
    }

    private static void stopStub() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ==================== PrometheusClient 直连 ====================

    static void clientSuite() {
        EngineFunctional.emit("--- prometheus:client (PrometheusClient 直连桩服务) ---");
        String p = "client.";

        EngineFunctional.check(p + "builder 地址为空被拒", () -> {
            expectType(IllegalArgumentException.class, () -> PrometheusClient.builder().baseUrl("").build());
            expectType(IllegalArgumentException.class, () -> PrometheusClient.builder().baseUrl(null).build());
            return "已拒绝";
        });

        EngineFunctional.check(p + "builder 非 http/https 协议被拒", () -> {
            expectType(IllegalArgumentException.class, () -> PrometheusClient.builder().baseUrl("ftp://x:9090").build());
            return "已拒绝";
        });

        EngineFunctional.check(p + "builder timeout<=0 被拒", () -> {
            expectType(IllegalArgumentException.class, () -> PrometheusClient.builder().timeoutMs(0).build());
            return "已拒绝";
        });

        EngineFunctional.check(p + "baseUrl 尾部斜杠被规整", () -> {
            try (PrometheusClient c = PrometheusClient.builder().baseUrl(baseUrl() + "///").build()) {
                if (!baseUrl().equals(c.baseUrl())) {
                    throw new EngineFunctional.HarnessFailure("实际: " + c.baseUrl());
                }
                return c.baseUrl();
            }
        });

        try (PrometheusClient client = PrometheusClient.builder().baseUrl(baseUrl()).build()) {
            EngineFunctional.check(p + "query(vector) 解析标签与值", () -> {
                QueryResult r = client.query("up").execute();
                if (!"vector".equals(r.getResultType()) || !r.hasData() || r.getResult().size() != 1) {
                    throw new EngineFunctional.HarnessFailure("结果错误: " + r);
                }
                PrometheusMetric m = r.getResult().get(0);
                if (!"up".equals(m.getName()) || !"node".equals(m.getLabel("job"))
                        || m.getValue() == null || m.getValue() != 1.0d) {
                    throw new EngineFunctional.HarnessFailure("指标错误: " + m);
                }
                if (m.getTimestamp() != 1701261657L) {
                    throw new EngineFunctional.HarnessFailure("时间戳错误: " + m.getTimestamp());
                }
                return "up/job=node/value=1.0";
            });

            EngineFunctional.check(p + "query(time) 下发 time 参数", () -> {
                client.query("up").time(123L).execute();
                if (QUERY_PARAMS.get() == null || !QUERY_PARAMS.get().contains("time=123")) {
                    throw new EngineFunctional.HarnessFailure("查询串: " + QUERY_PARAMS.get());
                }
                return "time=123";
            });

            EngineFunctional.check(p + "query(空 promql) 被拒", () -> {
                expectType(IllegalArgumentException.class, () -> client.query("  ").execute());
                return "已拒绝";
            });

            EngineFunctional.check(p + "query scalar 解析值对", () -> {
                QUERY_MODE.set("scalar");
                try {
                    QueryResult r = client.query("scalar(1)").execute();
                    if (!"scalar".equals(r.getResultType()) || r.getResult().size() != 1) {
                        throw new EngineFunctional.HarnessFailure("结果错误: " + r);
                    }
                    PrometheusMetric m = r.getResult().get(0);
                    if (m.getValue() == null || m.getValue() != 42.0d || m.getTimestamp() != 1701261657L) {
                        throw new EngineFunctional.HarnessFailure("标量错误: " + m);
                    }
                    return "42.0";
                } finally {
                    QUERY_MODE.set("vector");
                }
            });

            EngineFunctional.check(p + "query 远端 status=error 抛 RemoteExecutionException", () -> {
                QUERY_MODE.set("apierror");
                try {
                    expectType(com.chua.common.support.exception.RemoteExecutionException.class,
                            () -> client.query("up").execute());
                    return "RemoteExecutionException";
                } finally {
                    QUERY_MODE.set("vector");
                }
            });

            EngineFunctional.check(p + "query HTTP 500 抛 RemoteExecutionException", () -> {
                QUERY_MODE.set("http500");
                try {
                    expectType(com.chua.common.support.exception.RemoteExecutionException.class,
                            () -> client.query("up").execute());
                    return "RemoteExecutionException";
                } finally {
                    QUERY_MODE.set("vector");
                }
            });

            EngineFunctional.check(p + "query 非 JSON 抛 RemoteExecutionException", () -> {
                QUERY_MODE.set("notjson");
                try {
                    expectType(com.chua.common.support.exception.RemoteExecutionException.class,
                            () -> client.query("up").execute());
                    return "RemoteExecutionException";
                } finally {
                    QUERY_MODE.set("vector");
                }
            });

            EngineFunctional.check(p + "query 不支持 resultType 抛 UnsupportedOperationException", () -> {
                QUERY_MODE.set("unsupported");
                try {
                    expectType(UnsupportedOperationException.class, () -> client.query("up").execute());
                    return "UnsupportedOperationException";
                } finally {
                    QUERY_MODE.set("vector");
                }
            });

            EngineFunctional.check(p + "queryRange(matrix) 解析多样本", () -> {
                QueryResult r = client.queryRange("up").range(1000L, 2000L).step(60L).execute();
                if (!"matrix".equals(r.getResultType()) || r.getResult().size() != 1) {
                    throw new EngineFunctional.HarnessFailure("结果错误: " + r);
                }
                List<PrometheusMetric.Sample> samples = r.getResult().get(0).getValues();
                if (samples.size() != 2 || samples.get(0).value() != 1.0d || samples.get(1).value() != 0.0d) {
                    throw new EngineFunctional.HarnessFailure("样本错误: " + samples);
                }
                String q = RANGE_PARAMS.get();
                if (q == null || !q.contains("start=1000") || !q.contains("end=2000") || !q.contains("step=60")) {
                    throw new EngineFunctional.HarnessFailure("查询串: " + q);
                }
                return "samples=2";
            });

            EngineFunctional.check(p + "queryRange 非法范围/步长被拒", () -> {
                expectType(IllegalArgumentException.class, () -> client.queryRange("up").range(2000L, 1000L));
                expectType(IllegalArgumentException.class, () -> client.queryRange("up").step(0L));
                expectType(IllegalArgumentException.class, () -> client.queryRange("up").lastMinutes(0L));
                return "已拒绝";
            });

            EngineFunctional.check(p + "labels() 解析标签名列表", () -> {
                List<String> labels = client.labels();
                if (labels.size() != 3 || !labels.contains("job")) {
                    throw new EngineFunctional.HarnessFailure("标签: " + labels);
                }
                return String.valueOf(labels);
            });

            EngineFunctional.check(p + "labelValues(job) 解析标签值", () -> {
                List<String> values = client.labelValues("job");
                if (values.size() != 2 || !values.contains("prometheus")) {
                    throw new EngineFunctional.HarnessFailure("标签值: " + values);
                }
                return String.valueOf(values);
            });

            EngineFunctional.check(p + "labelValues 非法标签名被拒", () -> {
                expectType(IllegalArgumentException.class, () -> client.labelValues("../etc"));
                expectType(IllegalArgumentException.class, () -> client.labelValues("a-b"));
                return "已拒绝";
            });

            EngineFunctional.check(p + "series(match) 解析序列标签", () -> {
                List<Map<String, String>> rows = client.series().match("up").list();
                if (rows.size() != 1 || !"up".equals(rows.get(0).get("__name__"))
                        || !"node".equals(rows.get(0).get("job"))) {
                    throw new EngineFunctional.HarnessFailure("序列: " + rows);
                }
                return String.valueOf(rows.get(0));
            });

            EngineFunctional.check(p + "series 缺少 match 被拒", () -> {
                expectType(IllegalArgumentException.class, () -> client.series().list());
                return "已拒绝";
            });

            EngineFunctional.check(p + "targets() 解析抓取目标（RFC3339 时间转毫秒）", () -> {
                List<PrometheusTarget> targets = client.targets();
                if (targets.size() != 1) {
                    throw new EngineFunctional.HarnessFailure("目标数: " + targets.size());
                }
                PrometheusTarget t = targets.get(0);
                if (!"up".equals(t.getHealth()) || !"node".equals(t.getJob())
                        || !"127.0.0.1:9100".equals(t.getInstance())
                        || t.getLastScrape() != 1704067200123L
                        || Math.abs(t.getScrapeDuration() - 0.0123d) > 1e-9) {
                    throw new EngineFunctional.HarnessFailure("目标错误: " + t);
                }
                return "up/node/1704067200123";
            });

            EngineFunctional.check(p + "rules() 解析规则分组与元数据", () -> {
                List<PrometheusRule> rules = client.rules();
                if (rules.size() != 1) {
                    throw new EngineFunctional.HarnessFailure("规则数: " + rules.size());
                }
                PrometheusRule r = rules.get(0);
                if (!"HighErrorRate".equals(r.getName()) || !"alerting".equals(r.getType())
                        || !"firing".equals(r.getState()) || !"g1".equals(r.getRuleGroup())
                        || r.getDuration() != 300.0d || r.getLastEvaluation() != 1704067200000L
                        || !"critical".equals(r.getLabels().get("severity"))) {
                    throw new EngineFunctional.HarnessFailure("规则错误: " + r);
                }
                return "HighErrorRate/g1";
            });

            EngineFunctional.check(p + "alerts() 解析当前告警", () -> {
                List<PrometheusAlert> alerts = client.alerts();
                if (alerts.size() != 1) {
                    throw new EngineFunctional.HarnessFailure("告警数: " + alerts.size());
                }
                PrometheusAlert a = alerts.get(0);
                if (!"firing".equals(a.getState()) || !"1".equals(a.getValue())
                        || a.getActiveAt() != 1704067200000L
                        || !"A".equals(a.getLabels().get("alertname"))) {
                    throw new EngineFunctional.HarnessFailure("告警错误: " + a);
                }
                return "firing/A";
            });

            EngineFunctional.check(p + "close 幂等且关闭后请求被拒", () -> {
                PrometheusClient c = PrometheusClient.builder().baseUrl(baseUrl()).build();
                c.close();
                c.close();
                if (!c.isClosed()) {
                    throw new EngineFunctional.HarnessFailure("isClosed 应为 true");
                }
                expectType(IllegalStateException.class, () -> c.labels());
                return "idempotent";
            });
        }

        EngineFunctional.check(p + "Basic 认证头按 userId:password 生成", () -> {
            String expected = "Basic " + Base64.getEncoder()
                    .encodeToString("user:pass".getBytes(StandardCharsets.UTF_8));
            try (PrometheusClient c = PrometheusClient.builder()
                    .baseUrl(baseUrl())
                    .basicAuth("user", "pass")
                    .build()) {
                // 只有 /api/v1/query 处理器记录 Authorization 头，故必须走 query 通道
                c.query("up").execute();
            }
            if (!expected.equals(AUTH_HEADER.get())) {
                throw new EngineFunctional.HarnessFailure("实际: " + AUTH_HEADER.get());
            }
            return "Basic user:pass";
        });
    }

    // ==================== PrometheusEngine 经 SPI ====================

    static void engineSuite() {
        EngineFunctional.emit("--- prometheus:engine (PrometheusEngine 经 SPI，桩服务) ---");
        String p = "engine.";

        PrometheusEngine engine = (PrometheusEngine) Engine.create("prometheus");
        if (engine == null) {
            EngineFunctional.recordFail(p + "Engine.create(\"prometheus\") 返回 null");
            return;
        }

        EngineFunctional.check(p + "addDataSource(name, url) 并登记为默认", () -> {
            engine.addDataSource("default", baseUrl());
            if (!engine.dataSourceNames().contains("default")) {
                throw new EngineFunctional.HarnessFailure("数据源: " + engine.dataSourceNames());
            }
            if (!"default".equals(engine.getDefaultDataSourceName())) {
                throw new EngineFunctional.HarnessFailure("默认数据源: " + engine.getDefaultDataSourceName());
            }
            if (!baseUrl().equals(engine.client().baseUrl())) {
                throw new EngineFunctional.HarnessFailure("client baseUrl: " + engine.client().baseUrl());
            }
            return "default=" + baseUrl();
        });

        EngineFunctional.check(p + "addDataSource(name, client) 与 getDataSource 可见性", () -> {
            PrometheusClient c = PrometheusClient.builder().baseUrl(baseUrl()).build();
            engine.addDataSource("second", c);
            if (engine.getDataSource("second") == null) {
                throw new EngineFunctional.HarnessFailure("second 数据源不可见");
            }
            if (engine.getDataSource() == null) {
                throw new EngineFunctional.HarnessFailure("默认数据源不可见");
            }
            if (engine.getDataSource("missing") != null || engine.getDataSource(null) != null) {
                throw new EngineFunctional.HarnessFailure("未注册数据源应返回 null");
            }
            PrometheusClient fetched = engine.client("second");
            if (fetched != c) {
                throw new EngineFunctional.HarnessFailure("client(second) 应返回同一实例");
            }
            return engine.dataSourceNames().toString();
        });

        EngineFunctional.check(p + "addDataSource 空名称被拒", () -> {
            expectType(IllegalArgumentException.class, () -> engine.addDataSource("  ", baseUrl()));
            expectType(IllegalArgumentException.class, () -> engine.addDataSource("x", (String) null));
            return "已拒绝";
        });

        EngineFunctional.check(p + "client(name) 未注册抛 IllegalArgumentException", () -> {
            expectType(IllegalArgumentException.class, () -> engine.client("nope"));
            return "IllegalArgumentException";
        });

        EngineFunctional.check(p + "能力声明：非 SQL / 非元数据", () -> {
            if (engine.supportsSql() || engine.supportsMeta()) {
                throw new EngineFunctional.HarnessFailure("supportsSql/supportsMeta 应均为 false");
            }
            if (engine.getExecutor() != null || engine.getExecutor("default") != null) {
                throw new EngineFunctional.HarnessFailure("getExecutor 应返回 null");
            }
            if (engine.getDialect("default") != null) {
                throw new EngineFunctional.HarnessFailure("getDialect 应返回 null");
            }
            return "sql=false meta=false executor=null dialect=null";
        });

        EngineFunctional.check(p + "只读语义：store/execute/Lambda 三入口均 UOE", () -> {
            expectType(UnsupportedOperationException.class, () -> engine.store("t", List.of()));
            expectType(UnsupportedOperationException.class, () -> engine.execute("DELETE FROM x"));
            expectType(UnsupportedOperationException.class, () -> engine.query(Object.class));
            expectType(UnsupportedOperationException.class, () -> engine.update(Object.class));
            expectType(UnsupportedOperationException.class, () -> engine.delete(Object.class));
            expectType(UnsupportedOperationException.class, () -> engine.meta());
            return "全部 UOE";
        });

        EngineFunctional.check(p + "queryInstant 委托桩服务并解析", () -> {
            QueryResult r = engine.queryInstant("up");
            if (!"vector".equals(r.getResultType()) || !r.hasData()
                    || r.getResult().get(0).getValue() != 1.0d) {
                throw new EngineFunctional.HarnessFailure("结果错误: " + r);
            }
            return "vector/1.0";
        });

        EngineFunctional.check(p + "targets/rules/alerts 委托桩服务", () -> {
            if (engine.targets().size() != 1 || engine.rules().size() != 1 || engine.alerts().size() != 1) {
                throw new EngineFunctional.HarnessFailure("委托结果数量不符");
            }
            return "1/1/1";
        });

        EngineFunctional.check(p + "query() 构建器在非 SQL 引擎上终端抛 UOE（不预检）", () -> {
            expectType(UnsupportedOperationException.class, () -> engine.query().table("up").list());
            expectType(UnsupportedOperationException.class, () -> engine.query().select("a").table("up").execute());
            return "UnsupportedOperationException";
        });

        EngineFunctional.check(p + "setDefaultDataSourceName 生效后 client() 随之切换", () -> {
            engine.setDefaultDataSourceName("second");
            if (engine.client() == null) {
                throw new EngineFunctional.HarnessFailure("切换默认数据源后 client() 为空");
            }
            engine.setDefaultDataSourceName("default");
            expectType(IllegalArgumentException.class, () -> engine.setDefaultDataSourceName(""));
            return "已切换";
        });

        EngineFunctional.check(p + "close 幂等，关闭后变更入口被拒", () -> {
            engine.close();
            if (!engine.isClosed()) {
                throw new EngineFunctional.HarnessFailure("isClosed 应为 true");
            }
            engine.close();
            expectType(IllegalStateException.class, () -> engine.client());
            expectType(IllegalStateException.class, () -> engine.addDataSource("x", baseUrl()));
            expectType(IllegalStateException.class, () -> engine.setDefaultDataSourceName("x"));
            return "closed+idempotent";
        });
    }

    // ==================== 辅助 ====================

    /** 期望抛出指定类型异常。 */
    private static void expectType(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return;
            }
            throw new EngineFunctional.HarnessFailure(
                    "期望 " + type.getSimpleName() + "，实际 " + EngineFunctional.chain(t));
        }
        throw new EngineFunctional.HarnessFailure("未抛 " + type.getSimpleName());
    }

    /** 隔离执行一个套件。 */
    private static void runQuietly(String name, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            EngineFunctional.recordFail(name + " 套件异常退出: " + EngineFunctional.chain(t));
        }
    }

    // ==================== 桩数据 ====================

    private static final String VECTOR_JSON =
            "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                    + "{\"metric\":{\"__name__\":\"up\",\"job\":\"node\",\"instance\":\"127.0.0.1:9100\"},"
                    + "\"value\":[1701261657.078,\"1\"]}]}}";

    private static final String MATRIX_JSON =
            "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                    + "{\"metric\":{\"__name__\":\"up\"},\"values\":[[1701261657.078,\"1\"],[1701261717.078,\"0\"]]}]}}";

    private static final String SERIES_JSON =
            "{\"status\":\"success\",\"data\":[{\"__name__\":\"up\",\"job\":\"node\"}]}";

    private static final String LABELS_JSON =
            "{\"status\":\"success\",\"data\":[\"__name__\",\"job\",\"instance\"]}";

    private static final String LABEL_VALUES_JSON =
            "{\"status\":\"success\",\"data\":[\"node\",\"prometheus\"]}";

    private static final String TARGETS_JSON =
            "{\"status\":\"success\",\"data\":{\"activeTargets\":[{"
                    + "\"scrapeUrl\":\"http://127.0.0.1:9100/metrics\",\"health\":\"up\",\"lastError\":\"\","
                    + "\"lastScrape\":\"2024-01-01T00:00:00.123Z\",\"lastScrapeDuration\":0.0123,"
                    + "\"labels\":{\"job\":\"node\",\"instance\":\"127.0.0.1:9100\"}}]}}";

    private static final String RULES_JSON =
            "{\"status\":\"success\",\"data\":{\"groups\":[{\"file\":\"/etc/prometheus/rules.yml\",\"name\":\"g1\","
                    + "\"rules\":[{\"name\":\"HighErrorRate\",\"query\":\"rate(errors[5m]) > 0.5\","
                    + "\"type\":\"alerting\",\"state\":\"firing\",\"health\":\"ok\",\"lastError\":\"\","
                    + "\"duration\":300,\"evaluationTime\":0.0021,\"lastEvaluation\":\"2024-01-01T00:00:00Z\","
                    + "\"labels\":{\"severity\":\"critical\"},\"annotations\":{\"summary\":\"high error rate\"}}]}]}}";

    private static final String ALERTS_JSON =
            "{\"status\":\"success\",\"data\":{\"alerts\":[{\"state\":\"firing\",\"value\":\"1\","
                    + "\"activeAt\":\"2024-01-01T00:00:00Z\",\"labels\":{\"alertname\":\"A\"},"
                    + "\"annotations\":{\"summary\":\"s\"}}]}}";
}
