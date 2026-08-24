package com.chua.solr.support.engine;

import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Solr GroupByQueryWrapper（JSON Facet）真实容器测试。
 * 数据集 6 文档：SH×3 / BJ×2 / SZ×1，年龄 20~35。
 */
class SolrGroupByIT {
    /** 集合名 = 类名小写 testcore */
    static class Testcore { }

    private static final int[] PORTS = {8984, 8983};
    private static String baseUrl;

    @BeforeAll
    static void resolveBase() throws Exception {
        for (int port : PORTS) {
            if (reachable("172.16.0.40", port)) {
                baseUrl = "http://172.16.0.40:" + port + "/solr";
                break;
            }
        }
        Assumptions.assumeTrue(baseUrl != null, "Solr 容器不可达，跳过");
        seedDocs();
    }

    private static boolean reachable(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 2000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 直接经 SolrClient 灌入确定性数据并提交 */
    private static void seedDocs() throws Exception {
        try (HttpSolrClient client = new HttpSolrClient.Builder(baseUrl + "/testcore").build()) {
            for (String[] r : new String[][]{
                    {"1", "Alice", "25", "SH"},
                    {"2", "Bob", "30", "SH"},
                    {"3", "Cathy", "28", "SH"},
                    {"4", "Dave", "22", "BJ"},
                    {"5", "Eve", "35", "BJ"},
                    {"6", "Frank", "40", "SZ"}}) {
                SolrInputDocument doc = new SolrInputDocument();
                doc.addField("id", r[0]);
                doc.addField("name_s", r[1]);
                doc.addField("age_i", Integer.parseInt(r[2]));
                doc.addField("city_s", r[3]);
                client.add(doc);
            }
            client.commit();
        }
    }

    @Test
    void groupByCity_allBuckets_andCounts() {
        SolrEngine engine = newEngine();
        try {
            List<Map<String, Object>> buckets = engine
                    .groupBy(Testcore.class, "city_s")
                    .list();
            assertNotNull(buckets);
            assertFalse(buckets.isEmpty(), "应有分组桶");

            Map<Object, Long> byValue = buckets.stream()
                    .filter(b -> b.get("city_s") != null)
                    .collect(Collectors.groupingBy(
                            b -> String.valueOf(b.get("city_s")), Collectors.counting()));
            assertTrue(byValue.containsKey("SH"), "应含 SH 桶: " + byValue);
            assertTrue(byValue.containsKey("BJ"), "应含 BJ 桶: " + byValue);
            assertTrue(byValue.containsKey("SZ"), "应含 SZ 桶: " + byValue);
        } finally {
            engine.close();
        }
    }

    @Test
    void groupBy_withFilter_eq() {
        SolrEngine engine = newEngine();
        try {
            List<Map<String, Object>> buckets = engine
                    .groupBy(Testcore.class, "city_s")
                    .eq("name_s", "Alice")
                    .list();
            assertNotNull(buckets);
            assertFalse(buckets.isEmpty());
            /* Alice 只在 SH */
            String joined = String.valueOf(buckets);
            assertTrue(joined.contains("SH"), "过滤后应只剩 SH 桶: " + joined);
            assertFalse(joined.contains("SZ"), "不应出现 SZ: " + joined);
        } finally {
            engine.close();
        }
    }

    @Test
    void groupBy_gt_filter_and_limit() {
        SolrEngine engine = newEngine();
        try {
            /* age_i > 23 → 剩 SH3 + BJ1(Eve35) + SZ1(Frank40) = 3 城 */
            List<Map<String, Object>> buckets = engine
                    .groupBy(Testcore.class, "city_s")
                    .gt("age_i", 23)
                    .limit(10)
                    .list();
            assertNotNull(buckets);
            assertEquals(3, buckets.size(), "age>23 应命中 3 个城市桶: " + buckets);

            /* limit 生效：按 name_s 分组限制 2 桶 */
            List<Map<String, Object>> limited = engine
                    .groupBy(Testcore.class, "name_s")
                    .limit(2)
                    .list();
            assertNotNull(limited);
            assertEquals(2, limited.size(), "limit 应截断桶数量");
        } finally {
            engine.close();
        }
    }

    private SolrEngine newEngine() {
        SolrEngine engine = new SolrEngine();
        engine.addDataSource("solr", new EngineDataSource<String>() {
            @Override public String name() { return "solr"; }
            @Override public String getSource() { return baseUrl; }
            @Override public com.chua.common.support.lang.datasource.dialect.Dialect getDialect() { return null; }
            @Override public EngineDataSource<String> setSource(Object source) { return this; }
            @Override public EngineDataSource<String> setDialect(com.chua.common.support.lang.datasource.dialect.Dialect dialect) { return this; }
            @Override public String url() { return baseUrl; }
            @Override public String username() { return null; }
            @Override public String password() { return null; }
        });
        return engine;
    }
}
