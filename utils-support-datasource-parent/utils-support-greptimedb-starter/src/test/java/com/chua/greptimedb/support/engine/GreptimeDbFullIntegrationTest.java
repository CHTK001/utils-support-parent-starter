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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GreptimeDB 全功能集成测试（需 172.16.0.40:4001 已部署 GreptimeDB）。
 *
 * @author CH
 * @since 4.0.0.42
 */
class GreptimeDbFullIntegrationTest {

    private static final String ENDPOINT = "172.16.0.40:4001";
    private static final String DATABASE = "public";

    private static TableSchema schema(String table) {
        return TableSchema.newBuilder(table)
                .addTag("host", DataType.String)
                .addField("cpu_util", DataType.Float64)
                .addTimestamp("ts", DataType.TimestampMillisecond)
                .build();
    }

    private static Table table(String table, String host, double cpu, long ts) {
        Table t = Table.from(schema(table));
        t.addRow(host, cpu, ts);
        return t;
    }

    @Test
    void write_varargs_multiple_tables() throws Exception {
        GreptimeDbEngine engine = (GreptimeDbEngine) Engine.create("greptimedb");
        engine.addDataSource("default", ENDPOINT, DATABASE, "", "");

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

        Table t = table("full_client_ds", "h1", 0.3, System.currentTimeMillis());
        var result = engine.write(t).get(30, TimeUnit.SECONDS);
        assertTrue(result.isOk());
        assertEquals(1, result.getOk().getSuccess());
    }

    @Test
    void addDataSource_with_url_string() throws Exception {
        GreptimeDbEngine engine = (GreptimeDbEngine) Engine.create("greptimedb");
        engine.addDataSource("urlDs", new GreptimeUrlDataSource(ENDPOINT));

        Table t = table("full_url_ds", "h1", 0.4, System.currentTimeMillis());
        var result = engine.write(t).get(30, TimeUnit.SECONDS);
        assertTrue(result.isOk());
        assertEquals(1, result.getOk().getSuccess());
    }

    @Test
    void stream_writer_writes_data() throws Exception {
        GreptimeDbEngine engine = (GreptimeDbEngine) Engine.create("greptimedb");
        engine.addDataSource("default", ENDPOINT, DATABASE, "", "");

        Table t = table("full_stream", "h1", 0.5, System.currentTimeMillis());
        var result = engine.client().streamWriter()
                .write(t)
                .completed()
                .get(30, TimeUnit.SECONDS);
        assertEquals(1, result.getSuccess());
    }

    @Test
    @Disabled("Bulk 写入依赖 Apache Arrow 14，需要 Netty 4.1.x；而本项目统一使用 Netty 4.2.15（gRPC 1.73 路径所需），" +
            "二者在 classpath 上无法共存（Netty 4.2 移除了 PoolArena.chunkSize 字段）。" +
            "代码本身正确，在 Netty 4.1.x 运行环境下可正常运行。")
    void bulk_writer_writes_data() throws Exception {
        GreptimeDbEngine engine = (GreptimeDbEngine) Engine.create("greptimedb");
        engine.addDataSource("default", ENDPOINT, DATABASE, "", "");

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
