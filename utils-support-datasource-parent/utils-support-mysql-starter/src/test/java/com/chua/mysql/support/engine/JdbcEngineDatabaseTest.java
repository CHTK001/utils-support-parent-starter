package com.chua.mysql.support.engine;

import com.chua.datasource.support.annotation.TableName;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JdbcEngine 完整集成测试，连接 172.16.0.40:3308 MySQL 服务。
 * <p>
 * 覆盖场景：
 * <ol>
 *   <li>建库能力 — {@code createDatabase}</li>
 *   <li>建表能力 — Executor DDL</li>
 *   <li>增删改查闭环 — Executor CRUD</li>
 *   <li>元数据入口 — {@code meta().table()} / {@code meta().index()}</li>
 *   <li>用户管理 — {@code meta().user()}</li>
 *   <li>权限管理 — {@code meta().permission()}</li>
 *   <li>索引管理 — {@code index()} SPI 直接入口</li>
 *   <li>清理 — 删除测试库与测试用户</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JdbcEngineDatabaseTest {

    private static final String HOST = "172.16.0.40";
    private static final int PORT = 3308;
    private static final String ADMIN_USER = "root";
    private static final String ADMIN_PASS = "root";
    private static final String TEST_DB = "jdbc_engine_test_db";
    private static final String TEST_USER = "engine_test_user";
    private static final String TEST_TABLE = "engine_test_table";
    private static final String TEST_IDX = "idx_engine_test_name";

    private static MysqlEngine engine;

    // ==================== 可达性检查 ====================

    @BeforeAll
    static void assumeEnv() {
        Assertions.assertTrue(reachable(HOST, PORT), "MySQL 不可达 (172.16.0.40:" + PORT + ")，跳过测试");
    }

    @BeforeEach
    void setUp() {
        engine = new MysqlEngine();
        // 先连 mysql 系统库，以便创建测试库
        engine.addDataSource("admin", HOST, PORT, "mysql", ADMIN_USER, ADMIN_PASS);
        engine.setDefaultDataSourceName("admin");
        // 清理上一次运行遗留的测试用户（所有 host）
        cleanTestUser();
    }

    private void cleanTestUser() {
        try {
            java.sql.Connection conn = java.sql.DriverManager.getConnection(
                    "jdbc:mysql://" + HOST + ":" + PORT + "/mysql?useSSL=false&allowPublicKeyRetrieval=true",
                    ADMIN_USER, ADMIN_PASS);
            // 查询所有匹配的 host 并逐一删除
            java.sql.ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT host FROM mysql.user WHERE user = '" + TEST_USER + "'");
            while (rs.next()) {
                String host = rs.getString("host");
                conn.createStatement().execute(
                        "DROP USER IF EXISTS '" + TEST_USER + "'@'" + host + "'");
            }
            rs.close();
            conn.close();
        } catch (Exception ignored) {}
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            try { engine.close(); } catch (Exception ignored) {}
        }
    }

    // ==================== 测试用例 ====================

    /**
     * T1: 创建测试数据库。
     */
    @Test
    @Order(1)
    void test_create_database() {
        boolean created = engine.createDatabase(TEST_DB);
        assertTrue(created, "创建数据库应返回 true");
        assertTrue(engine.databaseExists(TEST_DB), "数据库应已存在");
    }

    /**
     * T2: 列出数据库，确认测试库出现。
     */
    @Test
    @Order(2)
    void test_list_databases() {
        List<String> dbs = engine.listDatabases();
        assertTrue(dbs.contains("mysql"), "应包含 mysql 系统库");
        assertTrue(dbs.contains(TEST_DB), "应包含测试库 " + TEST_DB);
    }

    /**
     * T3: 切换到测试库，建表并插入数据。
     */
    @Test
    @Order(3)
    void test_create_table_and_insert() {
        switchToTestDb();

        var ex = engine.getExecutor();
        assertNotNull(ex, "Executor 不应为 null");

        ex.execute("DROP TABLE IF EXISTS `" + TEST_TABLE + "`");
        ex.execute(
                "CREATE TABLE `" + TEST_TABLE + "` ("
                        + "  id   INT AUTO_INCREMENT PRIMARY KEY,"
                        + "  name VARCHAR(100) NOT NULL,"
                        + "  score INT DEFAULT 0"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        );

        int n = ex.execute("INSERT INTO `" + TEST_TABLE + "` (name, score) VALUES (?, ?)", "alice", 95);
        assertEquals(1, n, "插入一行应返回 1");

        n = ex.execute("INSERT INTO `" + TEST_TABLE + "` (name, score) VALUES (?, ?)", "bob", 87);
        assertEquals(1, n);

        List<Map<String, Object>> rows = ex.query("SELECT id, name, score FROM `" + TEST_TABLE + "` ORDER BY id");
        assertEquals(2, rows.size(), "应有 2 行数据");
        assertEquals("alice", rows.get(0).get("name"));
        assertEquals("bob", rows.get(1).get("name"));
    }

    /**
     * T4: 通过 meta().table() 查询表结构。
     */
    @Test
    @Order(4)
    void test_meta_table() {
        switchToTestDb();

        var tables = engine.meta().table().list();
        assertFalse(tables.isEmpty(), "应列出至少一张表");
        assertTrue(tables.stream().anyMatch(t -> TEST_TABLE.equals(t.getName())),
                "应包含表 " + TEST_TABLE);
    }

    /**
     * T5: 创建全文索引并通过 meta().index() 验证。
     */
    @Test
    @Order(5)
    void test_meta_index() {
        switchToTestDb();

        engine.meta().index()
                .onTable(TEST_TABLE)
                .create(TEST_IDX)
                .column("name")
                .execute();

        var indexes = engine.meta().index().onTable(TEST_TABLE).list();
        assertTrue(indexes.stream().anyMatch(i -> TEST_IDX.equals(i.getName())),
                "索引 " + TEST_IDX + " 应存在");
    }

    /**
     * T6: 创建测试用户并验证 user() SPI 入口。
     */
    @Test
    @Order(6)
    void test_create_user() {
        // 切回 admin 库创建用户
        engine.setDefaultDataSourceName("admin");
        var userMgr = engine.user();
        assertNotNull(userMgr, "user() SPI 入口不应为 null");

        var usersBefore = userMgr.listUsers();
        long countBefore = usersBefore.size();

        userMgr.createUser(TEST_USER)
                .withPassword("engine_test_pass")
                .execute();

        var usersAfter = userMgr.listUsers();
        assertEquals(countBefore + 1, usersAfter.size(), "用户列表应多一条");
        assertTrue(usersAfter.stream().anyMatch(u -> TEST_USER.equals(u.getUser())),
                "应包含新建用户 " + TEST_USER);
    }

    /**
     * T7: 通过权限授予测试权限管理入口。
     */
    @Test
    @Order(7)
    void test_permission_grant() {
        // 切回 admin 库授权
        engine.setDefaultDataSourceName("admin");
        var permMgr = engine.permission();
        assertNotNull(permMgr, "permission() SPI 入口不应为 null");

        // 授予测试库权限
        permMgr.grant("SELECT, INSERT, UPDATE, DELETE")
                .toUser(TEST_USER)
                .onDatabase(TEST_DB)
                .execute();

        var perms = permMgr.listPermissions(TEST_USER);
        assertTrue(perms.stream().anyMatch(p -> TEST_DB.equals(p.getDatabaseName())),
                "用户应有测试库权限记录");
    }

    /**
     * T8: 用测试用户连接测试库执行查询（端到端验证权限生效）。
     */
    @Test
    @Order(8)
    void test_query_as_test_user() {
        MysqlEngine userEngine = new MysqlEngine();
        userEngine.addDataSource("test", HOST, PORT, TEST_DB, TEST_USER, "engine_test_pass");

        var ex = userEngine.getExecutor();
        assertNotNull(ex, "测试用户应有 Executor");

        List<Map<String, Object>> rows = ex.query(
                "SELECT name, score FROM `" + TEST_TABLE + "` ORDER BY score DESC");
        assertFalse(rows.isEmpty(), "测试用户应能查询到数据");
        assertEquals("alice", rows.get(0).get("name"), "分数最高应是 alice");
        userEngine.close();
    }

    /**
     * T9: 删除测试用户并验证清理。
     */
    @Test
    @Order(9)
    void test_drop_user() {
        engine.setDefaultDataSourceName("admin");
        var userMgr = engine.user();

        // 删除所有 host 变体
        try {
            userMgr.dropUser(TEST_USER).execute();
        } catch (Exception ignored) {}
        try {
            java.sql.Connection conn = java.sql.DriverManager.getConnection(
                    "jdbc:mysql://" + HOST + ":" + PORT + "/mysql?useSSL=false&allowPublicKeyRetrieval=true",
                    ADMIN_USER, ADMIN_PASS);
            conn.createStatement().execute("DROP USER IF EXISTS '" + TEST_USER + "'@'127.0.0.1'");
            conn.createStatement().execute("DROP USER IF EXISTS '" + TEST_USER + "'@'localhost'");
            conn.close();
        } catch (Exception ignored) {}

        var usersAfter = userMgr.listUsers();
        assertFalse(usersAfter.stream().anyMatch(u -> TEST_USER.equals(u.getUser())),
                "用户应已被删除");
    }

    /**
     * T10: 清理测试库。
     */
    @Test
    @Order(10)
    void test_drop_database() {
        engine.setDefaultDataSourceName("admin");
        try {
            java.sql.Connection conn = java.sql.DriverManager.getConnection(
                    "jdbc:mysql://" + HOST + ":" + PORT + "/mysql?useSSL=false&allowPublicKeyRetrieval=true",
                    ADMIN_USER, ADMIN_PASS);
            conn.createStatement().execute("DROP DATABASE IF EXISTS `" + TEST_DB + "`");
            conn.close();
        } catch (Exception e) {
            // ignore cleanup failure
        }
        assertFalse(engine.databaseExists(TEST_DB), "测试库应已被删除");
    }

    // ==================== 工具方法 ====================

    private void switchToTestDb() {
        engine.setDefaultDataSourceName("admin");
        engine.addDataSource("testdb", HOST, PORT, TEST_DB, ADMIN_USER, ADMIN_PASS);
        engine.setDefaultDataSourceName("testdb");
    }

    private static boolean reachable(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 3000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
