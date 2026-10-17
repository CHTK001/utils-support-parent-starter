import com.chua.influxdb.support.engine.InfluxdbSeriesEngine;
import org.influxdb.InfluxDB;
import org.influxdb.dto.Point;
import org.influxdb.dto.Query;
import org.influxdb.dto.QueryResult;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 引擎验收运行器 —— 阶段九：InfluxdbSeriesEngine（注入客户端，真实驱动引擎逻辑）。
 *
 * <p>阶段五里 {@code InfluxdbSeriesEngine} 只有契约面（本机无 InfluxDB）。
 * 但该引擎提供了一个<b>公开的注入构造器</b>
 * {@code InfluxdbSeriesEngine(InfluxDB, String)}，注释明确写着「测试或外部生命周期管理」。
 * 因此这里用一个 {@link Proxy} 动态实现 {@link InfluxDB}，把引擎的<b>全部逻辑</b>
 * 真实跑起来，唯一被替换的是第三方 HTTP 客户端本身。</p>
 *
 * <p>这样验到的是本项目自己的代码：参数守卫、标识符白名单、InfluxQL 拼装、
 * 指标发现（{@code SELECT * ... LIMIT 1} 取列名）、多指标歧义拒绝、结果展平、
 * 时间解析、窗口均值降采样、清理语义、关闭语义、以及失败必须抛异常的契约。</p>
 *
 * <p><b>明确不覆盖</b>：真实 InfluxDB 的 InfluxQL 执行语义与线上 HTTP 协议
 * （由官方 influxdb-java 客户端负责，非本项目代码）。</p>
 */
public final class EngineInfluxdb {

    /** 桩捕获的查询语句。 */
    private static final List<String> QUERIES = Collections.synchronizedList(new ArrayList<>());

    /** 桩捕获的写入点。 */
    private static final List<Point> WRITES = Collections.synchronizedList(new ArrayList<>());

    /** 桩被 close 的次数。 */
    private static final AtomicInteger CLOSE_COUNT = new AtomicInteger();

    /** 下一次 write 是否抛异常。 */
    private static volatile boolean writeThrows;

    /** 查询应答器（按 InfluxQL 返回结果）。 */
    private static volatile Function<String, QueryResult> responder = cmd -> noSeries();

    /** 被注入的假客户端。 */
    private static InfluxDB fake;

    private EngineInfluxdb() {
    }

    public static void main(String[] args) {
        EngineFunctional.emit("=".repeat(78));
        EngineFunctional.emit("  引擎验收 阶段九：InfluxdbSeriesEngine（注入客户端）");
        EngineFunctional.emit("=".repeat(78));

        try {
            fake = fakeClient();
        } catch (Throwable t) {
            EngineFunctional.recordFail("无法构造桩客户端: " + EngineFunctional.chain(t));
            EngineFunctional.finishAndExit("EngineInfluxdb-report.txt");
            return;
        }

        runQuietly("guard", EngineInfluxdb::guardSuite);
        runQuietly("write", EngineInfluxdb::writeSuite);
        runQuietly("metrics", EngineInfluxdb::metricsSuite);
        runQuietly("series", EngineInfluxdb::seriesSuite);
        runQuietly("purge", EngineInfluxdb::purgeSuite);
        runQuietly("lifecycle", EngineInfluxdb::lifecycleSuite);

        EngineFunctional.finishAndExit("EngineInfluxdb-report.txt");
    }

    // ==================== 桩客户端 ====================

