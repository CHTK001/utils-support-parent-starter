package com.chua.datasource.support.engine;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JdbcReactorEngine 单元测试（使用 R2DBC H2 内联模式）
 */
class JdbcReactorEngineTest {

    // H2 R2DBC URL 格式：r2dbc:h2:mem://databaseName
    private static final String H2_R2DBC_URL = "r2dbc:h2:mem://testdb";

    @Test
    void testSingleDataSource() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("default", H2_R2DBC_URL);

        assertEquals("default", engine.getDefaultDataSourceName());
        assertNotNull(engine.getR2dbcFactory("default"));
        assertNotNull(engine.getDialect("default"));
    }

    @Test
    void testMultipleDataSources() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("h2_1", "r2dbc:h2:mem://test1");
        engine.addDataSource("h2_2", "r2dbc:h2:mem://test2");

        assertTrue(engine.isMultiDataSource());
        assertNotNull(engine.getR2dbcFactory("h2_1"));
        assertNotNull(engine.getR2dbcFactory("h2_2"));
    }

    @Test
    void testDialectDetection() {
        JdbcReactorEngine engine = new JdbcReactorEngine();

        engine.addDataSource("mysql", "jdbc:mysql://localhost:3306/mydb", "user", "pwd");
        assertNotNull(engine.getDialect("mysql"));

        engine.addDataSource("pg", "jdbc:postgresql://localhost:5432/mydb", "user", "pwd");
        assertNotNull(engine.getDialect("pg"));

        engine.addDataSource("h2", "jdbc:h2:mem:test", null, null);
        assertNotNull(engine.getDialect("h2"));
    }

    @Test
    void testNativeSqlQueryWithH2() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("default", "r2dbc:h2:mem://testquery");

        // 创建表并插入数据
        Mono<Integer> createResult = engine.execute("CREATE TABLE test_user (id INT PRIMARY KEY, name VARCHAR(50))");
        StepVerifier.create(createResult).expectNext(0).verifyComplete();

        Mono<Integer> insertResult = engine.execute("INSERT INTO test_user (id, name) VALUES (1, '张三')");
        StepVerifier.create(insertResult).expectNext(1).verifyComplete();

        insertResult = engine.execute("INSERT INTO test_user (id, name) VALUES (2, '李四')");
        StepVerifier.create(insertResult).expectNext(1).verifyComplete();

        // 查询
        Flux<Map<String, Object>> queryResult = engine.query("SELECT * FROM test_user ORDER BY id");
        StepVerifier.create(queryResult)
                .expectNextMatches(row -> row.get("id").equals(1) && "张三".equals(row.get("name")))
                .expectNextMatches(row -> row.get("id").equals(2) && "李四".equals(row.get("name")))
                .verifyComplete();
    }

    @Test
    void testNativeSqlQueryTypedWithH2() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("default", "r2dbc:h2:mem://testtyped");

        engine.execute("CREATE TABLE test_obj (id INT, value VARCHAR(50))").block();
        engine.execute("INSERT INTO test_obj (id, value) VALUES (1, 'hello'), (2, 'world')").block();

        Flux<Map<String, Object>> result = engine.query("SELECT * FROM test_obj ORDER BY id");
        List<Map<String, Object>> list = result.collectList().block();

        assertNotNull(list);
        assertEquals(2, list.size());
        assertEquals(1, list.get(0).get("id"));
        assertEquals("hello", list.get(0).get("value"));
    }

    @Test
    void testExecuteReturnsAffectedRows() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("default", "r2dbc:h2:mem://testexec");

        Mono<Integer> create = engine.execute("CREATE TABLE t (id INT)");
        StepVerifier.create(create).expectNext(0).verifyComplete();

        Mono<Integer> insert = engine.execute("INSERT INTO t (id) VALUES (1), (2), (3)");
        StepVerifier.create(insert).expectNext(3).verifyComplete();
    }

    @Test
    void testBatchExecute() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("default", "r2dbc:h2:mem://testbatch");

        engine.execute("CREATE TABLE batch_test (id INT, val VARCHAR(50))").block();

        Flux<Integer> batchResult = engine.batch(
                "INSERT INTO batch_test (id, val) VALUES (?, ?)",
                List.of(new Object[]{1, "a"}, new Object[]{2, "b"}, new Object[]{3, "c"})
        );

        StepVerifier.create(batchResult).expectNext(1, 1, 1).verifyComplete();
    }

    @Test
    void testQueryWithParameters() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("default", "r2dbc:h2:mem://testparam");

        engine.execute("CREATE TABLE named_users (id INT, name VARCHAR(50))").block();
        engine.execute("INSERT INTO named_users (id, name) VALUES (1, 'Alice'), (2, 'Bob'), (3, 'Alice')").block();

        Flux<Map<String, Object>> result = engine.query("SELECT * FROM named_users WHERE name = ?", "Alice");
        List<Map<String, Object>> list = result.collectList().block();

        assertNotNull(list);
        assertEquals(2, list.size());
    }

    @Test
    void testCloseReleaseResources() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("default", "r2dbc:h2:mem://testclose");

        engine.close();
        assertNull(engine.getDefaultDataSourceName());
    }

    @Test
    void testGetDataSource() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("default", "r2dbc:h2:mem://testds");

        var ds = engine.getDataSource("default");
        assertNotNull(ds);
        assertEquals("default", ds.name());
        assertNotNull(ds.getDialect());
    }
}