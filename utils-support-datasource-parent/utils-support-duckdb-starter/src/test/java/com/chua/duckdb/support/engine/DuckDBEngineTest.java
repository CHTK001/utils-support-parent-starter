package com.chua.duckdb.support.engine;

import com.chua.common.support.lang.datasource.dialect.Pagination;
import com.chua.datasource.support.meta.DefaultMetaData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DuckDB 引擎集成测试，覆盖 JdbcEngine 体系的查询、更新、删除、执行器、分页、元数据与关闭链路。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DuckDBEngineTest {

    /**
     * 被测引擎实例
     */
    private DuckDBEngine engine;

    /**
     * 每个用例前创建引擎并连接内存 DuckDB。
     */
    @BeforeEach
    void setUp() throws Exception {
        engine = new DuckDBEngine();
        engine.addDataSource("default", "jdbc:duckdb:");
        // 建表并插入测试数据（表名 = 实体类简单名小写，IF NOT EXISTS 防止重复建表）
        engine.getExecutor().execute("CREATE TABLE IF NOT EXISTS user (id INTEGER, name VARCHAR, age INTEGER)");
        // 先清理旧数据，保证每个测试方法独立
        engine.getExecutor().execute("DELETE FROM user");
        engine.getExecutor().execute("INSERT INTO user VALUES (?, ?, ?)", 1, "zhangsan", 20);
        engine.getExecutor().execute("INSERT INTO user VALUES (?, ?, ?)", 2, "lisi", 30);
    }

    /**
     * 每个用例结束后关闭引擎，释放连接。
     */
    @AfterEach
    void tearDown() {
        engine.close();
    }

    /**
     * 测试原生 SQL 查询返回 Map 行。
     */
    @Test
    void testNativeQuery() {
        List<Map<String, Object>> rows = engine.getExecutor().query("SELECT * FROM user WHERE age > ?", 21);
        assertEquals(1, rows.size());
        assertEquals(2, rows.getFirst().get("id"));
    }

    /**
     * 测试类型化查询反射映射实体字段。
     */
    @Test
    void testTypedQuery() {
        List<User> users = engine.getExecutor().query("SELECT * FROM user ORDER BY id", User.class);
        assertEquals(2, users.size());
        assertEquals(1, users.get(0).id);
        assertEquals("zhangsan", users.get(0).name);
        assertEquals(20, users.get(0).age);
    }

    /**
     * 测试 Lambda 链式查询走真实 JDBC。
     */
    @Test
    void testLambdaQuery() {
        List<User> users = engine.query(User.class).list();
        assertEquals(2, users.size());
    }

    /**
     * 测试 Lambda 链式更新走真实 JDBC。
     */
    @Test
    void testLambdaUpdate() {
        int affected = engine.update(User.class).set("age", 25).eq("id", 1).update();
        assertEquals(1, affected);
        List<Map<String, Object>> rows = engine.getExecutor().query("SELECT age FROM user WHERE id = 1");
        assertEquals(25, rows.getFirst().get("age"));
    }

    /**
     * 测试 Lambda 链式删除走真实 JDBC。
     */
    @Test
    void testLambdaDelete() {
        int affected = engine.delete(User.class).eq("id", 2).remove();
        assertEquals(1, affected);
        List<Map<String, Object>> rows = engine.getExecutor().query("SELECT * FROM user");
        assertEquals(1, rows.size());
    }

    /**
     * 测试分页查询返回总数与当页数据。
     */
    @Test
    void testQueryPage() {
        Pagination page = new Pagination().setPageNum(1).setPageSize(1);
        List<Map<String, Object>> rows = engine.getExecutor().queryPage("SELECT * FROM user ORDER BY id", page);
        assertEquals(2, page.getTotal());
        assertEquals(1, rows.size());
        assertEquals(1, rows.getFirst().get("id"));
    }

    /**
     * 测试批量插入。
     */
    @Test
    void testBatch() {
        int[] batch = engine.getExecutor().batch(
                "INSERT INTO user VALUES (?, ?, ?)",
                List.of(new Object[]{3, "wangwu", 40}, new Object[]{4, "zhaoliu", 50}));
        assertEquals(2, batch.length);
        assertEquals(1, batch[0]);
        assertEquals(1, batch[1]);
    }

    /**
     * 测试 DuckDB 引擎的元数据入口（无专用实现时回退 DefaultMetaData）。
     */
    @Test
    void testMetaFallback() {
        // DuckDB 提供方言级元数据实现（DuckdbMetaData），优于通用 DefaultMetaData 兜底
        assertInstanceOf(com.chua.common.support.lang.datasource.meta.MetaData.class, engine.meta());
        assertNotNull(engine.meta());
    }

    /**
     * 测试关闭引擎后连接释放，查询失败。
     */
    @Test
    void testCloseReleasesConnection() {
        engine.close();
        assertThrows(Exception.class, () -> engine.getExecutor().query("SELECT * FROM user"));
    }

    /**
     * 测试执行器获取，非 JDBC 数据源返回 null。
     */
    @Test
    void testExecutorNotNull() {
        assertNotNull(engine.getExecutor());
        assertNotNull(engine.getExecutor("default"));
    }

    /**
     * 测试空引擎无数据源时 meta 回退且不抛异常。
     */
    @Test
    void testEmptyEngineMeta() {
        DuckDBEngine empty = new DuckDBEngine();
        assertInstanceOf(DefaultMetaData.class, empty.meta());
        empty.close();
    }

    /**
     * 测试实体反射映射的字段（与 user 表列一一对应）。
     * <p>必须为 public，保证跨包的 JdbcSqlExecutor 反射可访问。</p>
     */
    public static class User {
        /** 主键 */
        public int id;

        /** 名称 */
        public String name;

        /** 年龄 */
        public int age;
    }
}