    private static InfluxDB fakeClient() {
        return (InfluxDB) Proxy.newProxyInstance(
                EngineInfluxdb.class.getClassLoader(),
                new Class<?>[]{InfluxDB.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    switch (name) {
                        case "query":
                            if (args != null && args.length == 1 && args[0] instanceof Query q) {
                                QUERIES.add(q.getCommand());
                                return responder.apply(q.getCommand());
                            }
                            throw new UnsupportedOperationException("未预期的异步 query 调用: " + method);
                        case "write":
                            if (args != null && args.length == 1 && args[0] instanceof Point p) {
                                if (writeThrows) {
                                    throw new RuntimeException("write boom");
                                }
                                WRITES.add(p);
                                return null;
                            }
                            throw new UnsupportedOperationException("未预期的 write 重载: " + method);
                        case "close":
                            CLOSE_COUNT.incrementAndGet();
                            return null;
                        case "setDatabase":
                            return proxy;
                        case "ping":
                            return null;
                        case "toString":
                            return "fake-influxdb";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException("未预期的客户端方法: " + method);
                    }
                });
    }

    private static void reset() {
        QUERIES.clear();
        WRITES.clear();
        CLOSE_COUNT.set(0);
        writeThrows = false;
        responder = cmd -> noSeries();
    }

    private static InfluxdbSeriesEngine freshEngine() {
        reset();
        return new InfluxdbSeriesEngine(fake, "monitor");
    }

    // ==================== 1. 装配与守卫 ====================

    static void guardSuite() {
        EngineFunctional.emit("--- influxdb:guard (装配判定与参数守卫) ---");
        String p = "guard.";

        EngineFunctional.check(p + "engine() 标识与三种「不可用」装配", () -> {
            InfluxdbSeriesEngine nullClient = new InfluxdbSeriesEngine((InfluxDB) null);
            InfluxdbSeriesEngine emptyDb = new InfluxdbSeriesEngine(fake, "");
            InfluxdbSeriesEngine noProps = new InfluxdbSeriesEngine(new Properties());
            if (!"influxdb".equals(nullClient.engine())) {
                throw new EngineFunctional.HarnessFailure("engine() 应为 influxdb: " + nullClient.engine());
            }
            if (nullClient.isAvailable()) {
                throw new EngineFunctional.HarnessFailure("客户端为空时不应可用");
            }
            if (emptyDb.isAvailable()) {
                throw new EngineFunctional.HarnessFailure("数据库名为空时不应可用");
            }
            if (noProps.isAvailable()) {
                throw new EngineFunctional.HarnessFailure("缺 url/database 时不应可用（且不得发起连接）");
            }
            return "三者均不可用";
        });

        EngineFunctional.check(p + "不可用时写/查/清理的降级语义", () -> {
            InfluxdbSeriesEngine e = new InfluxdbSeriesEngine((InfluxDB) null, "monitor");
            expectType(IllegalStateException.class, () -> e.writePoint("cpu", 1L, "cpu_usage", 1.0, 1L));
            if (!e.metrics("cpu", 1L).isEmpty()) {
                throw new EngineFunctional.HarnessFailure("不可用时 metrics 应为空");
            }
            if (!e.series("cpu", 1L, "cpu_usage", 1, "5m").isEmpty()) {
                throw new EngineFunctional.HarnessFailure("不可用时 series 应为空");
            }
            if (e.purge("cpu", 1L, 1L) != 0) {
                throw new EngineFunctional.HarnessFailure("不可用时 purge 应为 0（后端不支持/未装配）");
            }
            return "写抛 ISE，读/清理降级";
        });

        EngineFunctional.check(p + "writePoint 必填参数缺失被拒", () -> {
            InfluxdbSeriesEngine e = freshEngine();
            expectType(IllegalArgumentException.class, () -> e.writePoint(null, 1L, "cpu", 1.0, 1L));
            expectType(IllegalArgumentException.class, () -> e.writePoint("cpu", null, "cpu", 1.0, 1L));
            expectType(IllegalArgumentException.class, () -> e.writePoint("cpu", 1L, null, 1.0, 1L));
            return "已拒绝";
        });

        EngineFunctional.check(p + "非法标识符被白名单拒绝（写入与查询一致）", () -> {
            InfluxdbSeriesEngine e = freshEngine();
            for (String bad : new String[]{"a-b", "a;drop", "a b", "a\"", "", "a.b", "a\tb"}) {
                expectType(IllegalArgumentException.class, () -> e.writePoint(bad, 1L, "cpu", 1.0, 1L));
                expectType(IllegalArgumentException.class, () -> e.writePoint("cpu", 1L, bad, 1.0, 1L));
            }
            expectType(IllegalArgumentException.class, () -> e.metrics("a-b", 1L));
            expectType(IllegalArgumentException.class, () -> e.series("a-b", 1L, "cpu", 1, "5m"));
            expectType(IllegalArgumentException.class, () -> e.series("cpu", 1L, "a-b", 1, "5m"));
            expectType(IllegalArgumentException.class, () -> e.purge("a-b", 1L, 1L));
            expectType(IllegalArgumentException.class, () -> e.purge("cpu", 1L, "a-b", 1L));
            if (!QUERIES.isEmpty() || !WRITES.isEmpty()) {
                throw new EngineFunctional.HarnessFailure("守卫必须在触达客户端之前生效");
            }
            return "写/查/清理全部拒绝，且未触达客户端";
        });

        EngineFunctional.check(p + "数字开头的 measurement/field 合法（SqlName.isWord 契约）", () -> {
            InfluxdbSeriesEngine e = freshEngine();
            e.writePoint("1metric", 7L, "2field", 1.0, 1700000000000L);
            if (WRITES.size() != 1) {
                throw new EngineFunctional.HarnessFailure("数字开头应被接受: " + WRITES);
            }
            String line = WRITES.get(0).lineProtocol();
            if (!line.startsWith("1metric,monitor_id=7 ") || !line.contains("2field=1.0")) {
                throw new EngineFunctional.HarnessFailure("行协议错误: " + line);
            }
            return line;
        });
    }

