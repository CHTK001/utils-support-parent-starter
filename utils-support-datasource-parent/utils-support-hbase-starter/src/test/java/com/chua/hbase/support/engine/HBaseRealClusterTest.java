package com.chua.hbase.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Assumptions;

/**
 * HBase 真实集群集成测试（172.16.0.40:2181 standalone 容器）。
 * 环境不可达时自动跳过。
 *
 * @author CH
 * @since 4.0.0.42
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HBaseRealClusterTest {

    private static final String QUORUM = "172.16.0.40:2181";
    private static final String TABLE = "it_hbase_engine";
    private static final String FAMILY = "cf";
    private static final long UNIQUE = System.currentTimeMillis();

    private static boolean reachable(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 2000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * ZK 可达才执行；避免 CI 无环境误报。
     */
    @BeforeAll
    static void assumeEnv() throws Exception {
        Assumptions.assumeTrue(reachable("172.16.0.40", 2181), "HBase ZK 不可达，跳过");
        // 给 Master/RegionServer 一点完成注册的时间
        for (int i = 0; i < 15 && !reachable("hbase-docker", 16020); i++) {
            Thread.sleep(2000);
        }
    }

    private static HBaseEngine engine() {
        HBaseEngine e = (HBaseEngine) Engine.create("hbase");
        e.addDataSource("default", QUORUM);
        return e;
    }

    /**
     * 建表 + put + get 回读一致（真实写读）。
     */
    @Test
    @Order(1)
    void put_then_get_roundtrip() {
        HBaseEngine engine = engine();
        try {
            engine.createTable(TABLE, FAMILY);
            String rk = "row-" + UNIQUE;
            Map<String, String> values = new LinkedHashMap<>();
            values.put("name", "n-" + UNIQUE);
            values.put("city", "shanghai");
            engine.put(TABLE, rk, FAMILY, values);

            Map<String, String> back = engine.get(TABLE, rk, FAMILY);
            assertEquals("n-" + UNIQUE, back.get("name"));
            assertEquals("shanghai", back.get("city"));
            System.out.println("[HB-VERIFY] get 回读一致 row=" + rk);
        } finally {
            engine.close();
        }
    }

    /**
     * scan 按行键前缀命中刚写入的行。
     */
    @Test
    @Order(2)
    void scan_by_prefix_finds_row() {
        HBaseEngine engine = engine();
        try {
            engine.createTable(TABLE, FAMILY);
            String rk = "scan-" + UNIQUE;
            Map<String, String> values = new LinkedHashMap<>();
            values.put("name", "s-" + UNIQUE);
            engine.put(TABLE, rk, FAMILY, values);

            var rows = engine.scan(TABLE, FAMILY, "scan-");
            assertTrue(rows.size() >= 1);
            assertTrue(rows.stream().anyMatch(m -> uniq(m).equals(rk)), "前缀扫描应命中该行");

            var all = engine.scan(TABLE, FAMILY, null);
            assertTrue(all.size() >= rows.size());
            System.out.println("[HB-VERIFY] 全表行数=" + all.size());
        } finally {
            engine.close();
        }
    }

    /**
     * deleteRow 后 get 为空（真实删除）。
     */
    @Test
    @Order(3)
    void delete_row_removes_data() {
        HBaseEngine engine = engine();
        try {
            String rk = "del-" + UNIQUE;
            engine.createTable(TABLE, FAMILY);
            Map<String, String> values = new LinkedHashMap<>();
            values.put("name", "d-" + UNIQUE);
            engine.put(TABLE, rk, FAMILY, values);
            assertFalse(engine.get(TABLE, rk, FAMILY).isEmpty());

            engine.deleteRow(TABLE, rk);
            assertTrue(engine.get(TABLE, rk, FAMILY).isEmpty(), "删除后应读不到该行");
            System.out.println("[HB-VERIFY] 删除生效 row=" + rk);
        } finally {
            engine.close();
        }
    }

    /**
     * 提取行键字段。
     */
    private static String uniq(Map<String, String> m) {
        return m.getOrDefault("__row", "");
    }
}
