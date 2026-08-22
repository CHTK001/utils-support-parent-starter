package com.chua.datasource.support.engine;

import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static io.r2dbc.spi.ConnectionFactoryOptions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * JdbcReactorEngine 单元测试，覆盖方言检测、URL转换、H2 执行路径。
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

    // ==================== 方言检测（通过 JDBC URL 自动识别） ====================

    @Test
    void testDialectDetection_mysql() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("mysql", "jdbc:mysql://localhost:3306/mydb", "user", "pwd");
        assertNotNull(engine.getDialect("mysql"));
        assertEquals("mysql", engine.getDialect("mysql").protocol());
    }

    @Test
    void testDialectDetection_postgresql() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("pg", "jdbc:postgresql://localhost:5432/mydb", "user", "pwd");
        assertNotNull(engine.getDialect("pg"));
        assertEquals("postgresql", engine.getDialect("pg").protocol());
    }

    @Test
    void testDialectDetection_sqlserver() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("sqlserver", "jdbc:sqlserver://localhost:1433;databaseName=mydb", "user", "pwd");
        assertNotNull(engine.getDialect("sqlserver"));
        assertEquals("sqlserver", engine.getDialect("sqlserver").protocol());
    }

    @Test
    void testDialectDetection_oracle_viaConnectionFactory() {
        // Oracle thin URL 无法转为 R2DBC URL，通过 ConnectionFactory 重载验证方言识别
        JdbcReactorEngine engine = new JdbcReactorEngine();
        ConnectionFactory fakeFactory = createFakeH2Factory();
        engine.addDataSource("oracle", fakeFactory, new com.chua.datasource.support.dialect.Oracle12cDialect());
        assertEquals("oracle12c", engine.getDialect("oracle").protocol());
    }

    @Test
    void testDialectDetection_h2() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("h2", "jdbc:h2:mem://testh2");
        assertNotNull(engine.getDialect("h2"));
        assertEquals("h2", engine.getDialect("h2").protocol());
    }

    // ==================== JDBC URL 自动转换 ====================

    @Test
    void testJdbcUrlConversion_mysql() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("mysql", "jdbc:mysql://localhost:3306/mydb", "user", "pwd");
        assertNotNull(engine.getR2dbcFactory("mysql"));
        assertEquals("mysql", engine.getDialect("mysql").protocol());
    }

    @Test
    void testJdbcUrlConversion_postgresql() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("pg", "jdbc:postgresql://localhost:5432/mydb", "user", "pwd");
        assertNotNull(engine.getR2dbcFactory("pg"));
        assertEquals("postgresql", engine.getDialect("pg").protocol());
    }

    @Test
    void testJdbcUrlConversion_sqlserver() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("sqlserver", "jdbc:sqlserver://localhost:1433;databaseName=mydb", "user", "pwd");
        assertNotNull(engine.getR2dbcFactory("sqlserver"));
        assertEquals("sqlserver", engine.getDialect("sqlserver").protocol());
    }

    @Test
    void testJdbcUrlConversion_h2Mem() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("h2", "jdbc:h2:mem://conv_test_" + System.nanoTime());
        assertNotNull(engine.getR2dbcFactory("h2"));
        assertNotNull(engine.getDialect("h2"));
        engine.execute("CREATE TABLE t (id INT)").block();
    }

    // ==================== MariaDB / Oracle 绕过驱动缺失的测试 ====================

    @Test
    void testDialectDetection_mariadb() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        ConnectionFactory fakeFactory = createFakeH2Factory();
        engine.addDataSource("mariadb", fakeFactory, new com.chua.datasource.support.dialect.MariaDbDialect());
        assertEquals("mariadb", engine.getDialect("mariadb").protocol());
    }

    @Test
    void testOracleThinUrlCannotConvertToR2dbc() {
        // Oracle thin URL 格式不兼容 R2DBC URL 规范，addDataSource 会抛出 IllegalArgumentException
        JdbcReactorEngine engine = new JdbcReactorEngine();
        try {
            engine.addDataSource("oracle", "jdbc:oracle:thin:@localhost:1521:orcl", "user", "pwd");
            fail("Expected IllegalArgumentException for oracle thin URL");
        } catch (IllegalArgumentException e) {
            /* r2dbc:oracle:thin: 不是合法 R2DBC URL，符合预期 */
        }
    }

    // ==================== H2 R2DBC 执行路径 ====================

    @Test
    void testNativeSqlQueryWithH2() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("default", "r2dbc:h2:mem://testquery_" + System.nanoTime());

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
        engine.addDataSource("default", "r2dbc:h2:mem://testexec_" + System.nanoTime());

        Mono<Integer> create = engine.execute("CREATE TABLE t (id INT)");
        StepVerifier.create(create).expectNext(0).verifyComplete();

        Mono<Integer> insert = engine.execute("INSERT INTO t (id) VALUES (1), (2), (3)");
        StepVerifier.create(insert).expectNext(3).verifyComplete();
    }

    @Test
    void testQueryWithParameters() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("default", "r2dbc:h2:mem://testparam_" + System.nanoTime());

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
        engine.addDataSource("default", "r2dbc:h2:mem://testclose_" + System.nanoTime());

        engine.close();
        assertNull(engine.getDefaultDataSourceName());
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建一个指向 H2 内存库的假 ConnectionFactory，仅用于绕过无 R2DBC 驱动的测试。
     */
    private static ConnectionFactory createFakeH2Factory() {
        return io.r2dbc.spi.ConnectionFactories.get(
                io.r2dbc.spi.ConnectionFactoryOptions.builder()
                        .option(DRIVER, "h2")
                        .option(PROTOCOL, "mem")
                        .option(DATABASE, "fake_for_dialect_test_" + System.nanoTime())
                        .build());
    }
}