    // ==================== 2. 写入 ====================

    static void writeSuite() {
        EngineFunctional.emit("--- influxdb:write (点位写入) ---");
        String p = "write.";

        EngineFunctional.check(p + "单点写入：measurement / tag / field / 毫秒转纳秒", () -> {
            InfluxdbSeriesEngine e = freshEngine();
            e.writePoint("cpu", 7L, "cpu_usage", 25.5, 1700000000123L);
            if (WRITES.size() != 1) {
                throw new EngineFunctional.HarnessFailure("写入次数: " + WRITES.size());
            }
            String line = WRITES.get(0).lineProtocol();
            if (!line.startsWith("cpu,monitor_id=7 ")) {
                throw new EngineFunctional.HarnessFailure("行协议前缀错误: " + line);
            }
            if (!line.contains("cpu_usage=25.5")) {
                throw new EngineFunctional.HarnessFailure("字段错误: " + line);
            }
            if (!line.contains("1700000000123000000")) {
                throw new EngineFunctional.HarnessFailure("时间未按毫秒转纳秒: " + line);
            }
            return line;
        });

        EngineFunctional.check(p + "writeBatch 逐点委托（默认方法）", () -> {
            InfluxdbSeriesEngine e = freshEngine();
            Map<String, Double> metrics = new LinkedHashMap<>();
            metrics.put("cpu_usage", 1.5);
            metrics.put("mem_usage", 2.5);
            e.writeBatch("cpu", 7L, metrics, 1700000000000L);
            if (WRITES.size() != 2) {
                throw new EngineFunctional.HarnessFailure("写入次数: " + WRITES.size());
            }
            String joined = WRITES.get(0).lineProtocol() + " | " + WRITES.get(1).lineProtocol();
            if (!joined.contains("cpu_usage=1.5") || !joined.contains("mem_usage=2.5")) {
                throw new EngineFunctional.HarnessFailure("批量内容错误: " + joined);
            }
            return "2 点";
        });

        EngineFunctional.check(p + "客户端写入异常转为 IllegalStateException（不静默丢点）", () -> {
            InfluxdbSeriesEngine e = freshEngine();
            writeThrows = true;
            try {
                expectType(IllegalStateException.class, () -> e.writePoint("cpu", 7L, "cpu_usage", 1.0, 1L));
                return "IllegalStateException";
            } finally {
                writeThrows = false;
            }
        });
    }

