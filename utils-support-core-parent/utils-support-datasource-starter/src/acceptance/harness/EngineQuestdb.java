import com.chua.questdb.support.engine.QuestdbSeriesEngine;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 引擎验收运行器 —— 阶段十二：QuestdbSeriesEngine（纯 HTTP 协议实现，本机桩）。
 *
 * <p>{@code questdb} 是 {@code META-INF/extensions/…SeriesEngine} 登记的 SPI 实现，
 * 在本轮之前**从未被验收覆盖**。它的两条链路都是 HTTP：</p>
 *
 * <ul>
 *   <li>写入：官方 {@code Sender}（ILP over HTTP）→ {@code POST /write?precision=n}</li>
 *   <li>查询 / 指标发现 / 保留期清理：JDK HttpClient → {@code GET /exec?query=…}</li>
 * </ul>
 *
 * <p>因此可以用本机 {@link HttpServer} 起桩，做**真实协议执行**验收：断言实际发出的
 * ILP 报文、实际拼出的 SQL、实际挂在请求上的鉴权头，以及服务端各种畸形响应下的失败语义。</p>
 *
 * <p><b>本阶段能证明与不能证明的边界</b>：证明本引擎的协议编码、SQL 拼装、JSON 映射、
 * 降采样与失败语义；不证明真实 QuestDB 的 SQL 语义、ILP 解析与列类型推断。</p>
 */
public final class EngineQuestdb {

    /** 桩服务 */
    private static HttpServer server;
    private static int port;

    /** 已收到的请求记录（顺序保留） */
    private static final List<Rec> CALLS = Collections.synchronizedList(new ArrayList<>());

    /** /exec 的脚本化响应队列 */
    private static final Deque<Resp> SCRIPT = new ConcurrentLinkedDeque<>();

    /** /write 的响应状态（204 表示成功） */
    private static volatile int writeStatus = 204;

    /** /write 的响应体（仅在非 204 时使用） */
    private static volatile String writeBody = "";

    private EngineQuestdb() {
    }

    public static void main(String[] args) {
        EngineFunctional.emit("=".repeat(78));
        EngineFunctional.emit("  引擎验收 阶段十二：QuestdbSeriesEngine（本机 HTTP 桩）");
        EngineFunctional.emit("=".repeat(78));

        try {
            startStub();
        } catch (Throwable t) {
            EngineFunctional.recordFail("无法启动桩服务: " + EngineFunctional.chain(t));
            EngineFunctional.finishAndExit("EngineQuestdb-report.txt");
            return;
        }

        try {
            runQuietly("config", EngineQuestdb::configSuite);
            runQuietly("guard", EngineQuestdb::guardSuite);
            runQuietly("write", EngineQuestdb::writeSuite);
            runQuietly("auth", EngineQuestdb::authSuite);
            runQuietly("metrics", EngineQuestdb::metricsSuite);
            runQuietly("series", EngineQuestdb::seriesSuite);
            runQuietly("purge", EngineQuestdb::purgeSuite);
            runQuietly("lifecycle", EngineQuestdb::lifecycleSuite);
        } finally {
            stopStub();
        }

        EngineFunctional.finishAndExit("EngineQuestdb-report.txt");
    }

    // ==================== 桩服务 ====================

    private static String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private static void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/write", ex -> {
            String body = read(ex.getRequestBody());
            CALLS.add(new Rec("WRITE", ex.getRequestURI().getPath(),
                    ex.getRequestURI().getQuery(), auth(ex), body));
            int status = writeStatus;
            byte[] bytes = writeBody.getBytes(StandardCharsets.UTF_8);
            if (status == 204) {
                ex.sendResponseHeaders(204, -1);
                ex.close();
                return;
            }
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.createContext("/exec", ex -> {
            String raw = ex.getRequestURI().getQuery();
            String sql = raw == null ? "" : URLDecoder.decode(
                    raw.startsWith("query=") ? raw.substring("query=".length()) : raw, StandardCharsets.UTF_8);
            CALLS.add(new Rec("EXEC", "/exec", sql, auth(ex), null));
            Resp resp = SCRIPT.pollFirst();
            if (resp == null) {
                respond(ex, 500, "{\"error\":\"no scripted response\"}");
                return;
            }
            respond(ex, resp.status, resp.body);
        });
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

    private static String auth(HttpExchange ex) {
        return ex.getRequestHeaders().getFirst("Authorization");
    }

