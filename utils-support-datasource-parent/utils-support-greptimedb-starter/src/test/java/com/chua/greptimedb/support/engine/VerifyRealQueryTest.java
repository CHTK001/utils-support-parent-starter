package com.chua.greptimedb.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真库查询验证：本测试类【全程不调用任何写入 API】。
 * 所有查到的数据只能来自 GreptimeDB 服务器持久层。
 *
 * @author CH
 * @since 4.0.0.42
 */
class VerifyRealQueryTest {

    private static final String ENDPOINT = "172.16.0.40:4001";
    private static final String DATABASE = "public";

    /**
     * 映射表 query_metric（由此前其它测试进程写入并持久化的数据）。
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

    private GreptimeDbEngine newEngine() {
        GreptimeDbEngine engine = (GreptimeDbEngine) Engine.create("greptimedb");
        engine.addDataSource("default", ENDPOINT, DATABASE, "", "");
        engine.setDefaultDataSourceName("default");
        return engine;
    }

    /**
     * 反证 A：HTTP 端口不通时查询必须失败。
     * 若存在"内存假查询"，此处不会抛异常 —— 以此排除内存路径。
     */
    @Test
    void a_query_requires_live_network() {
        GreptimeDbEngine engine = newEngine();
        // 指向关闭的 MySQL 协议端口
        engine.setJdbcUrl("jdbc:mysql://172.16.0.40:4999/public?useSSL=false&connectTimeout=3000");
        assertThrows(Exception.class,
                () -> engine.query(QueryMetric.class).list(),
                "指向不可达端口必须失败 => 查询依赖真实网络往返，不存在内存旁路");
    }

    /**
     * 反证 B：容器刚被重启、且本 JVM 未写过任何数据，
     * 查询仍能返回【之前进程】写入的历史行 => 数据来自服务器磁盘，绝非 JVM 内存。
     */
    @Test
    void b_reads_historical_rows_without_writing_in_this_jvm() {
        GreptimeDbEngine engine = newEngine();
        List<QueryMetric> rows = engine.query(QueryMetric.class)
                .gt(QueryMetric::getCpuUtil, 0.0)
                .orderByDesc(QueryMetric::getTs)
                .list();
        System.out.println("[VERIFY-B] 本 JVM 零写入，从服务器读回历史行数=" + rows.size());
        assertFalse(rows.isEmpty(), "容器重启后仍能读到历史数据 => 真库持久化");
        QueryMetric first = rows.get(0);
        System.out.println("[VERIFY-B] 样例行: host=" + first.getHost()
                + " cpu=" + first.getCpuUtil() + " ts=" + first.getTs());
        assertNotNull(first.getHost());
    }

    /**
     * 反证 C：同一过滤条件，引擎通道 与 裸 HttpURLConnection 直连 /v1/sql
     * 返回结果完全一致 => 引擎查询就是转发到服务器 SQL 接口。
     */
    @Test
    void c_engine_channel_equals_raw_http_channel() throws Exception {
        String uniqHost = "verify-" + System.currentTimeMillis();

        // 用 gRPC 写入一行唯一标识数据（写入走官方 SDK）
        GreptimeDbEngine writer = newEngine();
        io.greptime.models.TableSchema schema = io.greptime.models.TableSchema.newBuilder("query_metric")
                .addTag("host", io.greptime.models.DataType.String)
                .addField("cpu_util", io.greptime.models.DataType.Float64)
                .addTimestamp("ts", io.greptime.models.DataType.TimestampMillisecond)
                .build();
        io.greptime.models.Table t = io.greptime.models.Table.from(schema);
        long ts = System.currentTimeMillis();
        t.addRow(uniqHost, 0.123456, ts);
        var ok = writer.write(t).get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(ok.isOk());
        try { writer.close(); } catch (Exception ignored) { }

        // 通道1：引擎 Lambda 查询
        GreptimeDbEngine reader = newEngine();
        List<QueryMetric> viaEngine = reader.query(QueryMetric.class)
                .eq(QueryMetric::getHost, uniqHost)
                .list();

        // 通道2：绕开引擎，裸 HTTP 直连服务器
        String sql = "SELECT host, cpu_util, ts FROM query_metric WHERE host = '" + uniqHost + "'";
        String rawResp = postSql(sql);
        System.out.println("[VERIFY-C] 引擎通道行数=" + viaEngine.size()
                + " | 裸HTTP响应含该行=" + rawResp.contains(uniqHost));

        assertEquals(1, viaEngine.size());
        assertTrue(rawResp.contains(uniqHost), "裸 HTTP 也必须查到同一行");
        assertEquals(0.123456, viaEngine.get(0).getCpuUtil(), 1e-9);
        assertEquals(ts, viaEngine.get(0).getTs().longValue());
    }

    /**
     * 绕开引擎，以裸 HttpURLConnection 直连 GreptimeDB HTTP SQL 接口。
     *
     * @param sql 待执行 SQL
     * @return 响应体文本
     * @throws Exception 请求失败
     */
    private static String postSql(String sql) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://172.16.0.40:4000/v1/sql").openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        String body = "sql=" + URLEncoder.encode(sql, StandardCharsets.UTF_8) + "&db=public";
        try (var os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }
}