    // ==================== 3. 指标发现 ====================

    static void metricsSuite() {
        EngineFunctional.emit("--- influxdb:metrics (指标发现) ---");
        String p = "metrics.";

        EngineFunctional.check(p + "探测语句为 SELECT * ... LIMIT 1 且结果按字典序升序", () -> {
            InfluxdbSeriesEngine e = freshEngine();
            responder = cmd -> matrix("cpu",
                    List.of("time", "mem_usage", "cpu_usage"),
                    List.of(row(1700000000000L, 1.0, 2.0)));
            List<String> metrics = e.metrics("cpu", 7L);
            String expected = "SELECT * FROM \"cpu\" WHERE \"monitor_id\" = '7' LIMIT 1";
            if (!expected.equals(QUERIES.get(0))) {
                throw new EngineFunctional.HarnessFailure("语句错误: " + QUERIES.get(0));
            }
            if (!List.of("cpu_usage", "mem_usage").equals(metrics)) {
                throw new EngineFunctional.HarnessFailure("结果错误: " + metrics);
            }
            return expected;
        });

        EngineFunctional.check(p + "探测返回 error 时降级为空列表", () -> {
            InfluxdbSeriesEngine e = freshEngine();
            responder = cmd -> errored("probe boom");
            if (!e.metrics("cpu", 7L).isEmpty()) {
                throw new EngineFunctional.HarnessFailure("应降级为空列表");
            }
            return "空列表";
        });

        EngineFunctional.check(p + "探测抛异常时转为 IllegalStateException", () -> {
            InfluxdbSeriesEngine e = freshEngine();
            responder = cmd -> {
                throw new RuntimeException("network down");
            };
            expectType(IllegalStateException.class, () -> e.metrics("cpu", 7L));
            return "IllegalStateException";
        });
    }

    // ==================== 4. 曲线查询 ====================

