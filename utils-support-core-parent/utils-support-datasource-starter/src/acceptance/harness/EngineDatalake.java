import com.chua.common.support.lang.datasource.dialect.Pagination;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.datalake.support.engine.HttpDatalakeQueryEngine;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 引擎验收运行器 —— 阶段十：HttpDatalakeQueryEngine（本机 HTTP 桩，真实读写）。
 *
 * <p>阶段六里该引擎只做了只读契约面，理由是「其 {@code querySql}/{@code executeSql}
 * 需要数据湖查询服务，本机无该服务」。复核后确认它的协议面极窄：把 SQL 以
 * {@code text/plain; charset=utf-8} 作为请求体 {@code POST {baseUrl}/query}，
 * 响应体原样返回。因此用 JDK 自带 {@code HttpServer} 起桩即可真实跑通全链路。</p>
 *
 * <p>覆盖：请求方法/路径/Content-Type/请求体编码、JSON 行解析与类型映射、
 * 客户端分页切片、受影响行数解析、错误响应、非数组/非对象/空响应的拒绝，
 * 以及只读入口与关闭语义。桩只回放响应体，解析与判定全部由被测代码完成。</p>
 */
public final class EngineDatalake {

    /** 桩服务。 */
    private static HttpServer server;

    /** 桩服务端口。 */
    private static int port;

    /** 响应模式。 */
    private static final AtomicReference<String> MODE = new AtomicReference<>("rows");

    /** 桩收到的方法。 */
    private static final AtomicReference<String> METHOD = new AtomicReference<>();

    /** 桩收到的路径。 */
    private static final AtomicReference<String> PATH = new AtomicReference<>();

    /** 桩收到的 Content-Type。 */
    private static final AtomicReference<String> CONTENT_TYPE = new AtomicReference<>();

    /** 桩收到的请求体。 */
    private static final AtomicReference<String> BODY = new AtomicReference<>();

    private EngineDatalake() {
    }

    public static void main(String[] args) {
        EngineFunctional.emit("=".repeat(78));
        EngineFunctional.emit("  引擎验收 阶段十：HttpDatalakeQueryEngine（本机 HTTP 桩）");
        EngineFunctional.emit("=".repeat(78));

        try {
            startStub();
        } catch (Throwable t) {
            EngineFunctional.recordFail("无法启动本地 HTTP 桩: " + EngineFunctional.chain(t));
            EngineFunctional.finishAndExit("EngineDatalake-report.txt");
            return;
        }
        try {
            runQuietly("contract", EngineDatalake::contractSuite);
            runQuietly("query", EngineDatalake::querySuite);
            runQuietly("paging", EngineDatalake::pagingSuite);
            runQuietly("execute", EngineDatalake::executeSuite);
            runQuietly("lifecycle", EngineDatalake::lifecycleSuite);
        } finally {
            stopStub();
        }

        EngineFunctional.finishAndExit("EngineDatalake-report.txt");
    }

    // ==================== 桩 ====================

