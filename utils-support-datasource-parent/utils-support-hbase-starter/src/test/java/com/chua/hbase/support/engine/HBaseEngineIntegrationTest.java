package com.chua.hbase.support.engine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HBase 引擎集成测试。
 *
 * <p>基于 Testcontainers：自动拉起 {@code harisekhon/hbase:2.1} 容器（standalone 单机模式，
 * 含 ZooKeeper），无需外部服务与环境变量。</p>
 *
 * <p>测试覆盖 HBase 原生领域 API（createTable / put / get / scan / deleteRow），
 * 以及 ORM 语义（store）的 UnsupportedOperationException 拒绝断言。
 * {@code query() / update() / delete()} 等 ORM 链式调用同样被显式拒绝。</p>
 *
 * <p>需要本机 Docker 环境；容器随测试类启动、结束后自动回收。</p>
 *
 * @author CH
 */
public class HBaseEngineIntegrationTest {

    private static final String TABLE = "hbase_itest";
    private static final String FAMILY = HBaseEngine.DEFAULT_FAMILY;

    /**
     * 真实 HBase standalone 容器（ZK 2181 + master/regionserver RPC 16000/16020）
    */
    static final GenericContainer<?> HBASE = new GenericContainer<>(DockerImageName.parse("harisekhon/hbase:2.1"))
            .withExposedPorts(2181, 16000, 16010, 16020, 16030)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(5)));

    private HBaseEngine engine;

    /**
     * 启动Container。
     */
    @BeforeAll
    static void startContainer() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                org.testcontainers.DockerClientFactory.instance().isDockerAvailable(),
                "本机无 Docker 环境，跳过容器集成测试");
        HBASE.start();
    }

    /**
     * 停止Container。
     */
    @AfterAll
    static void stopContainer() {
        HBASE.stop();
    }

    /**
     * 设置Up。
     */
    @BeforeEach
    void setUp() {
        engine = new HBaseEngine();
        engine.addDataSource("default", HBASE.getHost() + ":" + HBASE.getMappedPort(2181));
        // 确保测试表存在
        engine.createTable(TABLE, FAMILY);
    }

    // ==================== createTable ====================

    /**
     * 测试：创建表Idempotent。
     */
    @Test
    void testCreateTableIdempotent() {
        // 重复建表不抛异常（已存在则跳过）
        assertDoesNotThrow(() -> engine.createTable(TABLE, FAMILY));
    }

    // ==================== put / get ====================

    /**
     * 测试：放入And获取。
     */
    @Test
    void testPutAndGet() {
        Map<String, String> vals = Map.of("name", "Alice", "age", "25");
        engine.put(TABLE, "row001", FAMILY, vals);

        Map<String, String> got = engine.get(TABLE, "row001", FAMILY);
        assertEquals("Alice", got.get("name"));
        assertEquals("25", got.get("age"));
    }

    /**
     * 测试：获取ReturnsEmptyWhenMissing。
     */
    @Test
    void testGetReturnsEmptyWhenMissing() {
        Map<String, String> got = engine.get(TABLE, "row_not_exist", FAMILY);
        assertNotNull(got);
        assertTrue(got.isEmpty(), "不存在的行应返回空 Map");
    }

    // ==================== scan ====================

    /**
     * 测试：Scan。
     */
    @Test
    void testScan() {
        engine.put(TABLE, "scan001", FAMILY, Map.of("v", "a"));
        engine.put(TABLE, "scan002", FAMILY, Map.of("v", "b"));

        List<Map<String, String>> rows = engine.scan(TABLE, FAMILY, null);
        assertFalse(rows.isEmpty(), "扫描全表应有数据");
        assertTrue(rows.stream().allMatch(m -> m.containsKey("__row")), "scan 结果每行应含 __row 键");
        assertTrue(rows.stream().anyMatch(m -> "scan001".equals(m.get("__row"))));
        assertTrue(rows.stream().anyMatch(m -> "scan002".equals(m.get("__row"))));
    }

    /**
     * 测试：ScanWith行前缀。
     */
    @Test
    void testScanWithRowPrefix() {
        engine.put(TABLE, "pfx:aaa", FAMILY, Map.of("x", "1"));
        engine.put(TABLE, "pfx:bbb", FAMILY, Map.of("x", "2"));
        engine.put(TABLE, "other:ccc", FAMILY, Map.of("x", "3"));

        List<Map<String, String>> rows = engine.scan(TABLE, FAMILY, "pfx:");
        List<String> rowKeys = rows.stream().map(m -> m.get("__row")).toList();
        assertTrue(rowKeys.contains("pfx:aaa"));
        assertTrue(rowKeys.contains("pfx:bbb"));
        assertFalse(rowKeys.contains("other:ccc"), "前缀扫描不应包含 other:ccc");
    }

    // ==================== deleteRow ====================

    /**
     * 测试：删除行。
     */
    @Test
    void testDeleteRow() {
        engine.put(TABLE, "del001", FAMILY, Map.of("d", "v"));
        Map<String, String> before = engine.get(TABLE, "del001", FAMILY);
        assertFalse(before.isEmpty(), "delete 前应有数据");

        engine.deleteRow(TABLE, "del001");

        Map<String, String> after = engine.get(TABLE, "del001", FAMILY);
        assertTrue(after.isEmpty(), "delete 后应为空");
    }

    // ==================== ORM 语义拒绝 ====================

    /**
     * 测试：StoreThrowsUnsupportedOperation。
     */
    @Test
    void testStoreThrowsUnsupportedOperation() {
        assertThrows(UnsupportedOperationException.class,
                () -> engine.store("default", List.of("any")),
                "store() 应抛出 UnsupportedOperationException");
    }

    /**
     * 测试：查询列出ThrowsUnsupportedOperation。
     */
    @Test
    void testQueryListThrowsUnsupportedOperation() {
        // HBaseEngine.executeNewQuery 抛 UnsupportedOperationException，
        // query(Class) 走 AbstractEngine 的 EngineQueryWrapper → executeQuery → executeNewQuery → 抛出
        assertThrows(Exception.class,
                () -> engine.query(String.class).list(),
                "query().list() 应因 executeNewQuery 不支持而抛异常");
    }

    // ==================== put 覆盖写 ====================

    /**
     * 测试：放入Overwrite。
     */
    @Test
    void testPutOverwrite() {
        engine.put(TABLE, "ov001", FAMILY, Map.of("v", "old"));
        engine.put(TABLE, "ov001", FAMILY, Map.of("v", "new"));
        Map<String, String> got = engine.get(TABLE, "ov001", FAMILY);
        assertEquals("new", got.get("v"), "相同行键应覆盖写");
    }

    // ==================== close ====================

    /**
     * 测试：关闭。
     */
    @Test
    void testClose() {
        assertDoesNotThrow(engine::close);
    }
}
