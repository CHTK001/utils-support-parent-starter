package com.chua.datalake.support;

import com.chua.common.support.lang.datasource.dialect.Pagination;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.datalake.support.client.DatalakeHttpClient;
import com.chua.datalake.support.client.DefaultDatalakeHttpClient;
import com.chua.datalake.support.engine.HttpDatalakeQueryEngine;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 查询客户端与查询引擎冒烟测试。
 *
 * <p>用一个内置 HTTP 服务假扮 数据湖服务端 {@code /query} 端点，逐段验证：</p>
 * <ul>
 *   <li>地址、SQL、HTTP 状态码的入参与出参校验不会静默放过</li>
 *   <li>响应体到行集合的解析：正常数组、错误页、对象包裹、畸形 JSON</li>
 *   <li>绑定参数没有传输通道时必须拒绝，而不是当作"按参数查过"</li>
 *   <li>{@code execute} 的受影响行数与 {@code queryPage} 的分页切片</li>
 *   <li>只读引擎的写入口显式抛异常</li>
 * </ul>
 *
 * <p>遵循项目约定使用 {@code main} 方法直接运行（本模块无 JUnit 依赖）：</p>
 * <pre>
 * 运行方式：{@code java com.chua.datalake.support.DatalakeQueryClientSmokeTest}
 * 任一校验失败输出 FAIL 并以退出码 1 结束，全部通过输出 PASS。
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DatalakeQueryClientSmokeTest {

    /**
     * 两行中文结果集
     */
    private static final String ROWS_JSON = "[{\"id\":1,\"name\":\"张三\"},{\"id\":2,\"name\":\"李四\"}]";

    /**
     * 失败计数
     */
    private static int failureCount = 0;
    /**
     * 成功计数
     */
    private static int passCount = 0;

    /**
     * 假扮服务端的响应状态码
     */
    private static volatile int status = 200;
    /**
     * 假扮服务端的响应体
     */
    private static volatile String body = "[]";
    /**
     * 服务端收到的请求数
     */
    private static volatile int requestCount = 0;
    /**
     * 服务端收到的最后一个请求体
     */
    private static volatile String lastBody;
    /**
     * 服务端收到的最后一个 Content-Type
     */
    private static volatile String lastContentType;

    /**
     * main。
     *
     * @param args 参数
     * @throws Exception 启动内置服务失败
     */
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/query", exchange -> {
            String sql = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (DatalakeQueryClientSmokeTest.class) {
                requestCount++;
                lastBody = sql;
                lastContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port;
        try {
            baseUrlIsValidate(baseUrl);
            httpErrorIsNotResult(baseUrl);
            emptySqlNeverReachesServer(baseUrl);
            transportCarriesUtf8(baseUrl);
            rowsAreParsed(baseUrl);
            bindingArgsAreRejected(baseUrl);
            parseRowsContract();
            executeReportsAffectedRows(baseUrl);
            queryPageSlicesAndCounts(baseUrl);
            readOnlySurfaceThrows(baseUrl);
            typedQueryMapsRows(baseUrl);
        } finally {
            server.stop(0);
        }
        summary();
    }

    /**
     * 场景 1：服务地址必须可用才允许构造客户端。
     */
    private static void baseUrlIsValidate(String baseUrl) {
        check(throwsType(IllegalArgumentException.class, () -> new DatalakeHttpClient(null)),
                "baseUrl=null 抛 IllegalArgumentException");
        check(throwsType(IllegalArgumentException.class, () -> new DatalakeHttpClient("   ")),
                "baseUrl=空白串抛 IllegalArgumentException");
        check(throwsType(IllegalArgumentException.class, () -> new DefaultDatalakeHttpClient("127.0.0.1:8700")),
                "baseUrl=无 scheme 抛 IllegalArgumentException");
        check(throwsType(IllegalArgumentException.class, () -> new DefaultDatalakeHttpClient("ftp://127.0.0.1")),
                "baseUrl=非 http/https 抛 IllegalArgumentException");
        check(throwsNone(() -> new DefaultDatalakeHttpClient(baseUrl + "/")),
                "baseUrl=合法地址（含尾斜杠）构造成功");
    }

    /**
     * 场景 2：非 2xx 响应不能被当成查询结果返回。
     */
    private static void httpErrorIsNotResult(String baseUrl) {
        status = 500;
        body = "<html><body>Internal Server Error</body></html>";
        check(throwsType(java.io.IOException.class, () -> new DatalakeHttpClient(baseUrl).query("SELECT 1")),
                "服务端 500 抛 IOException，不把错误页当结果");
        status = 200;
        body = "[]";
    }

    /**
     * 场景 3：空 SQL 在本地拒绝，不产生网络请求。
     */
    private static void emptySqlNeverReachesServer(String baseUrl) {
        int before = requestCount;
        check(throwsType(IllegalArgumentException.class, () -> new DatalakeHttpClient(baseUrl).query("")),
                "空 SQL 抛 IllegalArgumentException");
        check(requestCount - before == 0, "空 SQL 未向服务端发出请求");
    }

    /**
     * 场景 4：SQL 以 UTF-8 文本作为请求体传输，中文不丢。
     */
    private static void transportCarriesUtf8(String baseUrl) {
        status = 200;
        body = "[]";
        String sql = "SELECT '数据湖' AS tag";
        runQuietly(() -> new DatalakeHttpClient(baseUrl).query(sql));
        check(lastContentType != null && lastContentType.toLowerCase().contains("charset=utf-8"),
                "Content-Type 声明 charset=utf-8, 实际=" + lastContentType);
        check(sql.equals(lastBody), "中文 SQL 往返一致");
    }

    /**
     * 场景 5：查询响应解析为行集合。
     */
    private static void rowsAreParsed(String baseUrl) {
        status = 200;
        body = ROWS_JSON;
        try {
            List<Map<String, Object>> rows = new DefaultDatalakeHttpClient(baseUrl).queryAsList("SELECT id,name FROM t");
            check(rows.size() == 2, "queryAsList 返回 2 行, 实际=" + rows.size());
            check("张三".equals(rows.get(0).get("name")), "首行 name=张三, 实际=" + rows.get(0).get("name"));
            check(((Number) rows.get(0).get("id")).intValue() == 1, "首行 id=1");
        } catch (Exception e) {
            check(false, "queryAsList 正常路径不应抛异常: " + e);
        }
    }

    /**
     * 场景 6：绑定参数没有传输通道时必须拒绝。
     */
    private static void bindingArgsAreRejected(String baseUrl) {
        status = 200;
        body = "[{\"id\":1}]";
        check(throwsType(UnsupportedOperationException.class,
                        () -> new DefaultDatalakeHttpClient(baseUrl).queryAsList("SELECT id FROM t WHERE id=?", 1)),
                "带绑定参数抛 UnsupportedOperationException，不静默丢参数");
        check(throwsType(UnsupportedOperationException.class,
                        () -> new DefaultDatalakeHttpClient(baseUrl).queryWithArgs("SELECT id FROM t WHERE id=?", 1)),
                "queryWithArgs 同样拒绝绑定参数");
    }

    /**
     * 场景 7：响应体形态契约。
     */
    private static void parseRowsContract() {
        check(DefaultDatalakeHttpClient.parseRows(ROWS_JSON).size() == 2, "JSON 数组解析为 2 行");
        check(throwsType(IllegalArgumentException.class, () -> DefaultDatalakeHttpClient.parseRows("{\"data\":[]}")),
                "对象包裹的响应抛异常，不冒充空结果");
        check(throwsType(IllegalArgumentException.class, () -> DefaultDatalakeHttpClient.parseRows("<html>500</html>")),
                "畸形 JSON 抛异常");
        check(throwsType(IllegalArgumentException.class, () -> DefaultDatalakeHttpClient.parseRows("  ")),
                "空响应体抛异常");
        check(throwsType(IllegalArgumentException.class,
                        () -> DefaultDatalakeHttpClient.parseRows("[\"scalar\"]")),
                "数组元素不是对象时抛异常");
    }

    /**
     * 场景 8：execute 返回真实受影响行数。
     */
    private static void executeReportsAffectedRows(String baseUrl) {
        SqlExecutor executor = new HttpDatalakeQueryEngine(baseUrl).getExecutor();
        status = 200;
        body = "3";
        try {
            check(executor.execute("UPDATE t SET a=1") == 3, "execute 返回服务端报告的 3 行");
        } catch (Exception e) {
            check(false, "execute 正常路径不应抛异常: " + e);
        }
        body = "OK";
        check(throwsType(IllegalStateException.class, () -> executor.execute("UPDATE t SET a=1")),
                "响应不是行数时抛 IllegalStateException，不返回 0 冒充失败");
    }

    /**
     * 场景 9：分页切片与总数回填。
     */
    private static void queryPageSlicesAndCounts(String baseUrl) {
        SqlExecutor executor = new HttpDatalakeQueryEngine(baseUrl).getExecutor();
        status = 200;
        body = "[{\"n\":1},{\"n\":2},{\"n\":3},{\"n\":4},{\"n\":5}]";
        Pagination page = new Pagination();
        page.setPageNum(2);
        page.setPageSize(2);
        List<Map<String, Object>> rows = executor.queryPage("SELECT n FROM t", page);
        check(rows.size() == 2, "第 2 页每页 2 条返回 2 行, 实际=" + rows.size());
        check(((Number) rows.get(0).get("n")).intValue() == 3, "第 2 页首行为 n=3");
        check(page.getTotal() == 5, "total 回填为 5, 实际=" + page.getTotal());
    }

    /**
     * 场景 10：只读引擎的写入口显式抛。
     */
    private static void readOnlySurfaceThrows(String baseUrl) {
        HttpDatalakeQueryEngine engine = new HttpDatalakeQueryEngine(baseUrl);
        check(throwsType(UnsupportedOperationException.class, () -> engine.addDataSource("x", null)),
                "addDataSource 抛 UnsupportedOperationException");
        check(throwsType(UnsupportedOperationException.class, () -> engine.store("x", List.of(1, 2))),
                "store 抛 UnsupportedOperationException，不静默丢数据");
        check(throwsType(UnsupportedOperationException.class,
                        () -> engine.getExecutor().batch("UPDATE t SET a=?",
                                java.util.Collections.singletonList(new Object[]{1}))),
                "batch 抛 UnsupportedOperationException");
    }

    /**
     * 场景 11：类型映射与错误传播。
     */
    private static void typedQueryMapsRows(String baseUrl) {
        SqlExecutor executor = new HttpDatalakeQueryEngine(baseUrl).getExecutor();
        status = 200;
        body = ROWS_JSON;
        List<Row> rows = executor.query("SELECT id,name FROM t", Row.class);
        check(rows.size() == 2, "类型映射查询返回 2 行, 实际=" + rows.size());
        check("张三".equals(rows.get(0).getName()), "映射后首行 name=张三, 实际=" + rows.get(0).getName());
        status = 500;
        body = "<html>err</html>";
        check(throwsType(IllegalStateException.class, () -> executor.query("SELECT 1")),
                "服务端错误不被伪装成空结果");
        status = 200;
        body = "[]";
    }

    /**
     * 类型映射目标。
     */
    public static class Row {

        /**
         * 主键
         */
        private int id;

        /**
         * 名称
         */
        private String name;

        /**
         * 获取主键。
         *
         * @return 主键
         */
        public int getId() {
            return id;
        }

        /**
         * 设置主键。
         *
         * @param id 主键
         */
        public void setId(int id) {
            this.id = id;
        }

        /**
         * 获取名称。
         *
         * @return 名称
         */
        public String getName() {
            return name;
        }

        /**
         * 设置名称。
         *
         * @param name 名称
         */
        public void setName(String name) {
            this.name = name;
        }
    }

    // ━━━━━━━━━━━━━━ 断言工具 ━━━━━━━━━━━━━━

    /**
     * 可抛异常的动作。
     *
     * @author CH
     * @since 4.0.0.42
     */
    private interface Action {

        /**
         * 执行。
         *
         * @throws Exception 异常
         */
        void run() throws Exception;
    }

    /**
     * 校验动作抛出的异常类型。
     *
     * @param expected 期望异常类型
     * @param action   动作
     * @return 命中返回 true
     */
    private static boolean throwsType(Class<? extends Throwable> expected, Action action) {
        try {
            action.run();
            return false;
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() instanceof RuntimeException) {
                root = root.getCause();
            }
            return expected.isInstance(t) || expected.isInstance(root);
        }
    }

    /**
     * 校验动作不抛异常。
     *
     * @param action 动作
     * @return 不抛返回 true
     */
    private static boolean throwsNone(Action action) {
        try {
            action.run();
            return true;
        } catch (Throwable t) {
            System.out.println("        意外异常: " + t);
            return false;
        }
    }

    /**
     * 忽略异常地执行。
     *
     * @param action 动作
     */
    private static void runQuietly(Action action) {
        try {
            action.run();
        } catch (Throwable ignored) {
            // 仅用于抓取服务端侧观察值
        }
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
        System.out.println("DatalakeQueryClientSmokeTest 结果: PASS=" + passCount + ", FAIL=" + failureCount);
        if (failureCount > 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }
}
