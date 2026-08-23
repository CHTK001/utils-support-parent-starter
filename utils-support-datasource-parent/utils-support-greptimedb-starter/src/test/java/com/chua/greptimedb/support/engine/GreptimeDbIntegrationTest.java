package com.chua.greptimedb.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import io.greptime.models.DataType;
import io.greptime.models.Table;
import io.greptime.models.TableSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GreptimeDB 集成测试（需要 172.16.0.40:4001 已部署 GreptimeDB 服务）。
 *
 * @author CH
 * @since 4.0.0.42
 */
class GreptimeDbIntegrationTest {

    private static final String ENDPOINT = "172.16.0.40:4001";
    private static final String DATABASE = "public";

    /**
     * 测试期间创建的引擎，结束后统一关闭，避免泄漏 gRPC 连接。
     */
    private final java.util.List<GreptimeDbEngine> engines = new java.util.ArrayList<>();

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

    @Test
    void write_metrics_to_greptimedb() throws Exception {
        GreptimeDbEngine engine = (GreptimeDbEngine) Engine.create("greptimedb");
        engine.addDataSource("default", ENDPOINT, DATABASE, "", "");
        engines.add(engine);

        TableSchema schema = TableSchema.newBuilder("metrics_demo")
                .addTag("host", DataType.String)
                .addField("cpu_util", DataType.Float64)
                .addTimestamp("ts", DataType.TimestampMillisecond)
                .build();
        Table table = Table.from(schema);
        long now = System.currentTimeMillis();
        // addRow 顺序须与 schema 定义一致：tag, field, timestamp
        table.addRow("host-a", 0.42, now);
        table.addRow("host-b", 0.88, now + 1);

        var result = engine.write(table).get(30, TimeUnit.SECONDS);
        assertTrue(result.isOk(), "写入应成功: " + result);
        assertEquals(2, result.getOk().getSuccess());
        System.out.println("GreptimeDB 写入成功, affectedRows=" + result.getOk().getSuccess());

        // 通过 HTTP SQL 接口校验数据已落库
        String sql = "SELECT count(*) AS c FROM metrics_demo";
        String resp = postSql("http://172.16.0.40:4000/v1/sql", "sql=" + java.net.URLEncoder.encode(sql, java.nio.charset.StandardCharsets.UTF_8) + "&db=public");
        System.out.println("SQL 校验返回: " + resp);
        assertNotNull(resp);
        assertTrue(resp.contains("\"output\""),
                "SQL 查询应返回有效结果: " + resp);
    }

    /**
     * 通过 GreptimeDB HTTP SQL 接口执行查询。
     */
    private static String postSql(String url, String body) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        java.io.InputStream in = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        if (in == null) {
            return "";
        }
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }
}