    static void seriesSuite() {
        EngineFunctional.emit("--- influxdb:series (曲线查询与降采样) ---");
        String p = "series.";

        EngineFunctional.check(p + "显式 metricName：InfluxQL 只取单 field", () -> {
            InfluxdbSeriesEngine e = freshEngine();
            responder = cmd -> matrix("cpu",
                    List.of("time", "cpu_usage"),
                    List.of(row(1700000000000L, 10.0)));
            List<List<Object>> curve = e.series("cpu", 7L, "cpu_usage", 1, "5m");
            if (QUERIES.size() != 1) {
                throw new EngineFunctional.HarnessFailure("应为一次查询（不需要探测）: " + QUERIES);
            }
            String ql = QUERIES.get(0);
            if (!ql.startsWith("SELECT \"cpu_usage\" FROM \"cpu\" WHERE \"monitor_id\" = '7'")) {
                throw new EngineFunctional.HarnessFailure("语句错误: " + ql);
            }
            if (!ql.contains("AND time >= ") || !ql.contains("ms AND time <= ") || !ql.endsWith("ms")) {
                throw new EngineFunctional.HarnessFailure("时间范围片段错误: " + ql);
            }
            if (curve.size() != 1 || !equalNum(curve.get(0).get(0), 1699999800000L)
                    || !equalNum(curve.get(0).get(1), 10.0)) {
                throw new EngineFunctional.HarnessFailure("曲线错误: " + curve);
            }
            return ql;
        });

        EngineFunctional.check(p + "窗口均值降采样：同窗口取均值、跨窗口升序", () -> {
            InfluxdbSeriesEngine e = freshEngine();
            responder = cmd -> matrix("cpu",
                    List.of("time", "cpu_usage"),
                    List.of(row(1700000000000L, 10.0), row(1700000001000L, 20.0), row(1700000300000L, 30.0)));
            List<List<Object>> curve = e.series("cpu", 7L, "cpu_usage", 1, "5m");
            if (curve.size() != 2) {
                throw new EngineFunctional.HarnessFailure("桶数错误: " + curve);
            }
            if (!equalNum(curve.get(0).get(0), 1699999800000L) || !equalNum(curve.get(0).get(1), 15.0)) {
                throw new EngineFunctional.HarnessFailure("首桶错误: " + curve.get(0));
            }
            if (!equalNum(curve.get(1).get(0), 1700000100000L) || !equalNum(curve.get(1).get(1), 30.0)) {
                throw new EngineFunctional.HarnessFailure("次桶错误: " + curve.get(1));
            }
            return curve.toString();
        });

        EngineFunctional.check(p + "字符串时间戳（RFC3339）解析为毫秒", () -> {
            InfluxdbSeriesEngine e = freshEngine();
            responder = cmd -> matrix("cpu",
                    List.of("time", "cpu_usage"),
                    List.of(row("2024-01-01T00:00:00Z", 5.0)));
            List<List<Object>> curve = e.series("cpu", 7L, "cpu_usage", 1, "1m");
            if (curve.size() != 1 || !equalNum(curve.get(0).get(0), 1704067200000L)
                    || !equalNum(curve.get(0).get(1), 5.0)) {
                throw new EngineFunctional.HarnessFailure("结果错误: " + curve);
            }
            return "1704067200000";
        });

        EngineFunctional.check(p + "非数值单元格被丢弃，其余点仍返回", () -> {
            InfluxdbSeriesEngine e = freshEngine();
            responder = cmd -> matrix("cpu",
                    List.of("time", "cpu_usage"),
                    List.of(row(1700000000000L, "not-a-number"), row(1700000000001L, 8.0)));
            List<List<Object>> curve = e.series("cpu", 7L, "cpu_usage", 1, "5m");
            if (curve.size() != 1 || !equalNum(curve.get(0).get(1), 8.0)) {
                throw new EngineFunctional.HarnessFailure("结果错误: " + curve);
            }
            return "保留 1 点";
        });

        EngineFunctional.check(p + "metricName 为空且仅一个指标：先探测再查询该指标", () -> {
            InfluxdbSeriesEngine e = freshEngine();
            responder = cmd -> cmd.startsWith("SELECT *")
                    ? matrix("cpu", List.of("time", "cpu_usage"), List.of(row(1700000000000L, 10.0)))
                    : matrix("cpu", List.of("time", "cpu_usage"), List.of(row(1700000000000L, 12.0)));
            List<List<Object>> curve = e.series("cpu", 7L, 1, "5m");
            if (QUERIES.size() != 2) {
                throw new EngineFunctional.HarnessFailure("应为探测+查询两次: " + QUERIES);
            }
            if (!QUERIES.get(1).startsWith("SELECT \"cpu_usage\" FROM \"cpu\"")) {
                throw new EngineFunctional.HarnessFailure("第二次语句错误: " + QUERIES.get(1));
            }
            if (curve.size() != 1 || !equalNum(curve.get(0).get(1), 12.0)) {
                throw new EngineFunctional.HarnessFailure("曲线错误: " + curve);
            }
            return "自动推断 cpu_usage";
        });

        EngineFunctional.check(p + "metricName 为空且多指标：拒绝合并并列候选", () -> {
            InfluxdbSeriesEngine e = freshEngine();
            responder = cmd -> matrix("cpu",
                    List.of("time", "mem_usage", "cpu_usage"),
                    List.of(row(1700000000000L, 1.0, 2.0)));
            try {
                e.series("cpu", 7L, 1, "5m");
                throw new EngineFunctional.HarnessFailure("应抛 IllegalArgumentException");
            } catch (IllegalArgumentException ex) {
                String msg = String.valueOf(ex.getMessage());
                if (!msg.contains("cpu_usage") || !msg.contains("mem_usage")) {
                    throw new EngineFunctional.HarnessFailure("消息未列出候选: " + msg);
                }
                return msg;
            }
        });

        EngineFunctional.check(p + "metricName 为空且无指标：返回空曲线", () -> {
            InfluxdbSeriesEngine e = freshEngine();
            responder = cmd -> matrix("cpu", List.of("time"), List.of(row(1700000000000L)));
            if (!e.series("cpu", 7L, 1, "5m").isEmpty()) {
                throw new EngineFunctional.HarnessFailure("应返回空曲线");
            }
            if (QUERIES.size() != 1) {
                throw new EngineFunctional.HarnessFailure("只应有探测查询: " + QUERIES);
            }
            return "空曲线";
        });

        EngineFunctional.check(p + "参数守卫：hours<=0 / 缺 target·monitorId", () -> {
            InfluxdbSeriesEngine e = freshEngine();
            expectType(IllegalArgumentException.class, () -> e.series("cpu", 7L, "cpu", 0, "5m"));
            expectType(IllegalArgumentException.class, () -> e.series("cpu", 7L, "cpu", -1, "5m"));
            expectType(IllegalArgumentException.class, () -> e.series(null, 7L, "cpu", 1, "5m"));
            expectType(IllegalArgumentException.class, () -> e.series("cpu", null, "cpu", 1, "5m"));
            expectType(IllegalArgumentException.class, () -> e.metrics(null, 7L));
            expectType(IllegalArgumentException.class, () -> e.purge(null, 7L, 1L));
            return "全部拒绝";
        });

        EngineFunctional.check(p + "结果级 error / 序列级 error 都抛 IllegalStateException", () -> {
            InfluxdbSeriesEngine e = freshEngine();
            responder = cmd -> errored("top boom");
            expectType(IllegalStateException.class, () -> e.series("cpu", 7L, "cpu", 1, "5m"));

            responder = cmd -> resultWithSeriesError("series boom");
            expectType(IllegalStateException.class, () -> e.series("cpu", 7L, "cpu", 1, "5m"));
            return "两者皆 ISE";
        });

        EngineFunctional.check(p + "客户端返回 null 或抛异常都不得降级为空曲线", () -> {
            InfluxdbSeriesEngine e = freshEngine();
            responder = cmd -> null;
            expectType(IllegalStateException.class, () -> e.series("cpu", 7L, "cpu", 1, "5m"));

            responder = cmd -> {
                throw new RuntimeException("conn reset");
            };
            expectType(IllegalStateException.class, () -> e.series("cpu", 7L, "cpu", 1, "5m"));
            return "两者皆 ISE";
        });
    }

