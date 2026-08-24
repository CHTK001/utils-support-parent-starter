package com.chua.datasource.support.engine;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JdbcReactorEngine 全功能点集成测试。
 * 覆盖：类型映射查询（Flux&lt;T&gt;）、Lambda 包装器（query/update/delete）、
 * 分页、同步 SqlExecutor 执行链（R2DBC/JDBC 双路径）、meta 元数据行为。
 */
class JdbcEngineFeatureIT {

    private static final String H2_URL_PREFIX = "r2dbc:h2:mem://feat_";
    private static int COUNTER = 0;

    private JdbcReactorEngine h2Engine() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("h2", H2_URL_PREFIX + (COUNTER++));
        return engine;
    }

    // ==================== 类型映射查询（Flux<T>） ====================

    @Test
    void typedQuery_h2_returnsMappedObjects() {
        JdbcReactorEngine engine = h2Engine();
        engine.execute("CREATE TABLE featuser (id INT PRIMARY KEY, name VARCHAR(50), age INT)").block();
        engine.execute("INSERT INTO featuser (id, name, age) VALUES (1, 'Alice', 20), (2, 'Bob', 30)").block();

        List<FeatUser> users = engine.query("SELECT id, name, age FROM featuser WHERE age >= ?", FeatUser.class, 18)
                .collectList().block();
        assertNotNull(users);
        assertEquals(2, users.size());
        assertEquals("Alice", users.get(0).getName());
        assertEquals(30, users.get(1).getAge());
        engine.close();
    }

    @Test
    void typedQuery_h2_emptyResult() {
        JdbcReactorEngine engine = h2Engine();
        engine.execute("CREATE TABLE featuser (id INT PRIMARY KEY, name VARCHAR(50), age INT)").block();

        List<FeatUser> users = engine.query("SELECT id, name, age FROM featuser", FeatUser.class)
                .collectList().block();
        assertNotNull(users);
        assertTrue(users.isEmpty());
        engine.close();
    }

    @Test
    void typedQuery_mysql_jdbcPathMapping() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        try {
            engine.addDataSource("mysql",
                    "jdbc:mysql://172.16.0.40:3306/report?useSSL=false&allowPublicKeyRetrieval=true",
                    "root", "root@");
            engine.execute("DROP TABLE IF EXISTS jte_feat_typed").block();
            engine.execute("CREATE TABLE jte_feat_typed (id INT PRIMARY KEY, name VARCHAR(50), age INT)").block();
            engine.execute("INSERT INTO jte_feat_typed (id, name, age) VALUES (1, 'Carol', 25)").block();

            List<FeatUser> users = engine.query("SELECT id, name, age FROM jte_feat_typed WHERE id = ?", FeatUser.class, 1)
                    .collectList().block();
            assertNotNull(users);
            assertEquals(1, users.size());
            assertEquals("Carol", users.get(0).getName());

            engine.execute("DROP TABLE jte_feat_typed").block();
        } finally {
            engine.close();
        }
    }

    @Test
    void typedQuery_pg_r2dbcDollarPlaceholder() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("pg", "jdbc:postgresql://172.16.0.40:5433/testdb", "postgres", "postgres");
        try {
            String tbl = "jte_feat_pg_typed_" + System.nanoTime();
            engine.execute("DROP TABLE IF EXISTS " + tbl).block();
            engine.execute("CREATE TABLE " + tbl + " (id INT PRIMARY KEY, name VARCHAR(50), age INT)").block();
            engine.execute("INSERT INTO " + tbl + " (id, name, age) VALUES ($1, $2, $3)", 7, "Dave", 40).block();

            List<FeatUser> users = engine.query("SELECT id, name, age FROM " + tbl + " WHERE id = $1", FeatUser.class, 7)
                    .collectList().block();
            assertNotNull(users);
            assertEquals(1, users.size());
            assertEquals(40, users.get(0).getAge());

            engine.execute("DROP TABLE " + tbl).block();
        } finally {
            engine.close();
        }
    }

    // ==================== Lambda 查询包装器（同步 SqlExecutor 链路） ====================

    @Test
    void lambdaQuery_h2_list_withConditions() {
        JdbcReactorEngine engine = h2Engine();
        engine.execute("CREATE TABLE featuser (id INT PRIMARY KEY, name VARCHAR(50), age INT)").block();
        engine.execute("INSERT INTO featuser (id, name, age) VALUES "
                + "(1, 'Alice', 20), (2, 'Bob', 35), (3, 'Cathy', 28)").block();

        List<FeatUser> users = engine.query(FeatUser.class)
                .ge(FeatUser::getAge, 25)
                .list()
                .collectList()
                .block();
        assertNotNull(users);
        assertEquals(2, users.size());

        engine.close();
    }

    @Test
    void lambdaQuery_h2_one() {
        JdbcReactorEngine engine = h2Engine();
        engine.execute("CREATE TABLE featuser (id INT PRIMARY KEY, name VARCHAR(50), age INT)").block();
        engine.execute("INSERT INTO featuser (id, name, age) VALUES (1, 'Alice', 20)").block();

        StepVerifier.create(engine.query(FeatUser.class).eq(FeatUser::getName, "Alice").one())
                .expectNextMatches(u -> "Alice".equals(u.getName()) && u.getAge() == 20)
                .verifyComplete();

        engine.close();
    }

    @Test
    void lambdaQuery_h2_page() {
        JdbcReactorEngine engine = h2Engine();
        engine.execute("CREATE TABLE featuser (id INT PRIMARY KEY, name VARCHAR(50), age INT)").block();
        engine.execute("INSERT INTO featuser (id, name, age) VALUES "
                + "(1, 'A1', 21), (2, 'B2', 22), (3, 'C3', 23), (4, 'D4', 24), (5, 'E5', 25)").block();

        StepVerifier.create(engine.query(FeatUser.class).page(2, 2))
                .expectNextMatches(p -> p.getRecords().size() == 2 && p.getTotal() == 5)
                .verifyComplete();

        engine.close();
    }

    @Test
    void lambdaQuery_h2_selectAndOrderBy() {
        JdbcReactorEngine engine = h2Engine();
        engine.execute("CREATE TABLE featuser (id INT PRIMARY KEY, name VARCHAR(50), age INT)").block();
        engine.execute("INSERT INTO featuser (id, name, age) VALUES (1, 'X', 50), (2, 'Y', 60)").block();

        List<FeatUser> users = engine.query(FeatUser.class)
                .select(FeatUser::getId, FeatUser::getAge)
                .orderByDesc(FeatUser::getAge)
                .list()
                .collectList()
                .block();
        assertNotNull(users);
        assertEquals(2, users.size());
        assertEquals(60, users.get(0).getAge());

        engine.close();
    }

    @Test
    void lambdaUpdate_h2_setAndExecute() {
        JdbcReactorEngine engine = h2Engine();
        engine.execute("CREATE TABLE featuser (id INT PRIMARY KEY, name VARCHAR(50), age INT)").block();
        engine.execute("INSERT INTO featuser (id, name, age) VALUES (1, 'Old', 10), (2, 'Keep', 10)").block();

        Mono<Integer> updated = engine.update(FeatUser.class)
                .set(FeatUser::getName, "New")
                .eq(FeatUser::getId, 1)
                .update();

        StepVerifier.create(updated).expectNext(1).verifyComplete();

        Map<String, Object> row = engine.query("SELECT name FROM featuser WHERE id = 1").next().block();
        assert row != null;
        assertEquals("New", row.get("NAME"));

        engine.close();
    }

    @Test
    void lambdaDelete_h2_removeByCondition() {
        JdbcReactorEngine engine = h2Engine();
        engine.execute("CREATE TABLE featuser (id INT PRIMARY KEY, name VARCHAR(50), age INT)").block();
        engine.execute("INSERT INTO featuser (id, name, age) VALUES (1, 'Del', 10), (2, 'Keep', 99)").block();

        Mono<Integer> deleted = engine.delete(FeatUser.class)
                .lt(FeatUser::getAge, 50)
                .remove();

        StepVerifier.create(deleted).expectNext(1).verifyComplete();

        List<Map<String, Object>> rest = engine.query("SELECT * FROM featuser").collectList().block();
        assert rest != null;
        assertEquals(1, rest.size());

        engine.close();
    }

    @Test
    void lambdaQuery_mysql_jdbcSyncExecutor() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        try {
            engine.addDataSource("mysql",
                    "jdbc:mysql://172.16.0.40:3306/report?useSSL=false&allowPublicKeyRetrieval=true",
                    "root", "root@");
            /* Lambda 查询按实体类名小写找表：featuser */
            engine.execute("DROP TABLE IF EXISTS featuser").block();
            engine.execute("CREATE TABLE featuser (id INT PRIMARY KEY, name VARCHAR(50), age INT)").block();
            engine.execute("INSERT INTO featuser (id, name, age) VALUES (9, 'SyncExec', 45)").block();

            FeatUser u = engine.query(FeatUser.class).eq(FeatUser::getId, 9).one().block();
            assertNotNull(u);
            assertEquals("SyncExec", u.getName());

            engine.execute("DROP TABLE featuser").block();
        } finally {
            engine.close();
        }
    }

    // ==================== meta 元数据 ====================

    @Test
    void meta_defaultImplementation_throwsUnsupported() {
        JdbcReactorEngine engine = h2Engine();
        try {
            var metaData = new com.chua.datasource.support.meta.DefaultMetaData(new MinimalEngine(engine));
            assertThrows(UnsupportedOperationException.class, metaData::table);
            assertThrows(UnsupportedOperationException.class, metaData::view);
            assertThrows(UnsupportedOperationException.class, metaData::index);
        } finally {
            engine.close();
        }
    }

    /** 测试辅助：最小 Engine 适配器 */
    static class MinimalEngine implements com.chua.common.support.lang.datasource.engine.Engine {
        private final JdbcReactorEngine delegate;
        MinimalEngine(JdbcReactorEngine delegate) { this.delegate = delegate; }

        @Override public <T> com.chua.common.support.lang.datasource.engine.Engine addDataSource(String name,
                com.chua.common.support.lang.datasource.engine.EngineDataSource<T> ds) { throw new UnsupportedOperationException(); }
        @Override public <T> com.chua.common.support.lang.datasource.engine.Engine store(String name, List<T> data) { throw new UnsupportedOperationException(); }
        @Override public com.chua.common.support.lang.datasource.engine.Engine setDefaultDataSourceName(String name) { throw new UnsupportedOperationException(); }
        @Override public com.chua.common.support.lang.datasource.engine.executor.SqlExecutor getExecutor(String dataSourceName) { throw new UnsupportedOperationException(); }
        @Override public com.chua.common.support.lang.datasource.engine.executor.SqlExecutor getExecutor() { throw new UnsupportedOperationException(); }
        @Override public <T> com.chua.common.support.lang.datasource.engine.EngineDataSource<T> getDataSource(String name) { throw new UnsupportedOperationException(); }
        @Override public <T> com.chua.common.support.lang.datasource.engine.EngineDataSource<T> getDataSource() { throw new UnsupportedOperationException(); }
        @Override public <T> com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper<T> query(Class<T> c) { throw new UnsupportedOperationException(); }
        @Override public <T> com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper<T> update(Class<T> c) { throw new UnsupportedOperationException(); }
        @Override public <T> com.chua.common.support.lang.datasource.engine.wrapper.LambdaDeleteWrapper<T> delete(Class<T> c) { throw new UnsupportedOperationException(); }
        @Override public com.chua.common.support.lang.datasource.dialect.Dialect getDialect(String n) { throw new UnsupportedOperationException(); }
        @Override public void close() { delegate.close(); }
    }

    // ==================== 多数据源联邦（JDBC 同步路径） ====================

    @Test
    void federation_multiDatasource_queryViaUnifiedJdbc() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        try {
            /* 同一宿主机两个 MySQL 库注册为两个数据源 → 触发联邦模式 */
            engine.addDataSource("ds1", "jdbc:mysql://172.16.0.40:3306/report?useSSL=false&allowPublicKeyRetrieval=true", "root", "root@");
            engine.addDataSource("ds2", "jdbc:mysql://172.16.0.40:3308/testdb?useSSL=false&allowPublicKeyRetrieval=true", "root", "root");

            assertTrue(engine.isMultiDataSource());

            List<Map<String, Object>> rows = engine.query("SELECT 1 AS v")
                    .collectList().block();
            assertNotNull(rows);
            assertFalse(rows.isEmpty());

            /* 联邦模式下 execute 走 JDBC PreparedStatement.executeUpdate，
             * 仅适用于 DML/DDL，不能执行 SELECT */
            String tbl = "jte_fed_" + System.nanoTime();
            engine.execute("CREATE TABLE " + tbl + " (id INT)").block();
            Mono<Integer> inserted = engine.execute("INSERT INTO " + tbl + " VALUES (1)");
            assertEquals(1, inserted.block());
            engine.execute("DROP TABLE " + tbl).block();
        } finally {
            engine.close();
        }
    }
}
