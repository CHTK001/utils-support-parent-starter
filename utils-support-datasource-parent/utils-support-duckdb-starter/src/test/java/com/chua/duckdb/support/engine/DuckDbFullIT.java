package com.chua.duckdb.support.engine;

import org.junit.jupiter.api.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * DuckDB 完整增删改查值校验（共享表，5 个独立用例）。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DuckDbFullIT {

    static DuckDBEngine engine;

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

    @BeforeAll
    static void setupAll() {
        engine = new DuckDBEngine();
        engine.addDataSource("d", "jdbc:duckdb:");
        var ex = engine.getExecutor();
        ex.execute("CREATE TABLE rec (id INT PRIMARY KEY, name VARCHAR(20), age INT)");
        ex.execute("INSERT INTO rec VALUES (1,'Alice',20),(2,'Bob',30),(3,'Cathy',25)");
    }

    @AfterAll
    static void teardownAll() {
        if (engine != null) engine.close();
    }

    @Test @Order(1)
    void read_all_andValues() {
        List<Rec> all = engine.query(Rec.class).list();
        assertEquals(3, all.size());
        Rec first = engine.query(Rec.class).eq(Rec::getId, 1).one();
        assertNotNull(first);
        assertEquals("Alice", first.getName());
        assertEquals(20, first.getAge());
    }

    @Test @Order(2)
    void read_operators() {
        assertEquals(2, engine.query(Rec.class).ne(Rec::getName, "Alice").list().size(), "ne");
        assertEquals(2, engine.query(Rec.class).gt(Rec::getAge, 22).list().size(), "gt");
        assertEquals(1, engine.query(Rec.class).lt(Rec::getAge, 22).list().size(), "lt");
        assertEquals(1, engine.query(Rec.class).like(Rec::getName, "lic").list().size(), "like");
        assertEquals(2, engine.query(Rec.class).in(Rec::getId, List.of(1, 3)).list().size(), "in");
        assertEquals(2, engine.query(Rec.class).between(Rec::getAge, 20, 25).list().size(), "between");
    }

    @Test @Order(3)
    void update_readBackNewValue() {
        int u = engine.update(Rec.class).set(Rec::getName, "Alicia").eq(Rec::getId, 1).update();
        assertEquals(1, u);
        Rec after = engine.query(Rec.class).eq(Rec::getId, 1).one();
        assertNotNull(after);
        assertEquals("Alicia", after.getName(), "update 后应读到新值");
        /* 还原 */
        engine.update(Rec.class).set(Rec::getName, "Alice").eq(Rec::getId, 1).update();
    }

    @Test @Order(4)
    void delete_verifyGone() {
        /* 先插入一条专删的 */
        engine.getExecutor().execute("INSERT INTO rec VALUES (99,'Temp',0)");
        int d = engine.delete(Rec.class).eq(Rec::getId, 99).remove();
        assertTrue(d >= 0);
        List<Rec> rest = engine.query(Rec.class).list();
        assertEquals(3, rest.size(), "删除后应剩 3 条");
    }

    @Test @Order(5)
    void flyway_sqlImport() throws Exception {
        var script = java.nio.file.Files.createTempFile("fw", ".sql");
        java.nio.file.Files.writeString(script,
                "CREATE TABLE fw_t (k VARCHAR(10));\nINSERT INTO fw_t VALUES ('ok');");
        var fw = engine.flyway();
        assertTrue(fw.execute(script) >= 1);
        var row = engine.getExecutor().query("SELECT k FROM fw_t");
        assertFalse(row.isEmpty());
        java.nio.file.Files.deleteIfExists(script);
    }
}
