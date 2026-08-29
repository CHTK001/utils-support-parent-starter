package com.chua.datasource.support.engine;

import com.chua.datasource.support.ddl.DslManager;
import com.chua.datasource.support.user.UserInfo;
import com.chua.datasource.support.user.UserManager;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Engine 能力入口集成测试：flyway（flylink SQL 文件导入）、ddl()、user()。
 */
class JdbcEngineEntryIT {

    private static int COUNTER = 0;

    private JdbcReactorEngine h2Engine() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("h2", "r2dbc:h2:mem://entry_" + (COUNTER++));
        return engine;
    }

    // ==================== flyway：SQL 文件导入 ====================

    @Test
    void flyway_migrate_appliesVersionedScriptsInOrder(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("V1__create_user.sql"), """
                CREATE TABLE featuser (id INT PRIMARY KEY, name VARCHAR(50));
                """);
        Files.writeString(dir.resolve("V2__seed_user.sql"), """
                -- 初始化数据
                INSERT INTO featuser (id, name) VALUES (1, 'Fly');
                /* 块注释 */
                INSERT INTO featuser (id, name) VALUES (2, 'Way');
                """);

        JdbcReactorEngine engine = h2Engine();
        try {
            var flyway = engine.flyway().location(dir.toString());

            /* 首次迁移：应用全部脚本 */
            int applied = flyway.migrate();
            assertEquals(2, applied);

            List<Map<String, Object>> rows = engine.query("SELECT id, name FROM featuser ORDER BY id")
                    .collectList().block();
            assertNotNull(rows);
            assertEquals(2, rows.size());
            assertEquals("Fly", rows.get(0).get("NAME"));

            /* 幂等：重复 migrate 不再执行 */
            assertEquals(0, flyway.migrate());

            /* info() 记录已应用的迁移 */
            List<com.chua.common.support.lang.datasource.flyway.MigrationInfo> infos = flyway.info();
            assertEquals(2, infos.size());
        } finally {
            engine.close();
        }
    }

    @Test
    void flyway_execute_singleScriptWithoutHistory(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path script = dir.resolve("V1__init.sql");
        Files.writeString(script, "CREATE TABLE single_exec (id INT PRIMARY KEY);"
                + "INSERT INTO single_exec VALUES (9);");

        JdbcReactorEngine engine = h2Engine();
        try {
            int statements = engine.flyway().execute(script);
            assertTrue(statements >= 1);

            Map<String, Object> row = engine.query("SELECT COUNT(*) AS cnt FROM single_exec")
                    .next().block();
            assertNotNull(row);
        } finally {
            engine.close();
        }
    }

    // ==================== ddl() / user() 入口 ====================

    @Test
    void user_h2_resolvesSpiAndWiresDataSource() {
        /* JDBC URL 形式注册 → 同时创建 R2DBC 工厂与 JDBC DataSource（供 DataSourceAware 注入） */
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("h2", "jdbc:h2:mem:entry_user_" + (COUNTER++) + ";DB_CLOSE_DELAY=-1", "sa", "");
        try {
            UserManager manager = engine.user();
            assertNotNull(manager);
            assertEquals("h2", manager.type());

            /* 注入的 DataSource 可用：能查到当前用户 */
            List<UserInfo> users = manager.listUsers();
            assertFalse(users.isEmpty());
        } finally {
            engine.close();
        }
    }

    @Test
    void user_h2_lifecycle_createAlterDrop() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("h2", "jdbc:h2:mem:entry_lc_" + (COUNTER++) + ";DB_CLOSE_DELAY=-1", "sa", "");
        try {
            UserManager manager = engine.user();
            String name = "TEST_USER_" + (COUNTER++);

            manager.createUser(name).withPassword("secret").execute();
            assertTrue(manager.listUsers().stream().anyMatch(u -> u.getUser().equalsIgnoreCase(name)));

            manager.alterUser(name).withPassword("new-secret").execute();
            manager.dropUser(name).execute();
            assertFalse(manager.listUsers().stream().anyMatch(u -> u.getUser().equalsIgnoreCase(name)));
        } finally {
            engine.close();
        }
    }

    @Test
    void ddl_h2_resolvesSpiAndReadsMetadata() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("h2", "jdbc:h2:mem:entry_ddl_" + (COUNTER++) + ";DB_CLOSE_DELAY=-1", "sa", "");
        try {
            DslManager manager = engine.ddl();
            assertNotNull(manager);
            assertEquals("h2", manager.type());

            engine.execute("CREATE TABLE ddl_probe (id INT PRIMARY KEY, name VARCHAR(30))").block();

            /* listTables 经注入的 DataSource 读取元数据 */
            assertTrue(manager.listTables(null, "PUBLIC").stream()
                    .anyMatch(t -> "DDL_PROBE".equalsIgnoreCase(t.getName())));
            assertNotNull(manager.getTable(null, "PUBLIC", "DDL_PROBE"));

            /* DDL 生成 */
            String ddl = manager.createTableDDL(null, "PUBLIC", "DDL_PROBE");
            assertTrue(ddl.startsWith("CREATE TABLE"));
            assertEquals("ALTER TABLE old RENAME TO new;", manager.renameTable(null, "old", "new"));
        } finally {
            engine.close();
        }
    }

    @Test
    void ddl_noSpiForDialect_throwsUnsupported() {
        /* postgresql 方言在测试类路径无 SPI 注册 → 契约抛错 */
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("pg", "jdbc:postgresql://172.16.0.40:5433/testdb", "postgres", "postgres");
        try {
            UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class, engine::ddl);
            assertTrue(ex.getMessage().contains("DdlManager"));
            assertThrows(UnsupportedOperationException.class, engine::user);
        } finally {
            engine.close();
        }
    }

    @Test
    void abstractEngine_defaults_throwUnsupported() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        AbstractEngine minimal = new AbstractEngine() {
            @Override
            protected <T> List<T> executeNewQuery(String sql, Object[] params, Class<T> rowType) {
                return List.of();
            }
        };
        assertThrows(UnsupportedOperationException.class, minimal::ddl);
        assertThrows(UnsupportedOperationException.class, minimal::user);
        engine.close();
    }
}
