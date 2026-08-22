package com.chua.datasource.support.engine;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JdbcReactorEngine 完整集成测试
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>H2 内存库 — 纯 R2DBC 非阻塞执行（单数据源路径）</li>
 *   <li>MySQL 真实库 — R2DBC 非阻塞执行（单数据源路径）</li>
 *   <li>JDBC URL 自动转换 — jdbc:h2 / jdbc:mysql → r2dbc:h2 / r2dbc:mysql</li>
 *   <li>多数据源模式 — isMultiDataSource 触发联邦路径（无 DataSourceConversion SPI 时退回单数据源）</li>
 *   <li>错误处理 — 无效 SQL、缺失数据源、资源释放</li>
 * </ul>
 * </p>
 */
class JdbcReactorEngineIT {

    private static final String MYSQL_URL = "jdbc:mysql://172.16.0.40:3306/report?useSSL=false&allowPublicKeyRetrieval=true";
    private static final String MYSQL_USER = "root";
    private static final String MYSQL_PASSWORD = "root@";

    // ==================== H2 内存库（纯 R2DBC 非阻塞路径） ====================

    @Test
    void h2_createTable() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("h2", "r2dbc:h2:mem://it_h2");
        Mono<Integer> result = engine.execute("CREATE TABLE t1 (id INT PRIMARY KEY, val VARCHAR(50))");
        StepVerifier.create(result).expectNext(0).verifyComplete();
    }

    @Test
    void h2_insertAndQuery() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("h2", "r2dbc:h2:mem://it_h2_q");
        engine.execute("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(50))").block();
        engine.execute("INSERT INTO users (id, name) VALUES (1, 'Alice'), (2, 'Bob')").block();
        List<Map<String, Object>> rows = engine.query("SELECT * FROM users ORDER BY id")
                .collectList().block();
        assertNotNull(rows); assertEquals(2, rows.size());
    }

    @Test
    void h2_update() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("h2", "r2dbc:h2:mem://it_h2_u");
        engine.execute("CREATE TABLE items (id INT PRIMARY KEY, status VARCHAR(10))").block();
        engine.execute("INSERT INTO items (id, status) VALUES (1, 'pending')").block();
        Mono<Integer> updated = engine.execute("UPDATE items SET status = 'done' WHERE id = 1");
        StepVerifier.create(updated).expectNext(1).verifyComplete();
    }

    @Test
    void h2_delete() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("h2", "r2dbc:h2:mem://it_h2_d");
        engine.execute("CREATE TABLE tags (id INT PRIMARY KEY, label VARCHAR(20))").block();
        engine.execute("INSERT INTO tags (id, label) VALUES (1, 'a'), (2, 'b')").block();
        Mono<Integer> deleted = engine.execute("DELETE FROM tags WHERE id = 1");
        StepVerifier.create(deleted).expectNext(1).verifyComplete();
    }

    @Test
    void h2_batchInsert_returnsTotal() {
        // batchViaR2dbc 将所有批次行的 rowsUpdated 累加后作为单个 emission 返回
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("h2", "r2dbc:h2:mem://it_h2_b");
        engine.execute("CREATE TABLE batch_test (id INT, val VARCHAR(20))").block();
        Flux<Integer> results = engine.batch("INSERT INTO batch_test (id, val) VALUES (?, ?)",
                List.of(new Object[]{1, "x"}, new Object[]{2, "y"}, new Object[]{3, "z"}));
        // 3行 × 每行1条 = 总共3
        StepVerifier.create(results).expectNext(3).verifyComplete();
    }

    @Test
    void h2_paramQuery() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("h2", "r2dbc:h2:mem://it_h2_p");
        engine.execute("CREATE TABLE products (id INT PRIMARY KEY, name VARCHAR(50), price DECIMAL(10,2))").block();
        engine.execute("INSERT INTO products (id, name, price) VALUES (1, 'Widget', 9.99)").block();
        Flux<Map<String, Object>> result = engine.query("SELECT * FROM products WHERE id = ?", 1);
        StepVerifier.create(result)
                .expectNextMatches(row -> row.get("ID").equals(1) && "Widget".equals(row.get("NAME")))
                .verifyComplete();
    }

    @Test
    void h2_multiDataSourceFlag() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("h2a", "r2dbc:h2:mem://multi_a");
        engine.addDataSource("h2b", "r2dbc:h2:mem://multi_b");
        assertTrue(engine.isMultiDataSource());
        assertNotNull(engine.getR2dbcFactory("h2a"));
        assertNotNull(engine.getR2dbcFactory("h2b"));
    }

    // typed query 需要实体类有无参构造，Map 不是合法目标类型，跳过此测试

    // ==================== JDBC URL 自动转换 ====================

    @Test
    void jdbcUrlConversion_h2Mem_viaTwoParam() {
        // 两参 addDataSource(name, url) 应兼容传入 JDBC URL 并自动转换
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("h2", "jdbc:h2:mem://conv_test");
        assertNotNull(engine.getR2dbcFactory("h2"));
        assertNotNull(engine.getDialect("h2"));
        engine.execute("CREATE TABLE t (id INT)").block();
    }

    @Test
    void jdbcUrlConversion_mysql() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("mysql", MYSQL_URL, MYSQL_USER, MYSQL_PASSWORD);
        assertNotNull(engine.getR2dbcFactory("mysql"));
        assertNotNull(engine.getDialect("mysql"));
        assertEquals("mysql", engine.getDialect("mysql").protocol());
    }

    @Test
    void jdbcUrlConversion_postgresqlNotReachable() {
        // PostgreSQL 本地未启动，但 addDataSource 只创建 Factory 不连接，应成功
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("pg", "jdbc:postgresql://localhost:5432/test", "u", "p");
        assertNotNull(engine.getR2dbcFactory("pg"));
        assertNotNull(engine.getDialect("pg"));
    }

    // ==================== MySQL 真实库（R2DBC 非阻塞路径） ====================

    @Test
    void mysql_connectAndShowTables() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("mysql", MYSQL_URL, MYSQL_USER, MYSQL_PASSWORD);
        assertNotNull(engine.getR2dbcFactory("mysql"));

        Flux<Map<String, Object>> tables = engine.query("SHOW TABLES");
        List<Map<String, Object>> result = tables.collectList().block();
        assertNotNull(result); assertTrue(result.size() > 0);
    }

    @Test
    void mysql_selectLimit_lowercaseKeys() {
        // MySQL R2DBC 驱动返回小写字段名
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("mysql", MYSQL_URL, MYSQL_USER, MYSQL_PASSWORD);
        Flux<Map<String, Object>> result = engine.query("SELECT 1 AS one, 2 AS two");
        StepVerifier.create(result)
                .expectNextMatches(row -> row.get("one") != null && row.get("two") != null)
                .verifyComplete();
    }

    @Test
    void mysql_createInsertSelectAndDrop() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("mysql", MYSQL_URL, MYSQL_USER, MYSQL_PASSWORD);

        engine.execute("DROP TABLE IF EXISTS it_r2dbc_test").block();
        engine.execute("CREATE TABLE it_r2dbc_test (id INT PRIMARY KEY, name VARCHAR(50), ts TIMESTAMP DEFAULT CURRENT_TIMESTAMP)")
                .block();
        engine.execute("INSERT INTO it_r2dbc_test (id, name) VALUES (100, 'integration_test')").block();

        // MySQL 返回小写列名
        List<Map<String, Object>> rows = engine.query("SELECT * FROM it_r2dbc_test WHERE id = 100")
                .collectList().block();
        assertNotNull(rows); assertFalse(rows.isEmpty());
        assertEquals("integration_test", rows.get(0).get("name"));

        engine.execute("DROP TABLE it_r2dbc_test").block();
    }

    @Test
    void mysql_updateAndDelete() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("mysql", MYSQL_URL, MYSQL_USER, MYSQL_PASSWORD);

        engine.execute("DROP TABLE IF EXISTS it_update_del").block();
        engine.execute("CREATE TABLE it_update_del (id INT PRIMARY KEY, status VARCHAR(20))").block();
        engine.execute("INSERT INTO it_update_del (id, status) VALUES (1, 'pending')").block();

        // executeViaR2dbc 内部 map(Long::intValue) + reduce(0, Integer::sum) 返回 Integer
        Mono<Integer> updated = engine.execute("UPDATE it_update_del SET status = 'done' WHERE id = 1");
        StepVerifier.create(updated).expectNext(1).verifyComplete();

        // 验证更新结果
        Map<String, Object> row = engine.query("SELECT status FROM it_update_del WHERE id = 1")
                .next().block();
        assertNotNull(row); assertEquals("done", row.get("status"));

        // 删除
        Mono<Integer> deleted = engine.execute("DELETE FROM it_update_del WHERE id = 1");
        StepVerifier.create(deleted).expectNext(1).verifyComplete();

        engine.execute("DROP TABLE it_update_del").block();
    }

    @Test
    void mysql_batchInsert_returnsTotalRows() {
        // batchViaR2dbc 返回总 affected rows（单值 emission），而非逐行 emission
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("mysql", MYSQL_URL, MYSQL_USER, MYSQL_PASSWORD);

        engine.execute("DROP TABLE IF EXISTS it_batch").block();
        engine.execute("CREATE TABLE it_batch (id INT PRIMARY KEY, val VARCHAR(20))").block();

        Flux<Integer> results = engine.batch("INSERT INTO it_batch (id, val) VALUES (?, ?)",
                List.of(new Object[]{1, "a"}, new Object[]{2, "b"}, new Object[]{3, "c"}));
        // 3行 × 每行1条 = 总3
        StepVerifier.create(results).expectNext(3).verifyComplete();

        Map<String, Object> cntRow = engine.query("SELECT COUNT(*) AS cnt FROM it_batch")
                .next().block();
        assertNotNull(cntRow);
        engine.execute("DROP TABLE it_batch").block();
    }

    @Test
    void mysql_paramQueryWithSpecialChars() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("mysql", MYSQL_URL, MYSQL_USER, MYSQL_PASSWORD);

        engine.execute("DROP TABLE IF EXISTS it_params").block();
        engine.execute("CREATE TABLE it_params (id INT PRIMARY KEY, content VARCHAR(200))").block();
        engine.execute("INSERT INTO it_params (id, content) VALUES (1, 'hello & < > \"test')")
                .block();

        Flux<Map<String, Object>> result = engine.query("SELECT * FROM it_params WHERE id = ?", 1);
        StepVerifier.create(result)
                .expectNextMatches(row -> row.get("content") != null && row.get("content").toString().contains("hello"))
                .verifyComplete();

        engine.execute("DROP TABLE it_params").block();
    }

    // ==================== 错误处理 ====================

    @Test
    void executeInvalidSql() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("h2", "r2dbc:h2:mem://it_err");
        Mono<Integer> result = engine.execute("INVALID SQL HERE");
        StepVerifier.create(result).expectError().verify();
    }

    @Test
    void queryOnMissingDataSource_throwsIllegalState() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        // 未添加任何数据源，defaultDataSourceName = null
        // queryViaR2dbc 中 factory == null → IllegalStateException
        Flux<Map<String, Object>> result = engine.query("SELECT 1");
        StepVerifier.create(result).expectError(IllegalStateException.class).verify();
    }

    @Test
    void closeReleasesResources() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("h2", "r2dbc:h2:mem://it_close");
        engine.close();
        assertNull(engine.getDefaultDataSourceName());
    }
}