    private static String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private static void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/query", ex -> {
            METHOD.set(ex.getRequestMethod());
            PATH.set(ex.getRequestURI().getPath());
            CONTENT_TYPE.set(ex.getRequestHeaders().getFirst("Content-Type"));
            BODY.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            switch (MODE.get()) {
                case "http500" -> respond(ex, 500, "boom");
                case "notjson" -> respond(ex, 200, "this-is-not-json");
                case "notarray" -> respond(ex, 200, "{\"a\":1}");
                case "nonobject" -> respond(ex, 200, "[1,2]");
                case "empty" -> respond(ex, 200, "");
                case "affected" -> respond(ex, 200, "3");
                case "typed" -> respond(ex, 200,
                        "[{\"i\":42,\"d\":1.5,\"b\":true,\"s\":\"x\",\"n\":null}]");
                default -> respond(ex, 200,
                        "[{\"name\":\"alpha\",\"qty\":1},{\"name\":\"beta\",\"qty\":2}]");
            }
        });
        server.start();
        port = server.getAddress().getPort();
        EngineFunctional.emit("  [stub] 已启动 127.0.0.1:" + port + "/query");
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

    private static void reset(String mode) {
        MODE.set(mode);
        METHOD.set(null);
        PATH.set(null);
        CONTENT_TYPE.set(null);
        BODY.set(null);
    }

    private static HttpDatalakeQueryEngine engine() {
        return new HttpDatalakeQueryEngine(baseUrl());
    }

    // ==================== 1. 只读契约与装配 ====================

    static void contractSuite() {
        EngineFunctional.emit("--- datalake:contract (只读契约与装配) ---");
        String p = "contract.";

        EngineFunctional.check(p + "装配：默认数据源名、执行器、无方言/无数据源", () -> {
            reset("rows");
            HttpDatalakeQueryEngine e = engine();
            if (!"datalake".equals(e.getDefaultDataSourceName())) {
                throw new EngineFunctional.HarnessFailure("默认数据源名: " + e.getDefaultDataSourceName());
            }
            if (e.getExecutor() == null || e.getExecutor("datalake") == null) {
                throw new EngineFunctional.HarnessFailure("执行器不应为 null");
            }
            if (e.getDialect("datalake") != null) {
                throw new EngineFunctional.HarnessFailure("方言应为 null");
            }
            if (e.getDataSource() != null || e.getDataSource("datalake") != null) {
                throw new EngineFunctional.HarnessFailure("数据源应为 null（远程只读）");
            }
            if (e.supportsMeta()) {
                throw new EngineFunctional.HarnessFailure("supportsMeta 应为 false");
            }
            return "datalake / executor 可用 / 无方言";
        });

        EngineFunctional.check(p + "只读入口一律 UnsupportedOperationException", () -> {
            reset("rows");
            HttpDatalakeQueryEngine e = engine();
            expectType(UnsupportedOperationException.class, () -> e.store("t", List.of()));
            expectType(UnsupportedOperationException.class, () -> e.query(Object.class));
            expectType(UnsupportedOperationException.class, () -> e.update(Object.class));
            expectType(UnsupportedOperationException.class, () -> e.delete(Object.class));
            expectType(UnsupportedOperationException.class, () -> e.meta());
            expectType(UnsupportedOperationException.class,
                    () -> e.addDataSource("x", null));
            return "5 个入口全部 UOE";
        });

        EngineFunctional.check(p + "setDefaultDataSourceName 为无操作且返回自身", () -> {
            reset("rows");
            HttpDatalakeQueryEngine e = engine();
            Engine returned = e.setDefaultDataSourceName("other");
            if (returned != e) {
                throw new EngineFunctional.HarnessFailure("应返回自身");
            }
            if (!"datalake".equals(e.getDefaultDataSourceName())) {
                throw new EngineFunctional.HarnessFailure("默认名不应被改写: " + e.getDefaultDataSourceName());
            }
            return "no-op";
        });

        EngineFunctional.check(p + "baseUrl 校验：空/非 http/无 scheme/非法", () -> {
            expectType(IllegalArgumentException.class, () -> new HttpDatalakeQueryEngine(null));
            expectType(IllegalArgumentException.class, () -> new HttpDatalakeQueryEngine(""));
            expectType(IllegalArgumentException.class, () -> new HttpDatalakeQueryEngine("   "));
            expectType(IllegalArgumentException.class, () -> new HttpDatalakeQueryEngine("ftp://127.0.0.1:1"));
            expectType(IllegalArgumentException.class, () -> new HttpDatalakeQueryEngine("127.0.0.1:8700"));
            expectType(IllegalArgumentException.class, () -> new HttpDatalakeQueryEngine("http://a b"));
            return "全部拒绝";
        });
    }

    // ==================== 2. 查询 ====================

    static void querySuite() {
        EngineFunctional.emit("--- datalake:query (行解析与请求契约) ---");
        String p = "query.";

        EngineFunctional.check(p + "POST /query，SQL 以 text/plain UTF-8 作为请求体", () -> {
            reset("rows");
            HttpDatalakeQueryEngine e = engine();
            String sql = "SELECT id, 名称 FROM 监控表 WHERE id = 7";
            List<Map<String, Object>> rows = e.getExecutor().query(sql);
            if (!"POST".equals(METHOD.get())) {
                throw new EngineFunctional.HarnessFailure("方法: " + METHOD.get());
            }
            if (!"/query".equals(PATH.get())) {
                throw new EngineFunctional.HarnessFailure("路径: " + PATH.get());
            }
            if (CONTENT_TYPE.get() == null || !CONTENT_TYPE.get().toLowerCase().contains("text/plain")) {
                throw new EngineFunctional.HarnessFailure("Content-Type: " + CONTENT_TYPE.get());
            }
            if (!sql.equals(BODY.get())) {
                throw new EngineFunctional.HarnessFailure("请求体应按 UTF-8 原样传输，实际: " + BODY.get());
            }
            if (rows.size() != 2 || !"alpha".equals(rows.get(0).get("name"))
                    || !"beta".equals(rows.get(1).get("name"))) {
                throw new EngineFunctional.HarnessFailure("行内容/顺序错误: " + rows);
            }
            return "POST /query, 2 行, UTF-8 原样";
        });

        EngineFunctional.check(p + "JSON 标量类型映射（int/double/bool/string/null）", () -> {
            reset("typed");
            List<Map<String, Object>> rows = engine().getExecutor().query("SELECT 1");
            Map<String, Object> row = rows.get(0);
            if (!(row.get("i") instanceof Integer) || !Integer.valueOf(42).equals(row.get("i"))) {
                throw new EngineFunctional.HarnessFailure("i: " + row.get("i"));
            }
            if (!(row.get("d") instanceof Double) || !Double.valueOf(1.5).equals(row.get("d"))) {
                throw new EngineFunctional.HarnessFailure("d: " + row.get("d"));
            }
            if (!Boolean.TRUE.equals(row.get("b"))) {
                throw new EngineFunctional.HarnessFailure("b: " + row.get("b"));
            }
            if (!"x".equals(row.get("s"))) {
                throw new EngineFunctional.HarnessFailure("s: " + row.get("s"));
            }
            if (!row.containsKey("n") || row.get("n") != null) {
                throw new EngineFunctional.HarnessFailure("n 应存在且为 null: " + row);
            }
            return row.toString();
        });

        EngineFunctional.check(p + "query(Class) 映射为 POJO", () -> {
            reset("rows");
            List<Row> rows = engine().getExecutor().query("SELECT * FROM t", Row.class);
            if (rows.size() != 2 || !"alpha".equals(rows.get(0).getName()) || rows.get(0).getQty() != 1) {
                throw new EngineFunctional.HarnessFailure("POJO 映射错误: " + rows.get(0));
            }
            if (!"beta".equals(rows.get(1).getName()) || rows.get(1).getQty() != 2) {
                throw new EngineFunctional.HarnessFailure("POJO 映射错误: " + rows.get(1));
            }
            return "2 个 POJO";
        });

        EngineFunctional.check(p + "带绑定参数被拒绝（端点无参数通道，禁止静默丢弃）", () -> {
            reset("rows");
            SqlExecutor exec = engine().getExecutor();
            expectType(UnsupportedOperationException.class, () -> exec.query("SELECT * FROM t WHERE id = ?", 7));
            expectType(UnsupportedOperationException.class,
                    () -> exec.query("SELECT * FROM t WHERE id = ?", Row.class, 7));
            expectType(UnsupportedOperationException.class, () -> exec.execute("DELETE FROM t WHERE id = ?", 7));
            return "3 个入口全部 UOE";
        });

        EngineFunctional.check(p + "空 SQL 被拒", () -> {
            reset("rows");
            SqlExecutor exec = engine().getExecutor();
            expectType(IllegalArgumentException.class, () -> exec.query(""));
            expectType(IllegalArgumentException.class, () -> exec.query((String) null));
            return "已拒绝";
        });

        EngineFunctional.check(p + "HTTP 500 转 IllegalStateException", () -> {
            reset("http500");
            expectType(IllegalStateException.class, () -> engine().getExecutor().query("SELECT 1"));
            return "IllegalStateException";
        });

        EngineFunctional.check(p + "非 JSON / 非数组 / 数组含非对象 / 空响应 全部拒绝", () -> {
            SqlExecutor exec = engine().getExecutor();
            reset("notjson");
            expectType(IllegalArgumentException.class, () -> exec.query("SELECT 1"));
            reset("notarray");
            expectType(IllegalArgumentException.class, () -> exec.query("SELECT 1"));
            reset("nonobject");
            expectType(IllegalArgumentException.class, () -> exec.query("SELECT 1"));
            reset("empty");
            expectType(IllegalArgumentException.class, () -> exec.query("SELECT 1"));
            return "4 种畸形响应均拒绝";
        });

        EngineFunctional.check(p + "baseUrl 尾部斜杠被规整（不产生 //query）", () -> {
            reset("rows");
            HttpDatalakeQueryEngine e = new HttpDatalakeQueryEngine(baseUrl() + "///");
            List<Map<String, Object>> rows = e.getExecutor().query("SELECT 1");
            if (!"/query".equals(PATH.get())) {
                throw new EngineFunctional.HarnessFailure("路径: " + PATH.get());
            }
            if (rows.size() != 2) {
                throw new EngineFunctional.HarnessFailure("行数: " + rows.size());
            }
            return "path=" + PATH.get();
        });
    }

    // ==================== 3. 客户端分页 ====================

    static void pagingSuite() {
        EngineFunctional.emit("--- datalake:paging (客户端切片) ---");
        String p = "paging.";

        EngineFunctional.check(p + "按页切片并回填 total（整表已取回）", () -> {
            SqlExecutor exec = engine().getExecutor();
            reset("rows");
            Pagination first = new Pagination().setPageNum(1).setPageSize(1);
            List<Map<String, Object>> page1 = exec.queryPage("SELECT 1", first);
            if (page1.size() != 1 || !"alpha".equals(page1.get(0).get("name"))) {
                throw new EngineFunctional.HarnessFailure("第 1 页: " + page1);
            }
            if (first.getTotal() != 2) {
                throw new EngineFunctional.HarnessFailure("total: " + first.getTotal());
            }
            Pagination second = new Pagination().setPageNum(2).setPageSize(1);
            List<Map<String, Object>> page2 = exec.queryPage("SELECT 1", second);
            if (page2.size() != 1 || !"beta".equals(page2.get(0).get("name"))) {
                throw new EngineFunctional.HarnessFailure("第 2 页: " + page2);
            }
            if (second.getTotal() != 2) {
                throw new EngineFunctional.HarnessFailure("total: " + second.getTotal());
            }
            return "1/2 页正确, total=2";
        });

        EngineFunctional.check(p + "越界页为空、pageSize=0 为空，total 仍为总数", () -> {
            SqlExecutor exec = engine().getExecutor();
            reset("rows");
            Pagination beyond = new Pagination().setPageNum(9).setPageSize(2);
            if (!exec.queryPage("SELECT 1", beyond).isEmpty()) {
                throw new EngineFunctional.HarnessFailure("越界页应为空");
            }
            if (beyond.getTotal() != 2) {
                throw new EngineFunctional.HarnessFailure("total: " + beyond.getTotal());
            }
            Pagination zero = new Pagination().setPageNum(1).setPageSize(0);
            if (!exec.queryPage("SELECT 1", zero).isEmpty()) {
                throw new EngineFunctional.HarnessFailure("pageSize=0 应为空");
            }
            if (zero.getTotal() != 2) {
                throw new EngineFunctional.HarnessFailure("total: " + zero.getTotal());
            }
            return "边界正确";
        });

        EngineFunctional.check(p + "pagination 为 null 时返回整表", () -> {
            reset("rows");
            List<Map<String, Object>> rows = engine().getExecutor().queryPage("SELECT 1", null);
            if (rows.size() != 2) {
                throw new EngineFunctional.HarnessFailure("行数: " + rows.size());
            }
            return "整表 2 行";
        });
    }

    // ==================== 4. execute ====================

    static void executeSuite() {
        EngineFunctional.emit("--- datalake:execute (受影响行数) ---");
        String p = "execute.";

        EngineFunctional.check(p + "execute 解析受影响行数", () -> {
            reset("affected");
            int n = engine().getExecutor().execute("DELETE FROM t WHERE id = 7");
            if (n != 3) {
                throw new EngineFunctional.HarnessFailure("受影响行数: " + n);
            }
            if (!"DELETE FROM t WHERE id = 7".equals(BODY.get())) {
                throw new EngineFunctional.HarnessFailure("请求体: " + BODY.get());
            }
            return "3";
        });

        EngineFunctional.check(p + "非数字响应不得当作 0 行", () -> {
            reset("rows");
            expectType(IllegalStateException.class,
                    () -> engine().getExecutor().execute("DELETE FROM t"));
            return "IllegalStateException";
        });

        EngineFunctional.check(p + "execute 遇 HTTP 500 转 IllegalStateException", () -> {
            reset("http500");
            expectType(IllegalStateException.class,
                    () -> engine().getExecutor().execute("DELETE FROM t"));
            return "IllegalStateException";
        });

        EngineFunctional.check(p + "batch 明确不支持", () -> {
            reset("rows");
            expectType(UnsupportedOperationException.class,
                    () -> engine().getExecutor().batch("INSERT INTO t VALUES (?)", List.of()));
            return "UnsupportedOperationException";
        });
    }

    // ==================== 5. 生命周期 ====================

    static void lifecycleSuite() {
        EngineFunctional.emit("--- datalake:lifecycle (关闭语义) ---");
        String p = "lifecycle.";

        EngineFunctional.check(p + "close 幂等且 isClosed 反映真实状态", () -> {
            reset("rows");
            HttpDatalakeQueryEngine e = engine();
            if (e.isClosed()) {
                throw new EngineFunctional.HarnessFailure("初始应为未关闭");
            }
            e.close();
            if (!e.isClosed()) {
                throw new EngineFunctional.HarnessFailure("close 后 isClosed 应为 true");
            }
            e.close();
            if (!e.isClosed()) {
                throw new EngineFunctional.HarnessFailure("重复 close 不应改变状态");
            }
            return "closed+idempotent";
        });

        EngineFunctional.check(p + "关闭后执行器仍可调用（已知行为差异，见报告）", () -> {
            reset("rows");
            HttpDatalakeQueryEngine e = engine();
            e.close();
            List<Map<String, Object>> rows = e.getExecutor().query("SELECT 1");
            if (rows.size() != 2) {
                throw new EngineFunctional.HarnessFailure("行数: " + rows.size());
            }
            return "仍返回 2 行（底部无资源可释放，属已记录差异）";
        });
    }

    // ==================== 辅助 ====================

    /** 用于 POJO 映射断言的简单实体。 */
    public static class Row {
        private String name;
        private int qty;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getQty() {
            return qty;
        }

        public void setQty(int qty) {
            this.qty = qty;
        }

        @Override
        public String toString() {
            return "Row{name=" + name + ", qty=" + qty + '}';
        }
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
