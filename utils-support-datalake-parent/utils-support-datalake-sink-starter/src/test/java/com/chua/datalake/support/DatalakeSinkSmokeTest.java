package com.chua.datalake.support;

import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.datalake.support.engine.DefaultPipelineEngine;
import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.pipeline.DefaultPipelineManager;
import com.chua.datalake.support.sink.JdbcSink;
import com.chua.datalake.support.sink.StatisticSink;
import com.chua.datalake.support.spi.sink.DataSink;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 数据落地 Sink 冒烟测试。
 *
 * <p>遵循项目约定使用 {@code main} 方法直接运行（本模块无 JUnit 依赖）：</p>
 * <pre>
 * 运行方式：{@code java com.chua.datalake.support.DatalakeSinkSmokeTest}
 * 任一校验失败输出 FAIL 并以退出码 1 结束，全部通过输出 PASS。
 * </pre>
 *
 * <p>本测试不依赖真实数据库：只验证「未配置目标 / 连接失败」时 sink 如实上报，
 * 以及标识符白名单在触达数据库之前就拒绝注入。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DatalakeSinkSmokeTest {

    /**
     * 失败计数
     */
    private static int failureCount = 0;
    /**
     * 成功计数
     */
    private static int passCount = 0;

    /**
     * main。
     * @param args 参数
     */
    public static void main(String[] args) {
        testJdbcSinkWithoutTarget();
        testJdbcSinkRejectsIllegalIdentifiers();
        testJdbcSinkConnectionFailure();
        testStatisticSinkCounters();
        testPipelineResaveTakesEffect();
        testLateRegisteredSinkHeals();
        testIllegalSinkTypeStaysVisible();

        System.out.println("========================================");
        System.out.println("DatalakeSinkSmokeTest 结果: PASS=" + passCount + ", FAIL=" + failureCount);
        if (failureCount > 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }

    /**
     * 未配置目标表与数据源时必须返回 false
     */
    private static void testJdbcSinkWithoutTarget() {
        JdbcSink sink = new JdbcSink();
        check(!sink.write(envelope("p1"), null), "JdbcSink 未配置目标时不谎报成功");
        check(!sink.write(envelope("p1"), Map.of("table", "t_x")), "只有表名没有数据源同样拒绝");
        check(!sink.write(null, null), "空信封返回 false");
        check(!sink.write(envelope("p1"), Map.of("dataSource", deadDataSource())), "只有数据源没有表名拒绝");
    }

    /**
     * 标识符白名单在拼 SQL 前生效
     */
    private static void testJdbcSinkRejectsIllegalIdentifiers() {
        JdbcSink sink = new JdbcSink().withDataSource(deadDataSource()).withTable("t_probe");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id); DROP TABLE t_probe; --", "x");
        try {
            boolean written = sink.write(DataEnvelope.builder().parsed(row).pipelineId("p1").build(), null);
            check(!written, "注入列名被拒绝（实际返回=" + written + "）");
        } catch (IllegalArgumentException e) {
            check(e.getMessage().contains("列名"), "注入列名被拒绝");
        }
        JdbcSink badTable = new JdbcSink().withDataSource(deadDataSource()).withTable("t_probe; DROP TABLE x --");
        try {
            boolean written = badTable.write(envelope("p1"), null);
            check(!written, "注入表名被拒绝（实际返回=" + written + "）");
        } catch (IllegalArgumentException e) {
            check(e.getMessage().contains("表名"), "注入表名被拒绝");
        }
    }

    /**
     * 数据源连接失败必须返回 false
     */
    private static void testJdbcSinkConnectionFailure() {
        JdbcSink sink = new JdbcSink().withDataSource(deadDataSource()).withTable("t_probe");
        check(!sink.write(envelope("p1"), null), "取连接失败时返回 false");
    }

    /**
     * 统计 Sink 真的计数
     */
    private static void testStatisticSinkCounters() {
        StatisticSink sink = new StatisticSink();
        sink.start();
        check(sink.total() == 0, "初始计数为 0");
        sink.write(envelope("pa"), null);
        sink.write(envelope("pa"), null);
        sink.write(envelope("pb"), null);
        check(sink.total() == 3, "总量累计为 3（实际=" + sink.total() + "）");
        check(sink.countOf("pa") == 2, "管线 pa 计数为 2");
        check(sink.countOf("pb") == 1, "管线 pb 计数为 1");
        check(!sink.write(null, null), "空信封不计入统计");
        sink.reset();
        check(sink.total() == 0 && sink.countOf("pa") == 0, "reset 清零");
        sink.stop();
    }

    /**
     * 管线重新保存后，新配置里的 Sink 必须接管
     */
    private static void testPipelineResaveTakesEffect() {
        DefaultPipelineManager manager = new DefaultPipelineManager();
        Map<String, DataSink> registry = new ConcurrentHashMap<>();
        RecordingSink a = new RecordingSink("s-a");
        RecordingSink b = new RecordingSink("s-b");
        registry.put("s-a", a);
        registry.put("s-b", b);
        DefaultPipelineEngine engine = new DefaultPipelineEngine(manager, registry, null);

        manager.savePipeline("p-resave", dsl("p-resave", "s-a"));
        engine.execute("p-resave", envelope("p-resave"));
        check(a.received.size() == 1 && b.received.isEmpty(), "首次执行落到 s-a（a=" + a.received.size()
                + ", b=" + b.received.size() + "）");

        manager.savePipeline("p-resave", dsl("p-resave", "s-b"));
        engine.execute("p-resave", envelope("p-resave"));
        System.out.println("OBSERVE resave a=" + a.received.size() + " b=" + b.received.size());
        check(b.received.size() == 1, "重存后新配置生效，数据落到 s-b（实际 b=" + b.received.size() + "）");
        check(a.received.size() == 1, "重存后不再写旧 sink s-a（实际 a=" + a.received.size() + "）");
    }

    /**
     * Sink 晚注册时管线必须自愈，未注册期间要如实报失败
     */
    private static void testLateRegisteredSinkHeals() {
        DefaultPipelineManager manager = new DefaultPipelineManager();
        Map<String, DataSink> registry = new ConcurrentHashMap<>();
        DefaultPipelineEngine engine = new DefaultPipelineEngine(manager, registry, null);
        manager.savePipeline("p-late", dsl("p-late", "s-late"));

        DataEnvelope before = envelope("p-late");
        engine.execute("p-late", before);
        System.out.println("OBSERVE lateBefore state=" + before.getState() + " trace=" + before.getTrace());
        check(before.getState() == com.chua.datalake.support.model.PipelineState.SINK_FAIL,
                "Sink 未注册时状态为 SINK_FAIL 而非 null（实际=" + before.getState() + "）");
        check(String.valueOf(before.getTrace()).contains("no sink resolved"),
                "trace 指出没有可用 Sink（实际=" + before.getTrace() + "）");

        RecordingSink late = new RecordingSink("s-late");
        registry.put("s-late", late);
        DataEnvelope after = envelope("p-late");
        engine.execute("p-late", after);
        System.out.println("OBSERVE lateAfter received=" + late.received.size() + " state=" + after.getState());
        check(late.received.size() == 1, "晚注册的 Sink 下一次执行即命中（实际=" + late.received.size() + "）");
        check(after.getState() == com.chua.datalake.support.model.PipelineState.SINK_OK,
                "自愈后状态为 SINK_OK（实际=" + after.getState() + "）");
    }

    /**
     * Sink 配置的 type 写错类型时不得炸掉整条管线
     */
    private static void testIllegalSinkTypeStaysVisible() {
        DefaultPipelineManager manager = new DefaultPipelineManager();
        Map<String, DataSink> registry = new ConcurrentHashMap<>();
        RecordingSink ok = new RecordingSink("s-ok");
        registry.put("s-ok", ok);
        DefaultPipelineEngine engine = new DefaultPipelineEngine(manager, registry, null);
        manager.savePipeline("p-badtype",
                "{\"id\":\"p-badtype\",\"stages\":{\"default\":{\"sink\":[{\"type\":7},{\"type\":\"s-ok\"}]}}}");

        DataEnvelope envelope = envelope("p-badtype");
        try {
            engine.execute("p-badtype", envelope);
            check(ok.received.size() == 1, "非法 type 被跳过后其余 Sink 仍写入（实际=" + ok.received.size() + "）");
            check(envelope.getState() == com.chua.datalake.support.model.PipelineState.SINK_OK,
                    "整条管线状态为 SINK_OK（实际=" + envelope.getState() + "）");
        } catch (Exception e) {
            check(false, "非法 type 不得让执行抛出 " + e.getClass().getSimpleName());
        }
    }

    /**
     * 拼装只含 default 阶段与若干 sink 类型的管线 DSL
     *
     * @param pipelineId 管线标识
     * @param sinkTypes  Sink 类型
     * @return DSL JSON
     */
    private static String dsl(String pipelineId, String... sinkTypes) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":\"").append(pipelineId).append("\",\"stages\":{\"default\":{\"sink\":[");
        for (int i = 0; i < sinkTypes.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"type\":\"").append(sinkTypes[i]).append("\"}");
        }
        sb.append("]}}}");
        return sb.toString();
    }

    /**
     * 记录收到的信封，用于断言数据到底落到了哪个 Sink
     */
    private static class RecordingSink implements DataSink {

        /**
         * 收到的信封
         */
        private final List<DataEnvelope> received = new ArrayList<>();
        /**
         * 类型标识
         */
        private final String kind;

        RecordingSink(String kind) {
            this.kind = kind;
        }

        @Override
        public String type() {
            return kind;
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
            received.add(envelope);
            return true;
        }

        @Override
        public EngineDataSource<?> getDataSource() {
            return null;
        }
    }

    /**
     * 构造一条普通数据信封
     *
     * @param pipelineId 管线标识
     * @return 信封
     */
    private static DataEnvelope envelope(String pipelineId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "A-1");
        row.put("name", "张三");
        row.put("_cdc_op", "INSERT");
        return DataEnvelope.builder()
                .parsed(row)
                .pipelineId(pipelineId)
                .traceId("t-" + pipelineId)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 一个总是抛 SQLException 的数据源，用于验证失败路径
     *
     * @return 数据源
     */
    private static DataSource deadDataSource() {
        return new DataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                throw new SQLException("模拟连接失败");
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                throw new SQLException("模拟连接失败");
            }

            @Override
            public PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(PrintWriter out) {
                // 无日志
            }

            @Override
            public void setLoginTimeout(int seconds) {
                // 不支持
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public Logger getParentLogger() {
                return Logger.getLogger("global");
            }

            @Override
            public <T> T unwrap(Class<T> iface) {
                throw new UnsupportedOperationException("unwrap");
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        };
    }

    /**
     * 校验并计数
     *
     * @param condition 条件
     * @param message 消息
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
}
