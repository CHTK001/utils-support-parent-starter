package com.chua.duckdb.support.engine;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * DuckDB 完整增删改查值校验测试。
 */
class DuckDbCrudIT {

    public static class Rec {
        private int id;
        private String name;
        private int age;
        public int getId() { return id; }
        public void setId(int v) { id = v; }
        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public int getAge() { return age; }
        public void setAge(int v) { age = v; }
    }

    @Test
    void crud_valueAssertions() {
        DuckDBEngine engine = new DuckDBEngine();
        engine.addDataSource("d", "jdbc:duckdb:");
        try {
            var ex = engine.getExecutor();

            /* ===== CREATE ===== */
            ex.execute("CREATE TABLE rec (id INT PRIMARY KEY, name VARCHAR(20), age INT)");
            ex.execute("INSERT INTO rec VALUES (1,'Alice',20)");
            ex.execute("INSERT INTO rec VALUES (2,'Bob',30)");

            /* ===== READ（raw SQL + Lambda 两条路径）===== */
            var rows = ex.query("SELECT id,name,age FROM rec ORDER BY id");
            assertEquals(2, rows.size());
            assertEquals("Alice", rows.get(0).get("name"));
            assertEquals(30, ((Number)rows.get(1).get("age")).intValue());

            Rec one = engine.query(Rec.class).eq(Rec::getId, 1).one();
            assertNotNull(one);
            assertEquals("Alice", one.getName());
            assertEquals(20, one.getAge());

            /* ===== UPDATE → 读回校验新值 ===== */
            int u = engine.update(Rec.class)
                    .set(Rec::getName, "Alicia")
                    .eq(Rec::getId, 1)
                    .update();
            assertEquals(1, u);
            Rec after = engine.query(Rec.class).eq(Rec::getId, 1).one();
            assertNotNull(after);
            assertEquals("Alicia", after.getName(), "update 后应读到新值");
            assertEquals("Bob", engine.query(Rec.class).eq(Rec::getId, 2).one().getName(),
                    "未修改的行不受影响");

            /* ===== DELETE → 确认不存在 ===== */
            int d = engine.delete(Rec.class).eq(Rec::getId, 2).remove();
            assertEquals(1, d);
            List<Rec> rest = engine.query(Rec.class).list();
            assertEquals(1, rest.size());
            assertEquals(1, rest.get(0).getId());
            assertTrue(rest.stream().noneMatch(r -> r.getId() == 2), "删除后不应包含 id=2");
        } finally {
            engine.close();
        }
    }
}
