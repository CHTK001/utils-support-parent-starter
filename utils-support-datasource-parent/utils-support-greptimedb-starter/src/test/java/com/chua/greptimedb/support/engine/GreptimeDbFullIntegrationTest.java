package com.chua.greptimedb.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.greptimedb.support.client.GreptimeDbClient;
import io.greptime.GreptimeDB;
import io.greptime.models.DataType;
import io.greptime.models.Table;
import io.greptime.models.TableSchema;
import io.greptime.rpc.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GreptimeDB 全功能集成测试（需 172.16.0.40:4001 已部署 GreptimeDB）。
 * <p>注意：SPI 返回的 Engine 为共享单例，各测试显式 setDefaultDataSourceName 避免串扰。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class GreptimeDbFullIntegrationTest {

    private static final String ENDPOINT = "172.16.0.40:4001";
    private static final String DATABASE = "public";

    /**
     * 测试期间创建的引擎，结束后统一关闭，避免泄漏 gRPC 连接与后台重连线程。
     */
    private final List<GreptimeDbEngine> engines = new ArrayList<>();

    private GreptimeDbEngine newEngine(String name) {
        GreptimeDbEngine engine = (GreptimeDbEngine) Engine.create("greptimedb");
        engine.addDataSource(name, ENDPOINT, DATABASE, "", "");
        engine.setDefaultDataSourceName(name);
        engines.add(engine);
        return engine;
    }

    @AfterEach
    void tearDown() {
        for (GreptimeDbEngine e : engines) {
            try {
                e.close();
            } catch (Exception ignored) {
            }
        }
        engines.clear();
    }

    /**
     * 构建标准三列表结构（tag: host / field: cpu_util / time: ts）。
     *
     * @param table 表名
     * @return 表结构
     */
    private static TableSchema schema(String table) {
        return TableSchema.newBuilder(table)
                .addTag("host", DataType.String)
                .addField("cpu_util", DataType.Float64)
                .addTimestamp("ts", DataType.TimestampMillisecond)
                .build();
    }

    /**
     * 按表结构构建单行数据表（addRow 顺序：tag, field, timestamp）。
     *
     * @param table 表名
     * @param host  tag 值
     * @param cpu   field 值
     * @param ts    时间戳（epoch 毫秒）
     * @return 已含一行数据的表
     */
    private static Table table(String table, String host, double cpu, long ts) {
        Table t = Table.from(schema(table));
        t.addRow(host, cpu, ts);
        return t;
    }

    @Test
    void write_varargs_multiple_tables() throws Exception {
        GreptimeDbEngine engine = newEngine("default");

        Table t1 = table("full_varargs_1", "h1", 0.1, System.currentTimeMillis());
        Table t2 = table("full_varargs_2", "h2", 0.2, System.currentTimeMillis());

        var result = engine.write(t1, t2).get(30, TimeUnit.SECONDS);
        assertTrue(result.isOk(), "批量写入应成功: " + result);
        assertEquals(2, result.getOk().getSuccess());
    }

    @Test
    void addDataSource_with_client_object() throws Exception {
        GreptimeDB client = GreptimeDbClient.create(ENDPOINT, DATABASE, "", "");
        GreptimeDbEngine engine = (GreptimeDbEngine) Engine.create("greptimedb");
        engine.addDataSource("clientDs", new GreptimeClientDataSource(client));
        engine.setDefaultDataSourceName("clientDs");
        engines.add(engine);

        Table t = table("full_client_ds", "h1", 0.3, System.currentTimeMillis());
        var result = engine.write(t).get(30, TimeUnit.SECONDS);
        assertTrue(result.isOk());
        assertEquals(1, result.getOk().getSuccess());
    }

    @Test
    void addDataSource_with_url_string() throws Exception {
        GreptimeDbEngine engine = (GreptimeDbEngine) Engine.create("greptimedb");
        engine.addDataSource("urlDs", new GreptimeUrlDataSource(ENDPOINT));
        engine.setDefaultDataSourceName("urlDs");
        engines.add(engine);

        Table t = table("full_url_ds", "h1", 0.4, System.currentTimeMillis());
        var result = engine.write(t).get(30, TimeUnit.SECONDS);
        assertTrue(result.isOk());
        assertEquals(1, result.getOk().getSuccess());
    }

    @Test
    void stream_writer_writes_data() throws Exception {
        GreptimeDbEngine engine = newEngine("default");

        Table t = table("full_stream", "h1", 0.5, System.currentTimeMillis());
        var result = engine.client().streamWriter()
                .write(t)
                .completed()
                .get(30, TimeUnit.SECONDS);
        assertEquals(1, result.getSuccess());
    }

    @Test
    void bulk_writer_writes_data() throws Exception {
        GreptimeDbEngine engine = newEngine("default");

        // Bulk API 不会自动建表，先通过普通写入创建表结构
        engine.write(table("full_bulk", "seed", 0.0, System.currentTimeMillis())).get(30, TimeUnit.SECONDS);

        TableSchema schema = schema("full_bulk");
        var writer = engine.client().bulkStreamWriter(
                schema,
                64L * 1024 * 1024,
                256L * 1024 * 1024,
                60000,
                8,
                Context.newDefault());

        Table.TableBufferRoot buffer = writer.tableBufferRoot(1024);
        buffer.addRow("h1", 0.9, System.currentTimeMillis());
        buffer.complete();
        Integer affected = writer.writeNext().get(30, TimeUnit.SECONDS);
        writer.completed();
        assertEquals(1, affected);
    }

    /**
     * 真库查询回读：write 落库后经 Lambda 查询取回，验证读写全链路。
     */
    @Test
    void query_reads_real_database() throws Exception {
        GreptimeDbEngine engine = newEngine("default");
        long ts = System.currentTimeMillis();
        String uniqHost = "node-x-" + ts;

        Table t = Table.from(TableSchema.newBuilder("query_metric")
                .addTag("host", DataType.String)
                .addField("cpu_util", DataType.Float64)
                .addTimestamp("ts", DataType.TimestampMillisecond)
                .build());
        t.addRow(uniqHost, 0.66, ts);
        t.addRow("node-y", 0.77, ts + 1);
        engine.write(t).get(30, TimeUnit.SECONDS);

        // 条件查询：命中刚写入的行
        List<QueryMetric> rows = engine.query(QueryMetric.class)
                .eq(QueryMetric::getHost, uniqHost)
                .list();
        assertEquals(1, rows.size());
        assertEquals(uniqHost, rows.get(0).getHost());
        assertEquals(0.66, rows.get(0).getCpuUtil(), 1e-9);
        assertEquals(ts, rows.get(0).getTs().longValue());

        // ORDER BY 下推到 SQL
        List<QueryMetric> ordered = engine.query(QueryMetric.class)
                .gt(QueryMetric::getCpuUtil, 0.0)
                .orderByDesc(QueryMetric::getCpuUtil)
                .list();
        assertTrue(ordered.size() >= 2);
        assertTrue(ordered.get(0).getCpuUtil() >= ordered.get(ordered.size() - 1).getCpuUtil());

        // 分页
        var page = engine.query(QueryMetric.class).page(1, 1);
        assertTrue(page.getTotal() >= 2);
        assertEquals(1, page.getRecords().size());
    }

    /**
     * 真库删除：DELETE 下推 SQL，删除后查不到对应行。
     */
    @Test
    void delete_removes_real_rows() throws Exception {
        GreptimeDbEngine engine = newEngine("default");
        long ts = System.currentTimeMillis();

        Table t = Table.from(TableSchema.newBuilder("delete_demo")
                .addTag("host", DataType.String)
                .addField("cpu_util", DataType.Float64)
                .addTimestamp("ts", DataType.TimestampMillisecond)
                .build());
        t.addRow("del-a-" + ts, 0.11, ts);
        t.addRow("del-b-" + ts, 0.22, ts);
        engine.write(t).get(30, TimeUnit.SECONDS);

        engine.delete(DeleteDemo.class)
                .eq(DeleteDemo::getHost, "del-b-" + ts)
                .remove();

        List<DeleteDemo> remaining = engine.query(DeleteDemo.class)
                .eq(DeleteDemo::getHost, "del-b-" + ts)
                .list();
        assertTrue(remaining.isEmpty(), "删除后不应再查出该行");

        List<DeleteDemo> kept = engine.query(DeleteDemo.class)
                .eq(DeleteDemo::getHost, "del-a-" + ts)
                .list();
        assertEquals(1, kept.size(), "未删除的行应仍在");
    }

    /**
     * 通用 SQL 执行器：getExecutor 真库冒烟（查询 + 物理分页）。
     */
    @Test
    void sql_executor_works_against_real_db() throws Exception {
        GreptimeDbEngine engine = newEngine("default");
        long ts = System.currentTimeMillis();
        Table t = Table.from(TableSchema.newBuilder("query_metric")
                .addTag("host", DataType.String)
                .addField("cpu_util", DataType.Float64)
                .addTimestamp("ts", DataType.TimestampMillisecond)
                .build());
        t.addRow("exec-" + ts, 0.31, ts);
        engine.write(t).get(30, TimeUnit.SECONDS);

        var executor = engine.getExecutor();
        assertNotNull(executor);

        List<Map<String, Object>> maps =
                executor.query("SELECT * FROM query_metric WHERE host = ?", "exec-" + ts);
        assertEquals(1, maps.size());
        assertEquals("exec-" + ts, maps.get(0).get("host"));

        List<QueryMetric> entities = executor.query(
                "SELECT * FROM query_metric WHERE host = ?", QueryMetric.class, "exec-" + ts);
        assertEquals(1, entities.size());

        com.chua.common.support.lang.datasource.dialect.Pagination page =
                new com.chua.common.support.lang.datasource.dialect.Pagination();
        page.setPageNum(1);
        page.setPageSize(5);
        List<Map<String, Object>> paged =
                executor.queryPage("SELECT * FROM query_metric ORDER BY ts DESC", page);
        assertFalse(paged.isEmpty());
        assertTrue(paged.size() <= 5);
    }

    /**
     * 元数据语义冒烟：meta() facade 可用；
     * 表级 list/describe 为父类未实现模板（显式异常，非静默错误）。
     */
    @Test
    void meta_facade_semantics() throws Exception {
        GreptimeDbEngine engine = newEngine("default");
        long ts = System.currentTimeMillis();
        Table t = Table.from(TableSchema.newBuilder("query_metric")
                .addTag("host", DataType.String)
                .addField("cpu_util", DataType.Float64)
                .addTimestamp("ts", DataType.TimestampMillisecond)
                .build());
        t.addRow("meta-probe-" + ts, 0.01, ts);
        engine.write(t).get(30, TimeUnit.SECONDS);

        // meta() facade 可获取
        var metaData = engine.meta();
        assertNotNull(metaData);

        // 无方言引擎：DefaultMetaData 在入口即显式拒绝（确定行为，非静默错误）
        assertThrows(UnsupportedOperationException.class, metaData::table);
        assertThrows(UnsupportedOperationException.class, () -> metaData.table("query_metric"));

        // 替代路径：表清单/结构可经 getExecutor 真库查询获得
        var tables = engine.getExecutor()
                .query("SHOW TABLES");
        assertNotNull(tables);
        System.out.println("[META] SHOW TABLES 返回行数=" + tables.size());
        assertFalse(tables.isEmpty());
    }

    /**
     * 查询映射实体（表 query_metric）。
     */
    public static class QueryMetric {
        /**
         * 主机标识（tag）
         */
        private String host;
        /**
         * CPU 使用率（field）
         */
        private Double cpuUtil;
        /**
         * 时间戳（time 列，epoch 毫秒）
         */
        private Long ts;

        /** 获取主机标识 */
        public String getHost() { return host; }
        /** 设置主机标识 */
        public void setHost(String host) { this.host = host; }
        /** 获取 CPU 使用率 */
        public Double getCpuUtil() { return cpuUtil; }
        /** 设置 CPU 使用率 */
        public void setCpuUtil(Double cpuUtil) { this.cpuUtil = cpuUtil; }
        /** 获取时间戳 */
        public Long getTs() { return ts; }
        /** 设置时间戳 */
        public void setTs(Long ts) { this.ts = ts; }
    }

    /**
     * 删除测试实体（表 delete_demo）。
     */
    public static class DeleteDemo {
        /**
         * 主机标识（tag）
         */
        private String host;
        /**
         * CPU 使用率（field）
         */
        private Double cpuUtil;
        /**
         * 时间戳（time 列，epoch 毫秒）
         */
        private Long ts;

        /** 获取主机标识 */
        public String getHost() { return host; }
        /** 设置主机标识 */
        public void setHost(String host) { this.host = host; }
        /** 获取 CPU 使用率 */
        public Double getCpuUtil() { return cpuUtil; }
        /** 设置 CPU 使用率 */
        public void setCpuUtil(Double cpuUtil) { this.cpuUtil = cpuUtil; }
        /** 获取时间戳 */
        public Long getTs() { return ts; }
        /** 设置时间戳 */
        public void setTs(Long ts) { this.ts = ts; }
    }

    /**
     * 持有 GreptimeDB 客户端的 EngineDataSource（测试 addDataSource 客户端对象分支）。
     */
    static class GreptimeClientDataSource implements EngineDataSource<GreptimeDB> {
        private final GreptimeDB source;

        GreptimeClientDataSource(GreptimeDB source) {
            this.source = source;
        }

        @Override public String name() { return "clientDs"; }
        @Override public GreptimeDB getSource() { return source; }
        @Override public EngineDataSource<GreptimeDB> setSource(Object s) { return this; }
        @Override public Dialect getDialect() { return null; }
        @Override public EngineDataSource<GreptimeDB> setDialect(Dialect d) { return this; }
        @Override public String url() { return ENDPOINT; }
        @Override public String username() { return ""; }
        @Override public String password() { return ""; }
        @Override public String database() { return DATABASE; }
    }

    /**
     * 持有连接串的 EngineDataSource（测试 addDataSource URL 字符串分支）。
     */
    static class GreptimeUrlDataSource implements EngineDataSource<String> {
        private final String url;

        GreptimeUrlDataSource(String url) {
            this.url = url;
        }

        @Override public String name() { return "urlDs"; }
        @Override public String getSource() { return url; }
        @Override public EngineDataSource<String> setSource(Object s) { return this; }
        @Override public Dialect getDialect() { return null; }
        @Override public EngineDataSource<String> setDialect(Dialect d) { return this; }
        @Override public String url() { return url; }
        @Override public String username() { return ""; }
        @Override public String password() { return ""; }
        @Override public String database() { return DATABASE; }
    }
}