    // ==================== 5. 保留期清理 ====================

    static void purgeSuite() {
        EngineFunctional.emit("--- influxdb:purge (保留期清理) ---");
        String p = "purge.";

        EngineFunctional.check(p + "DELETE 语句正确且返回 -1（已清理、条数不可统计）", () -> {
            InfluxdbSeriesEngine e = freshEngine();
            int n = e.purge("cpu", 7L, null, 123456L);
            String expected = "DELETE FROM \"cpu\" WHERE time < 123456ms AND \"monitor_id\" = '7'";
            if (!expected.equals(QUERIES.get(0))) {
                throw new EngineFunctional.HarnessFailure("语句错误: " + QUERIES.get(0));
            }
            if (n != -1) {
                throw new EngineFunctional.HarnessFailure("返回值应为 -1（不能是 0）: " + n);
            }
            return expected;
        });

        EngineFunctional.check(p + "monitorId 为空时不附加 tag 条件", () -> {
            InfluxdbSeriesEngine e = freshEngine();
            e.purge("cpu", null, 999L);
            String expected = "DELETE FROM \"cpu\" WHERE time < 999ms";
            if (!expected.equals(QUERIES.get(0))) {
                throw new EngineFunctional.HarnessFailure("语句错误: " + QUERIES.get(0));
            }
            return expected;
        });

        EngineFunctional.check(p + "清理失败抛 IllegalStateException", () -> {
            InfluxdbSeriesEngine e = freshEngine();
            responder = cmd -> {
                throw new RuntimeException("db locked");
            };
            expectType(IllegalStateException.class, () -> e.purge("cpu", 7L, 1L));
            return "IllegalStateException";
        });
    }

