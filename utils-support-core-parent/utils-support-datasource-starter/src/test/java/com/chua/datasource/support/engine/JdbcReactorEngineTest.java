package com.chua.datasource.support.engine;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JdbcReactorEngine 单元测试
 */
class JdbcReactorEngineTest {

    @Test
    void testSingleDataSource() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("default", "r2dbc:h2:mem://testdb");

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
    }

    @Test
    void testNativeSqlQueryWithH2() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("default", "r2dbc:h2:mem://testquery");

        Mono<Integer> createResult = engine.execute("CREATE TABLE test_user (id INT PRIMARY KEY, name VARCHAR(50))");
        StepVerifier.create(createResult).expectNext(0).verifyComplete();

        Mono<Integer> insertResult = engine.execute("INSERT INTO test_user (id, name) VALUES (1, '张三')");
        StepVerifier.create(insertResult).expectNext(1).verifyComplete();

        Flux<Map<String, Object>> queryResult = engine.query("SELECT * FROM test_user");
        StepVerifier.create(queryResult)
                .expectNextMatches(row -> row.get("ID").equals(1) && "张三".equals(row.get("NAME")))
                .verifyComplete();
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
    void testQueryWithParameters() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("default", "r2dbc:h2:mem://testparam");

        engine.execute("CREATE TABLE users (id INT, name VARCHAR(50))").block();
        engine.execute("INSERT INTO users (id, name) VALUES (1, 'Alice'), (2, 'Bob')").block();

        Flux<Map<String, Object>> result = engine.query("SELECT * FROM users WHERE name = ?", "Alice");
        List<Map<String, Object>> list = result.collectList().block();

        assertNotNull(list);
        assertEquals(1, list.size());
    }

    @Test
    void testCloseReleaseResources() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("default", "r2dbc:h2:mem://testclose");

        engine.close();
        assertNull(engine.getDefaultDataSourceName());
    }
}