    private static String read(InputStream in) throws java.io.IOException {
        try (InputStream is = in) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void script(String... bodies) {
        for (String body : bodies) {
            SCRIPT.addLast(new Resp(200, body));
        }
    }

    private static void scriptStatus(int status, String body) {
        SCRIPT.addLast(new Resp(status, body));
    }

    private static void reset() {
        CALLS.clear();
        SCRIPT.clear();
        writeStatus = 204;
        writeBody = "";
    }

    private static List<Rec> execCalls() {
        synchronized (CALLS) {
            return CALLS.stream().filter(r -> "EXEC".equals(r.kind)).toList();
        }
    }

    private static List<Rec> writeCalls() {
        synchronized (CALLS) {
            return CALLS.stream().filter(r -> "WRITE".equals(r.kind)).toList();
        }
    }

    private record Rec(String kind, String path, String query, String auth, String body) {
    }

    private record Resp(int status, String body) {
    }

    // ==================== 装配 ====================

    static void configSuite() {
        EngineFunctional.emit("--- questdb:config (地址归一化与可用性) ---");
        String p = "config.";

        EngineFunctional.check(p + "engine() 标识 + url 必填", () -> {
            QuestdbSeriesEngine ok = new QuestdbSeriesEngine(baseUrl(), null, null, null);
            if (!"questdb".equals(ok.engine())) {
                throw new EngineFunctional.HarnessFailure("engine(): " + ok.engine());
            }
            if (!ok.isAvailable()) {
                throw new EngineFunctional.HarnessFailure("有效 url 应可用");
            }
            ok.close();

            if (new QuestdbSeriesEngine((String) null, null, null, null).isAvailable()
                    || new QuestdbSeriesEngine("   ", null, null, null).isAvailable()
                    || new QuestdbSeriesEngine(new Properties()).isAvailable()) {
                throw new EngineFunctional.HarnessFailure("url 缺失时不应可用");
            }
            return "questdb / url 必填";
        });

        EngineFunctional.check(p + "无 scheme 与尾部斜杠被归一化（仍能打到 /exec）", () -> {
            reset();
            script("{\"columns\":[{\"name\":\"timestamp\",\"type\":\"TIMESTAMP\"}],\"dataset\":[]}");
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(
                    "127.0.0.1:" + port + "///", null, null, null)) {
                if (!e.isAvailable()) {
                    throw new EngineFunctional.HarnessFailure("应可用");
                }
                e.metrics("cpu", 7L);
                Rec call = execCalls().get(0);
                if (!"/exec".equals(call.path)) {
                    throw new EngineFunctional.HarnessFailure("路径: " + call.path);
                }
                return "127.0.0.1:" + port + "/// -> /exec";
            }
        });

        EngineFunctional.check(p + "无法解析 host 时判定不可用且不抛异常", () -> {
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine("http://", null, null, null)) {
                if (e.isAvailable()) {
                    throw new EngineFunctional.HarnessFailure("应不可用");
                }
                return "不可用";
            }
        });
    }

    // ==================== 守卫 ====================

    static void guardSuite() {
        EngineFunctional.emit("--- questdb:guard (参数与白名单，调用前即拒绝) ---");
        String p = "guard.";

        EngineFunctional.check(p + "必填参数缺失被拒（零 HTTP）", () -> {
            reset();
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, null, null)) {
                expectType(IllegalArgumentException.class, () -> e.writePoint(null, 1L, "cpu", 1.0, 1L));
                expectType(IllegalArgumentException.class, () -> e.writePoint("cpu", null, "cpu", 1.0, 1L));
                expectType(IllegalArgumentException.class, () -> e.writePoint("cpu", 1L, null, 1.0, 1L));
                expectType(IllegalArgumentException.class, () -> e.metrics(null, 1L));
                expectType(IllegalArgumentException.class, () -> e.series(null, 1L, "cpu", 1, "5m"));
                expectType(IllegalArgumentException.class, () -> e.series("cpu", null, "cpu", 1, "5m"));
                expectType(IllegalArgumentException.class, () -> e.purge(null, 1L, "cpu", 1L));
                if (!CALLS.isEmpty()) {
                    throw new EngineFunctional.HarnessFailure("守卫失败后仍有 HTTP 调用: " + CALLS);
                }
                return "全部拒绝且零 HTTP";
            }
        });

        EngineFunctional.check(p + "非法标识符被白名单拒绝（零 HTTP）", () -> {
            reset();
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, null, null)) {
                for (String bad : new String[]{"a-b", "a'b", "a;drop", "a b", "", "a.b", "a\"b"}) {
                    expectType(IllegalArgumentException.class, () -> e.writePoint(bad, 1L, "cpu", 1.0, 1L));
                    expectType(IllegalArgumentException.class, () -> e.writePoint("cpu", 1L, bad, 1.0, 1L));
                    expectType(IllegalArgumentException.class, () -> e.metrics(bad, 1L));
                    expectType(IllegalArgumentException.class, () -> e.series(bad, 1L, "cpu", 1, "5m"));
                    expectType(IllegalArgumentException.class, () -> e.series("cpu", 1L, bad, 1, "5m"));
                    expectType(IllegalArgumentException.class, () -> e.purge(bad, 1L, "cpu", 1L));
                    expectType(IllegalArgumentException.class, () -> e.purge("cpu", 1L, bad, 1L));
                }
                if (!CALLS.isEmpty()) {
                    throw new EngineFunctional.HarnessFailure("守卫失败后仍有 HTTP 调用: " + CALLS);
                }
                return "拒绝且零 HTTP";
            }
        });

        EngineFunctional.check(p + "hours<=0 被拒（零 HTTP）", () -> {
            reset();
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, null, null)) {
                expectType(IllegalArgumentException.class, () -> e.series("cpu", 1L, "cpu", 0, "5m"));
                expectType(IllegalArgumentException.class, () -> e.series("cpu", 1L, "cpu", -3, "5m"));
                if (!CALLS.isEmpty()) {
                    throw new EngineFunctional.HarnessFailure("仍有 HTTP 调用");
                }
                return "拒绝 0 / -3";
            }
        });
    }

    // ==================== 写入 ====================

    static void writeSuite() {
        EngineFunctional.emit("--- questdb:write (真实 ILP 报文) ---");
        String p = "write.";

        EngineFunctional.check(p + "写入报文 = ILP（measurement/tag/column/纳秒时间戳）", () -> {
            reset();
            long ts = 1_700_000_000_123L;
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, null, null)) {
                e.writePoint("cpu", 7L, "cpu_usage", 12.5, ts);
            }
            List<Rec> writes = writeCalls();
            if (writes.size() != 1) {
                throw new EngineFunctional.HarnessFailure("POST /write 次数: " + writes.size());
            }
            Rec w = writes.get(0);
            String expected = "cpu,monitor_id=7 cpu_usage=12.5 " + (ts * 1_000_000L);
            String got = w.body.strip();
            if (!expected.equals(got)) {
                throw new EngineFunctional.HarnessFailure("ILP 报文: [" + got + "] 期望 [" + expected + "]");
            }
            String q = String.valueOf(w.query);
            if (!q.contains("precision=n")) {
                throw new EngineFunctional.HarnessFailure("precision 查询参数: " + q);
            }
            return got;
        });

        EngineFunctional.check(p + "每点同步 flush：两点产生两次 POST", () -> {
            reset();
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, null, null)) {
                e.writePoint("cpu", 7L, "cpu_usage", 1.0, 1_700_000_000_000L);
                e.writePoint("cpu", 7L, "cpu_usage", 2.0, 1_700_000_001_000L);
            }
            if (writeCalls().size() != 2) {
                throw new EngineFunctional.HarnessFailure("POST /write 次数: " + writeCalls().size());
            }
            return "2 点 2 次";
        });

        EngineFunctional.check(p + "写入失败必须抛出（不静默丢点），恢复后可继续写", () -> {
            reset();
            writeStatus = 500;
            writeBody = "boom";
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, null, null)) {
                try {
                    e.writePoint("cpu", 7L, "cpu_usage", 1.0, 1_700_000_000_000L);
                    throw new EngineFunctional.HarnessFailure("应抛 IllegalStateException");
                } catch (IllegalStateException ex) {
                    String msg = String.valueOf(ex.getMessage());
                    if (!msg.contains("cpu") || !msg.contains("cpu_usage")) {
                        throw new EngineFunctional.HarnessFailure("消息缺少上下文: " + msg);
                    }
                }
                writeStatus = 204;
                e.writePoint("cpu", 7L, "cpu_usage", 2.0, 1_700_000_001_000L);
                if (writeCalls().size() < 2) {
                    throw new EngineFunctional.HarnessFailure("恢复后未重新提交");
                }
                return "失败抛出且可恢复";
            }
        });

        EngineFunctional.check(p + "未配置/已关闭时写入抛 ISE", () -> {
            reset();
            QuestdbSeriesEngine off = new QuestdbSeriesEngine((String) null, null, null, null);
            expectType(IllegalStateException.class, () -> off.writePoint("cpu", 1L, "cpu", 1.0, 1L));
            QuestdbSeriesEngine closed = new QuestdbSeriesEngine(baseUrl(), null, null, null);
            closed.close();
            expectType(IllegalStateException.class, () -> closed.writePoint("cpu", 1L, "cpu", 1.0, 1L));
            if (!CALLS.isEmpty()) {
                throw new EngineFunctional.HarnessFailure("不应发生 HTTP 调用: " + CALLS);
            }
            return "ISE 且零 HTTP";
        });
    }

    // ==================== 鉴权 ====================

    static void authSuite() {
        EngineFunctional.emit("--- questdb:auth (写入与 /exec 必须携带同一份凭据) ---");
        String p = "auth.";

        EngineFunctional.check(p + "token：/write 与 /exec 都带 Bearer", () -> {
            reset();
            script("{\"columns\":[{\"name\":\"timestamp\",\"type\":\"TIMESTAMP\"}],\"dataset\":[]}");
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), "tok-123", null, null)) {
                e.writePoint("cpu", 7L, "cpu_usage", 1.0, 1_700_000_000_000L);
                e.metrics("cpu", 7L);
            }
            String w = String.valueOf(writeCalls().get(0).auth);
            String q = String.valueOf(execCalls().get(0).auth);
            if (!w.startsWith("Bearer ") || !w.contains("tok-123")) {
                throw new EngineFunctional.HarnessFailure("/write Authorization: " + w);
            }
            if (!"Bearer tok-123".equals(q)) {
                throw new EngineFunctional.HarnessFailure("/exec Authorization: " + q);
            }
            return "write=[" + w + "] exec=[" + q + "]";
        });

        EngineFunctional.check(p + "username/password：/exec 带 Basic（与写入一致）", () -> {
            reset();
            script("{\"columns\":[{\"name\":\"timestamp\",\"type\":\"TIMESTAMP\"}],\"dataset\":[]}");
            String expected = "Basic " + Base64.getEncoder()
                    .encodeToString("admin:s3cret".getBytes(StandardCharsets.UTF_8));
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, "admin", "s3cret")) {
                e.writePoint("cpu", 7L, "cpu_usage", 1.0, 1_700_000_000_000L);
                e.metrics("cpu", 7L);
            }
            String q = String.valueOf(execCalls().get(0).auth);
            if (!expected.equals(q)) {
                throw new EngineFunctional.HarnessFailure("/exec Authorization: " + q + " 期望 " + expected);
            }
            String w = String.valueOf(writeCalls().get(0).auth);
            if (!w.startsWith("Basic ")) {
                throw new EngineFunctional.HarnessFailure("/write Authorization: " + w);
            }
            return "exec=[" + q + "]";
        });

        EngineFunctional.check(p + "未配置凭据时不发 Authorization", () -> {
            reset();
            script("{\"columns\":[{\"name\":\"timestamp\",\"type\":\"TIMESTAMP\"}],\"dataset\":[]}");
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, null, null)) {
                e.metrics("cpu", 7L);
            }
            String q = execCalls().get(0).auth;
            if (q != null && !q.isEmpty()) {
                throw new EngineFunctional.HarnessFailure("不应带鉴权头: " + q);
            }
            return "none";
        });
    }

    // ==================== 指标发现 ====================

    static void metricsSuite() {
        EngineFunctional.emit("--- questdb:metrics (探测 SQL 与列类型收窄) ---");
        String p = "metrics.";

        EngineFunctional.check(p + "探测 SQL = SELECT * FROM \"t\" WHERE \"monitor_id\"='n' LIMIT 1", () -> {
            reset();
            script(TABLE_SAMPLE);
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, null, null)) {
                e.metrics("cpu", 7L);
            }
            String sql = execCalls().get(0).query;
            String expect = "SELECT * FROM \"cpu\" WHERE \"monitor_id\" = '7' LIMIT 1";
            if (!expect.equals(sql)) {
                throw new EngineFunctional.HarnessFailure("SQL: [" + sql + "]");
            }
            return sql;
        });

        EngineFunctional.check(p + "monitorId 为空时不加 WHERE", () -> {
            reset();
            script(TABLE_SAMPLE);
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, null, null)) {
                e.metrics("cpu", null);
            }
            String sql = execCalls().get(0).query;
            if (!"SELECT * FROM \"cpu\" LIMIT 1".equals(sql)) {
                throw new EngineFunctional.HarnessFailure("SQL: [" + sql + "]");
            }
            return sql;
        });

        EngineFunctional.check(p + "只报数值列，且按样本行剔除空值列，结果排序", () -> {
            reset();
            script(TABLE_SAMPLE);
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, null, null)) {
                List<String> one = e.metrics("cpu", 7L);
                if (!List.of("cpu_usage").equals(one)) {
                    throw new EngineFunctional.HarnessFailure("空值列未剔除: " + one);
                }
            }
            reset();
            script(TABLE_SAMPLE_FULL);
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, null, null)) {
                List<String> two = e.metrics("cpu", 7L);
                if (!List.of("cpu_usage", "mem_usage").equals(two)) {
                    throw new EngineFunctional.HarnessFailure("数值列/排序: " + two);
                }
            }
            return "[cpu_usage] / [cpu_usage, mem_usage]";
        });

        EngineFunctional.check(p + "表不存在按无数据处理（不抛异常）", () -> {
            reset();
            script("{\"error\":\"table does not exist\"}");
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, null, null)) {
                if (!e.metrics("cpu", 7L).isEmpty()) {
                    throw new EngineFunctional.HarnessFailure("应为空");
                }
            }
            return "empty";
        });

        EngineFunctional.check(p + "后端错误/非 JSON/空响应分别按契约处理", () -> {
            reset();
            scriptStatus(500, "{\"error\":\"boom\"}");
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, null, null)) {
                try {
                    e.metrics("cpu", 7L);
                    throw new EngineFunctional.HarnessFailure("500 应抛 ISE");
                } catch (IllegalStateException ex) {
                    if (!String.valueOf(ex.getMessage()).contains("boom")) {
                        throw new EngineFunctional.HarnessFailure("消息: " + ex.getMessage());
                    }
                }
            }
            reset();
            script("this-is-not-json");
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, null, null)) {
                expectType(IllegalStateException.class, () -> e.metrics("cpu", 7L));
            }
            reset();
            script("");
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, null, null)) {
                if (!e.metrics("cpu", 7L).isEmpty()) {
                    throw new EngineFunctional.HarnessFailure("空响应应为空结果");
                }
            }
            return "500->ISE / 非JSON->ISE / 空->empty";
        });
    }

    // ==================== 曲线 ====================

    static void seriesSuite() {
        EngineFunctional.emit("--- questdb:series (SQL 拼装、降采样、坏点丢弃) ---");
        String p = "series.";

        EngineFunctional.check(p + "显式指标：定位时间戳列后按窗口查询并降采样", () -> {
            long now = System.currentTimeMillis();
            long base = minuteStart(now) - 5 * 60_000L;
            reset();
            script(DESIGNATED_TS_COLUMN,
                    dataset(row(Instant.ofEpochMilli(base).toString(), 10.0),
                            row(Instant.ofEpochMilli(base + 1_000L).toString(), 20.0),
                            row(Instant.ofEpochMilli(base + 60_000L).toString(), 30.0)));
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, null, null)) {
                List<List<Object>> curve = e.series("cpu", 7L, "cpu_usage", 1, "1m");
                if (curve.size() != 2) {
                    throw new EngineFunctional.HarnessFailure("桶数: " + curve);
                }
                if (!eqNum(curve.get(0).get(0), base) || !eqNum(curve.get(0).get(1), 15.0)) {
                    throw new EngineFunctional.HarnessFailure("首桶: " + curve.get(0));
                }
                if (!eqNum(curve.get(1).get(0), base + 60_000L) || !eqNum(curve.get(1).get(1), 30.0)) {
                    throw new EngineFunctional.HarnessFailure("次桶: " + curve.get(1));
                }

                List<Rec> calls = execCalls();
                if (calls.size() != 2) {
                    throw new EngineFunctional.HarnessFailure("/exec 次数: " + calls.size());
                }
                if (!"SELECT * FROM \"cpu\" LIMIT 1".equals(calls.get(0).query)) {
                    throw new EngineFunctional.HarnessFailure("定位列 SQL: " + calls.get(0).query);
                }
                String sql = calls.get(1).query;
                String head = "SELECT \"ts\", \"cpu_usage\" FROM \"cpu\" WHERE \"monitor_id\" = '7' AND \"ts\" >= '";
                if (!sql.startsWith(head)) {
                    throw new EngineFunctional.HarnessFailure("查询 SQL 头: " + sql);
                }
                int sep = sql.indexOf("' AND \"ts\" <= '");
                if (sep < 0 || !sql.endsWith("'")) {
                    throw new EngineFunctional.HarnessFailure("查询 SQL 边界: " + sql);
                }
                long startBound = Instant.parse(
                        sql.substring(sql.indexOf(">= '") + 4, sep)).toEpochMilli();
                long endBound = Instant.parse(
                        sql.substring(sep + "' AND \"ts\" <= '".length(), sql.length() - 1)).toEpochMilli();
                if (endBound - startBound != 3_600_000L) {
                    throw new EngineFunctional.HarnessFailure("回溯窗口应为 1 小时: " + startBound + ".." + endBound);
                }
                if (Math.abs(endBound - System.currentTimeMillis()) > 60_000L) {
                    throw new EngineFunctional.HarnessFailure("上界应接近当前时间: " + endBound);
                }
                return curve.toString();
            }
        });

        EngineFunctional.check(p + "metricName 为空 + 唯一指标：自动推断后再查询", () -> {
            long now = System.currentTimeMillis();
            long base = minuteStart(now) - 3 * 60_000L;
            reset();
            script(SINGLE_METRIC_SAMPLE, DESIGNATED_TS_COLUMN,
                    dataset(row(Instant.ofEpochMilli(base).toString(), 42.0)));
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, null, null)) {
                List<List<Object>> curve = e.series("cpu", 7L, 1, "1m");
                if (curve.size() != 1 || !eqNum(curve.get(0).get(1), 42.0)) {
                    throw new EngineFunctional.HarnessFailure("曲线: " + curve);
                }
                if (execCalls().size() != 3) {
                    throw new EngineFunctional.HarnessFailure("/exec 次数: " + execCalls().size());
                }
                return "自动推断 cpu_usage";
            }
        });

        EngineFunctional.check(p + "metricName 为空 + 多指标：报错并列出候选，不再发查询", () -> {
            reset();
            script(TABLE_SAMPLE_FULL);
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, null, null)) {
                try {
                    e.series("cpu", 7L, 1, "1m");
                    throw new EngineFunctional.HarnessFailure("应抛 IllegalArgumentException");
                } catch (IllegalArgumentException ex) {
                    String msg = String.valueOf(ex.getMessage());
                    if (!msg.contains("cpu_usage") || !msg.contains("mem_usage")) {
                        throw new EngineFunctional.HarnessFailure("消息未列候选: " + msg);
                    }
                }
                if (execCalls().size() != 1) {
                    throw new EngineFunctional.HarnessFailure("歧义后仍发了查询: " + execCalls().size());
                }
                return "候选齐全且零多余查询";
            }
        });

        EngineFunctional.check(p + "坏点丢弃：时间不可解析/值非数值被剔除，其余保留", () -> {
            long now = System.currentTimeMillis();
            long base = minuteStart(now) - 4 * 60_000L;
            reset();
            script(DESIGNATED_TS_COLUMN,
                    dataset(row("not-a-time", 1.0),
                            row(Instant.ofEpochMilli(base).toString(), "text"),
                            row(Instant.ofEpochMilli(base).toString(), 7.5),
                            row(null, 9.0)));
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, null, null)) {
                List<List<Object>> curve = e.series("cpu", 7L, "cpu_usage", 1, "1m");
                if (curve.size() != 1 || !eqNum(curve.get(0).get(1), 7.5)) {
                    throw new EngineFunctional.HarnessFailure("曲线: " + curve);
                }
                return "4 行仅留 1 行";
            }
        });

        EngineFunctional.check(p + "时间戳为 epoch 微秒时按 /1000 归一到毫秒", () -> {
            long now = System.currentTimeMillis();
            long base = minuteStart(now) - 6 * 60_000L;
            reset();
            script(DESIGNATED_TS_COLUMN,
                    dataset(row(base * 1_000L, 5.0)));
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, null, null)) {
                List<List<Object>> curve = e.series("cpu", 7L, "cpu_usage", 1, "1m");
                if (curve.size() != 1 || !eqNum(curve.get(0).get(0), base)) {
                    throw new EngineFunctional.HarnessFailure("曲线: " + curve);
                }
                return "微秒 -> " + base;
            }
        });

        EngineFunctional.check(p + "定位不到时间戳列时退回默认列名 timestamp", () -> {
            long now = System.currentTimeMillis();
            long base = minuteStart(now) - 2 * 60_000L;
            reset();
            script(NO_TS_COLUMN,
                    dataset(row(Instant.ofEpochMilli(base).toString(), 3.0)));
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, null, null)) {
                e.series("cpu", 7L, "cpu_usage", 1, "1m");
                String sql = execCalls().get(1).query;
                if (!sql.startsWith("SELECT \"timestamp\", \"cpu_usage\"")) {
                    throw new EngineFunctional.HarnessFailure("SQL: " + sql);
                }
                return "回退 timestamp";
            }
        });

        EngineFunctional.check(p + "表不存在返回空曲线", () -> {
            reset();
            // 先定位时间戳列（表不存在→回退默认列名），再执行主查询（表不存在→空）
            script("{\"error\":\"table does not exist\"}", "{\"error\":\"table does not exist\"}");
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, null, null)) {
                if (!e.series("cpu", 7L, "cpu_usage", 1, "1m").isEmpty()) {
                    throw new EngineFunctional.HarnessFailure("应为空曲线");
                }
            }
            return "empty";
        });
    }

    // ==================== 清理 ====================

    static void purgeSuite() {
        EngineFunctional.emit("--- questdb:purge (DELETE 语义) ---");
        String p = "purge.";

        EngineFunctional.check(p + "带 monitorId：DELETE 按时间与 tag 过滤，返回 -1", () -> {
            reset();
            script(DESIGNATED_TS_COLUMN, "{}");
            long before = 1_700_000_000_000L;
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, null, null)) {
                int n = e.purge("cpu", 7L, null, before);
                if (n != -1) {
                    throw new EngineFunctional.HarnessFailure("返回值: " + n);
                }
                String sql = execCalls().get(1).query;
                String expect = "DELETE FROM \"cpu\" WHERE \"ts\" < '"
                        + Instant.ofEpochMilli(before) + "' AND \"monitor_id\" = '7'";
                if (!expect.equals(sql)) {
                    throw new EngineFunctional.HarnessFailure("SQL: [" + sql + "]");
                }
                return "-1 / " + sql;
            }
        });

        EngineFunctional.check(p + "monitorId 为空：DELETE 不带 tag 过滤", () -> {
            reset();
            script(DESIGNATED_TS_COLUMN, "{}");
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, null, null)) {
                e.purge("cpu", null, null, 1_700_000_000_000L);
                String sql = execCalls().get(1).query;
                if (sql.contains("monitor_id")) {
                    throw new EngineFunctional.HarnessFailure("SQL: [" + sql + "]");
                }
                return sql;
            }
        });

        EngineFunctional.check(p + "表不存在返回 0（无数据可清理）", () -> {
            reset();
            // 先定位时间戳列，再执行 DELETE，两步都会撞上「表不存在」
            script("{\"error\":\"table does not exist\"}", "{\"error\":\"table does not exist\"}");
            try (QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, null, null)) {
                if (e.purge("cpu", 7L, "cpu_usage", 1_700_000_000_000L) != 0) {
                    throw new EngineFunctional.HarnessFailure("应为 0");
                }
            }
            return "0";
        });

        EngineFunctional.check(p + "未配置/已关闭返回 0 且零 HTTP", () -> {
            reset();
            QuestdbSeriesEngine off = new QuestdbSeriesEngine((String) null, null, null, null);
            if (off.purge("cpu", 7L, null, 1L) != 0) {
                throw new EngineFunctional.HarnessFailure("未配置应返回 0");
            }
            QuestdbSeriesEngine closed = new QuestdbSeriesEngine(baseUrl(), null, null, null);
            closed.close();
            if (closed.purge("cpu", 7L, null, 1L) != 0) {
                throw new EngineFunctional.HarnessFailure("已关闭应返回 0");
            }
            if (!CALLS.isEmpty()) {
                throw new EngineFunctional.HarnessFailure("不应发生 HTTP 调用");
            }
            return "0 / 0 / 零 HTTP";
        });
    }

    // ==================== 生命周期 ====================

    static void lifecycleSuite() {
        EngineFunctional.emit("--- questdb:lifecycle (关闭语义) ---");
        String p = "lifecycle.";

        EngineFunctional.check(p + "close 幂等，关闭后写拒绝、读空、清理 0、零 HTTP", () -> {
            reset();
            QuestdbSeriesEngine e = new QuestdbSeriesEngine(baseUrl(), null, null, null);
            if (!e.isAvailable()) {
                throw new EngineFunctional.HarnessFailure("初始应可用");
            }
            e.close();
            if (e.isAvailable()) {
                throw new EngineFunctional.HarnessFailure("关闭后应不可用");
            }
            e.close();
            expectType(IllegalStateException.class,
                    () -> e.writePoint("cpu", 1L, "cpu_usage", 1.0, 1L));
            if (!e.metrics("cpu", 1L).isEmpty()) {
                throw new EngineFunctional.HarnessFailure("metrics 应为空");
            }
            if (!e.series("cpu", 1L, "cpu_usage", 1, "5m").isEmpty()) {
                throw new EngineFunctional.HarnessFailure("series 应为空");
            }
            if (e.purge("cpu", 1L, null, 1L) != 0) {
                throw new EngineFunctional.HarnessFailure("purge 应为 0");
            }
            if (!CALLS.isEmpty()) {
                throw new EngineFunctional.HarnessFailure("关闭后仍发生 HTTP: " + CALLS);
            }
            return "closed + idempotent + 零 HTTP";
        });
    }

    // ==================== 固定响应 ====================

    /** 探测样本：mem_usage 在样本行里为 null（该 monitor 从未写过该列） */
    private static final String TABLE_SAMPLE = """
            {"columns":[
              {"name":"timestamp","type":"TIMESTAMP"},
              {"name":"monitor_id","type":"SYMBOL"},
              {"name":"cpu_usage","type":"DOUBLE"},
              {"name":"mem_usage","type":"DOUBLE"},
              {"name":"note","type":"VARCHAR"}],
             "timestamp":0,
             "dataset":[["2026-01-01T00:00:00.000000Z","7",12.5,null,"x"]]}""";

    private static final String TABLE_SAMPLE_FULL = """
            {"columns":[
              {"name":"timestamp","type":"TIMESTAMP"},
              {"name":"monitor_id","type":"SYMBOL"},
              {"name":"mem_usage","type":"DOUBLE"},
              {"name":"cpu_usage","type":"DOUBLE"}],
             "timestamp":0,
             "dataset":[["2026-01-01T00:00:00.000000Z","7",30.0,12.5]]}""";

    private static final String SINGLE_METRIC_SAMPLE = """
            {"columns":[
              {"name":"timestamp","type":"TIMESTAMP"},
              {"name":"monitor_id","type":"SYMBOL"},
              {"name":"cpu_usage","type":"DOUBLE"}],
             "timestamp":0,
             "dataset":[["2026-01-01T00:00:00.000000Z","7",12.5]]}""";

    /** 设计时间戳列在下标 0，名为 ts */
    private static final String DESIGNATED_TS_COLUMN = """
            {"columns":[
              {"name":"monitor_id","type":"SYMBOL"},
              {"name":"ts","type":"TIMESTAMP"},
              {"name":"cpu_usage","type":"DOUBLE"}],
             "timestamp":1,
             "dataset":[]}""";

    /** 既无 designatd 下标也无 TIMESTAMP 列 → 回退默认名 */
    private static final String NO_TS_COLUMN = """
            {"columns":[{"name":"cpu_usage","type":"DOUBLE"}],
             "timestamp":9,
             "dataset":[]}""";

    private static String row(Object... cells) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            Object cell = cells[i];
            if (cell == null) {
                sb.append("null");
            } else if (cell instanceof Number) {
                sb.append(cell);
            } else {
                sb.append('"').append(cell).append('"');
            }
        }
        return sb.append(']').toString();
    }

    private static String dataset(String... rows) {
        return "{\"columns\":[{\"name\":\"ts\",\"type\":\"TIMESTAMP\"},"
                + "{\"name\":\"cpu_usage\",\"type\":\"DOUBLE\"}],"
                + "\"timestamp\":0,\"dataset\":[" + String.join(",", rows) + "]}";
    }

    // ==================== 辅助 ====================

    private static long minuteStart(long millis) {
        return (millis / 60_000L) * 60_000L;
    }

    private static boolean eqNum(Object actual, long expected) {
        return actual instanceof Number n && n.longValue() == expected;
    }

    private static boolean eqNum(Object actual, double expected) {
        return actual instanceof Number n && Math.abs(n.doubleValue() - expected) < 1e-9;
    }

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

    private static void runQuietly(String name, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            EngineFunctional.recordFail(name + " 套件异常退出: " + EngineFunctional.chain(t));
        }
    }
}