    // ==================== 6. 生命周期 ====================

    static void lifecycleSuite() {
        EngineFunctional.emit("--- influxdb:lifecycle (关闭语义) ---");
        String p = "lifecycle.";

        EngineFunctional.check(p + "close 关闭客户端、isAvailable 转 false、可重复调用", () -> {
            InfluxdbSeriesEngine e = freshEngine();
            if (!e.isAvailable()) {
                throw new EngineFunctional.HarnessFailure("初始应可用");
            }
            e.close();
            if (CLOSE_COUNT.get() != 1) {
                throw new EngineFunctional.HarnessFailure("客户端 close 次数: " + CLOSE_COUNT.get());
            }
            if (e.isAvailable()) {
                throw new EngineFunctional.HarnessFailure("关闭后 isAvailable 应为 false");
            }
            e.close();
            if (CLOSE_COUNT.get() < 1) {
                throw new EngineFunctional.HarnessFailure("重复 close 不应清空已发生的关闭");
            }
            return "closed, close 次数=" + CLOSE_COUNT.get();
        });

        EngineFunctional.check(p + "关闭后写拒绝、读与清理降级", () -> {
            InfluxdbSeriesEngine e = freshEngine();
            e.close();
            expectType(IllegalStateException.class, () -> e.writePoint("cpu", 7L, "cpu", 1.0, 1L));
            if (!e.metrics("cpu", 7L).isEmpty()) {
                throw new EngineFunctional.HarnessFailure("关闭后 metrics 应为空");
            }
            if (!e.series("cpu", 7L, "cpu", 1, "5m").isEmpty()) {
                throw new EngineFunctional.HarnessFailure("关闭后 series 应为空");
            }
            if (e.purge("cpu", 7L, 1L) != 0) {
                throw new EngineFunctional.HarnessFailure("关闭后 purge 应为 0");
            }
            if (!QUERIES.isEmpty() || !WRITES.isEmpty()) {
                throw new EngineFunctional.HarnessFailure("关闭后不得再触达客户端");
            }
            return "写 ISE / 读空 / 清理 0，且零调用";
        });
    }

    // ==================== 辅助 ====================

    private static List<Object> row(Object... cells) {
        List<Object> row = new ArrayList<>();
        Collections.addAll(row, cells);
        return row;
    }

    private static QueryResult matrix(String name, List<String> columns, List<List<Object>> values) {
        QueryResult.Series series = new QueryResult.Series();
        series.setName(name);
        series.setColumns(new ArrayList<>(columns));
        series.setValues(new ArrayList<>(values));
        QueryResult.Result result = new QueryResult.Result();
        result.setSeries(List.of(series));
        QueryResult qr = new QueryResult();
        qr.setResults(List.of(result));
        return qr;
    }

    private static QueryResult noSeries() {
        QueryResult qr = new QueryResult();
        qr.setResults(List.of());
        return qr;
    }

    private static QueryResult errored(String message) {
        QueryResult qr = new QueryResult();
        qr.setError(message);
        return qr;
    }

    private static QueryResult resultWithSeriesError(String message) {
        QueryResult.Result result = new QueryResult.Result();
        result.setError(message);
        QueryResult qr = new QueryResult();
        qr.setResults(List.of(result));
        return qr;
    }

    private static boolean equalNum(Object actual, double expected) {
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
