package com.chua.duckdb.support.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DuckDBEngine 嵌入式真实测试（内存模式 jdbc:duckdb:）。
 */
class DuckDBEngineIT {

    @Test
    void crud_rawSql_andLambda() {
        DuckDBEngine engine = new DuckDBEngine();
        engine.addDataSource("default", "jdbc:duckdb:");
        try {
            engine.getExecutor().execute("CREATE TABLE du (id INT PRIMARY KEY, name VARCHAR(20), age INT)");
            engine.getExecutor().execute("INSERT INTO du VALUES (1,'Alice',20),(2,'Bob',30),(3,'Cathy',25)");

            List<Map<String, Object>> rows = engine.getExecutor()
                    .query("SELECT id, name, age FROM du ORDER BY id");
            assertEquals(3, rows.size());
            assertEquals("Alice", String.valueOf(rows.get(0).get("name")));

            /* Lambda 更新与条件查询（同步执行链） */
            int updated = engine.update(Du.class).set(Du::getName, "Alicia").eq(Du::getId, 1).update();
            assertEquals(1, updated);
            Du one = engine.query(Du.class).eq(Du::getId, 1).one();
            assertNotNull(one);
            assertEquals("Alicia", one.getName());

            int deleted = engine.delete(Du.class).ge(Du::getAge, 30).remove();
            assertTrue(deleted >= 1);
        } finally {
            engine.close();
        }
    }

    @Test
    void flyway_sqlFileImport() {
        DuckDBEngine engine = new DuckDBEngine();
        engine.addDataSource("default", "jdbc:duckdb:");
        try {
            var flyway = engine.flyway();
            /* 内存库直接用 execute 单脚本验证 SQL 文件导入能力 */
            java.nio.file.Path script = java.nio.file.Files.createTempFile("duck_fly", ".sql");
            java.nio.file.Files.writeString(script,
                    "CREATE TABLE fw (k VARCHAR(10));\nINSERT INTO fw VALUES ('ok');");
            assertTrue(flyway.execute(script) >= 1);

            var row = engine.getExecutor().query("SELECT k FROM fw");
            assertFalse(row.isEmpty());
            assertEquals("ok", row.get(0).values().iterator().next());
            java.nio.file.Files.deleteIfExists(script);
        } catch (Exception e) {
            fail(e);
        } finally {
            engine.close();
        }
    }

    /** Lambda 实体（表名 = du） */
    public static class Du {
        private Integer id;
        private String name;
        private Integer age;

        public Integer getId() { return id; }
        public void setId(Integer id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Integer getAge() { return age; }
        public void setAge(Integer age) { this.age = age; }
    }
}
