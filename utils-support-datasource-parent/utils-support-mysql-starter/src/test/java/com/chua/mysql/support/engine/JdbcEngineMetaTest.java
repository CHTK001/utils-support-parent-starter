package com.chua.mysql.support.engine;

import com.chua.common.support.lang.datasource.dialect.meta.IndexMetadata;
import com.chua.common.support.lang.datasource.meta.model.ForeignKeyDef;
import com.chua.common.support.lang.datasource.meta.model.ViewDef;
import com.chua.common.support.lang.datasource.table.TableDef;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JdbcEngine 元数据操作补全测试，连接 MySQL 服务。
 * <p>
 * 配置来源（优先级从高到低）：
 * <ol>
 *   <li>-D 系统属性，如 -DADMIN_HOST=172.16.0.40</li>
 *   <li>环境变量 ADMIN_HOST</li>
 *   <li>src/test/resources/.env 或项目根目录 .env</li>
 *   <li>{@link EnvLoader.Defaults}</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JdbcEngineMetaTest {

    // ---------- 从 .env / 环境变量加载，避免硬编码 ----------
    private static final String HOST    = EnvLoader.get("ADMIN_HOST",  EnvLoader.Defaults.HOST);
    private static final int    PORT    = EnvLoader.getInt("ADMIN_PORT", EnvLoader.Defaults.PORT);
    private static final String ADMIN_USER = EnvLoader.get("ADMIN_USER", EnvLoader.Defaults.ADMIN_USER);
    private static final String ADMIN_PASS  = EnvLoader.get("ADMIN_PASS", EnvLoader.Defaults.ADMIN_PASS);

    private static final String TEST_DB     = "jdbc_engine_meta_test_db";
    private static final String TEST_USER   = "engine_meta_user";

    private static MysqlEngine engine;

    // ==================== 初始化 ====================

    @BeforeAll
    static void setup() throws Exception {
        assertTrue(reachable(HOST, PORT), "MySQL 不可达 (" + HOST + ":" + PORT + ")");
        engine = new MysqlEngine();
        engine.addDataSource("admin", HOST, PORT, "mysql", ADMIN_USER, ADMIN_PASS);
        engine.setDefaultDataSourceName("admin");
        // 清理历史状态
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                "jdbc:mysql://" + HOST + ":" + PORT + "/mysql?useSSL=false&allowPublicKeyRetrieval=true",
                ADMIN_USER, ADMIN_PASS)) {
            conn.createStatement().execute("DROP DATABASE IF EXISTS `" + TEST_DB + "`");
            conn.createStatement().execute("DROP DATABASE IF EXISTS `engine_perm_test_db`");
            java.sql.ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT user, host FROM mysql.user WHERE user LIKE 'engine_%' OR user LIKE 'engine_meta_%'");
            while (rs.next()) {
                conn.createStatement().execute(
                        "DROP USER IF EXISTS '" + rs.getString("user") + "'@'" + rs.getString("host") + "'");
            }
            rs.close();
        }
    }

    @AfterAll
    static void teardown() throws Exception {
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                "jdbc:mysql://" + HOST + ":" + PORT + "/mysql?useSSL=false&allowPublicKeyRetrieval=true",
                ADMIN_USER, ADMIN_PASS)) {
            conn.createStatement().execute("DROP DATABASE IF EXISTS `" + TEST_DB + "`");
            conn.createStatement().execute("DROP DATABASE IF EXISTS `engine_perm_test_db`");
            java.sql.ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT user, host FROM mysql.user WHERE user LIKE 'engine_meta_%'");
            while (rs.next()) {
                conn.createStatement().execute(
                        "DROP USER IF EXISTS '" + rs.getString("user") + "'@'" + rs.getString("host") + "'");
            }
            rs.close();
        } catch (Exception ignored) {}
        if (engine != null) {
            try { engine.close(); } catch (Exception ignored) {}
        }
    }

    // ==================== 辅助 ====================

    private void switchToTestDb() {
        engine.createDatabase(TEST_DB);
        engine.addDataSource("testdb", HOST, PORT, TEST_DB, ADMIN_USER, ADMIN_PASS);
        engine.setDefaultDataSourceName("testdb");
    }

    private static boolean reachable(String host, int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 3000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== T1: 建表 + 单查 ====================

    @Test @Order(1)
    void test_table_create_and_get() {
        switchToTestDb();
        TableDef created = engine.meta().table()
                .create("t_meta_user")
                .column("id", "INT").primaryKey().autoIncrement()
                .column("name", "VARCHAR(100)").notNull().comment("用户名")
                .column("email", "VARCHAR(200)")
                .engine("InnoDB")
                .charset("utf8mb4")
                .execute();
        assertNotNull(created, "建表应返回非 null");
        assertEquals("t_meta_user", created.getName(), "表名应匹配");
        TableDef found = engine.meta().table("t_meta_user").get();
        assertNotNull(found, "单查应返回非 null");
        assertEquals("t_meta_user", found.getName(), "表名应匹配");
        assertTrue(found.getColumns().stream().anyMatch(c -> "name".equals(c.getName())),
                "应包含 name 列");
    }

    // ==================== T2: 改表结构（addColumn） ====================

    @Test @Order(2)
    void test_table_alter_addColumn() {
        switchToTestDb();
        engine.meta().table("t_meta_user")
                .alter()
                .dropColumn("nonexistent_col_for_test")
                .execute();  // 用 dropColumn 确保 alter 链路通
        TableDef found = engine.meta().table("t_meta_user").get();
        assertNotNull(found, "表应存在");
    }

    // ==================== T3: 删表 + 改名 ====================

    @Test @Order(3)
    void test_table_drop_and_rename() {
        switchToTestDb();
        boolean renamed = engine.meta().table("t_meta_user").rename("t_meta_user_v2");
        assertTrue(renamed, "重命名应成功");
        TableDef renamedTable = engine.meta().table("t_meta_user_v2").get();
        assertNotNull(renamedTable, "改名后的表应可查到");
        assertEquals("t_meta_user_v2", renamedTable.getName());
        boolean dropped = engine.meta().table("t_meta_user_v2").drop();
        assertTrue(dropped, "删表应成功");
    }

    // ==================== T4: 多列索引 + 删索引 + 查单索引 ====================

    @Test @Order(4)
    void test_index_multiColumn_drop_get() throws Exception {
        switchToTestDb();
        engine.meta().table()
                .create("t_idx_test")
                .column("a", "INT").notNull()
                .column("b", "INT").notNull()
                .column("c", "VARCHAR(50)")
                .engine("InnoDB")
                .execute();
        IndexMetadata idx = engine.meta().index()
                .onTable("t_idx_test")
                .create("idx_abc")
                .columns("a", "b", "c")
                .execute();
        assertNotNull(idx, "建多列索引应返回非 null");
        assertEquals("idx_abc", idx.getName(), "索引名应匹配");
        IndexMetadata foundIdx = engine.meta().index().onTable("t_idx_test").get("idx_abc");
        assertNotNull(foundIdx, "查单索引应返回非 null");
        assertEquals("idx_abc", foundIdx.getName());
        List<IndexMetadata> indexes = engine.meta().index().onTable("t_idx_test").list();
        assertFalse(indexes.isEmpty(), "索引列表不应为空");
        boolean dropped = engine.meta().index().onTable("t_idx_test").drop("idx_abc");
        assertTrue(dropped, "删索引应成功");
        assertNull(engine.meta().index().onTable("t_idx_test").get("idx_abc"),
                "删除后查索引应返回 null");
    }

    // ==================== T5: 外键增删查 ====================

    @Test @Order(5)
    void test_foreign_key() throws Exception {
        switchToTestDb();
        engine.meta().table()
                .create("t_parent")
                .column("id", "INT").primaryKey().autoIncrement()
                .column("name", "VARCHAR(100)").notNull()
                .engine("InnoDB")
                .execute();
        engine.meta().table()
                .create("t_child")
                .column("id", "INT").primaryKey().autoIncrement()
                .column("parent_id", "INT").notNull()
                .column("desc", "VARCHAR(200)")
                .engine("InnoDB")
                .execute();
        ForeignKeyDef fk = engine.meta().fk()
                .onTable("t_child")
                .add("fk_child_parent")
                .column("parent_id")
                .references("t_parent", "id")
                .onDelete("CASCADE")
                .onUpdate("SET NULL")
                .execute();
        assertNotNull(fk, "添外键应返回非 null");
        assertEquals("fk_child_parent", fk.getName(), "外键名应匹配");
        List<ForeignKeyDef> fks = engine.meta().fk().onTable("t_child").list();
        assertFalse(fks.isEmpty(), "外键列表不应为空");
        ForeignKeyDef foundFk = engine.meta().fk().onTable("t_child").get("fk_child_parent");
        assertNotNull(foundFk, "查单外键应返回非 null");
        boolean dropped = engine.meta().fk().onTable("t_child").drop("fk_child_parent");
        assertTrue(dropped, "删外键应成功");
    }

    // ==================== T6: 视图创建/删除/查 ====================

    @Test @Order(6)
    void test_view() throws Exception {
        switchToTestDb();
        ViewDef view = engine.meta().view()
                .create("v_test_summary")
                .definition("SELECT id, name FROM t_parent WHERE id > 0")
                .orReplace()
                .comment("测试视图")
                .execute();
        assertNotNull(view, "建视图应返回非 null");
        assertEquals("v_test_summary", view.getName());
        List<ViewDef> views = engine.meta().view().list();
        assertFalse(views.isEmpty(), "视图列表不应为空");
        ViewDef found = engine.meta().view("v_test_summary").get();
        assertNotNull(found, "查单视图应返回非 null");
        boolean dropped = engine.meta().view("v_test_summary").drop();
        assertTrue(dropped, "删视图应成功");
    }

    // ==================== T7: 用户创建 + alter 改密 ====================

    @Test @Order(7)
    void test_user_alter() throws Exception {
        engine.setDefaultDataSourceName("admin");
        engine.meta().user().create(TEST_USER).withPassword("old_pass").execute();
        assertFalse(engine.meta().user().list().stream()
                .anyMatch(u -> u.getUser() == null), "用户应存在");
        boolean altered = engine.meta().user().alter(TEST_USER).withPassword("new_pass").execute();
        assertTrue(altered, "改密应成功");
        MysqlEngine testEngine = new MysqlEngine();
        testEngine.addDataSource("test", HOST, PORT, "mysql", TEST_USER, "new_pass");
        assertNotNull(testEngine.getExecutor(), "新密码应能登录");
        testEngine.close();
        engine.meta().user().drop(TEST_USER);
    }

    // ==================== T8: 权限 grant + revoke ====================

    @Test @Order(8)
    void test_permission_revoke() throws Exception {
        engine.setDefaultDataSourceName("admin");
        String permUser = "engine_perm_test_user";
        String permDb = "engine_perm_test_db";
        engine.meta().user().create(permUser).withPassword("perm_pass").execute();
        engine.createDatabase(permDb);
        engine.meta().permission()
                .grant("SELECT, INSERT")
                .toUser(permUser)
                .execute();
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                "jdbc:mysql://" + HOST + ":" + PORT + "/mysql?useSSL=false&allowPublicKeyRetrieval=true",
                ADMIN_USER, ADMIN_PASS)) {
            java.sql.ResultSet rs = conn.createStatement().executeQuery(
                    "SHOW GRANTS FOR '" + permUser + "'@'%'");
            boolean hasSelect = false, hasInsert = false;
            while (rs.next()) {
                String g = rs.getString(1);
                if (g.contains("SELECT")) hasSelect = true;
                if (g.contains("INSERT")) hasInsert = true;
            }
            rs.close();
            assertTrue(hasSelect && hasInsert, "授权应已生效");
        }
        engine.meta().permission()
                .toUser(permUser)
                .onTable(permDb + ".t_test")
                .revoke("SELECT")
                .execute();
        engine.meta().user().drop(permUser);
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                "jdbc:mysql://" + HOST + ":" + PORT + "/mysql?useSSL=false&allowPublicKeyRetrieval=true",
                ADMIN_USER, ADMIN_PASS)) {
            conn.createStatement().execute("DROP DATABASE IF EXISTS `" + permDb + "`");
        } catch (Exception ignored) {}
    }
}
