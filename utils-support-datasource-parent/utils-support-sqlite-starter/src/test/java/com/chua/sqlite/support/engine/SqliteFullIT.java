package com.chua.sqlite.support.engine;

import org.junit.jupiter.api.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SQLite 全操作符值校验测试（扩展版）。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SqliteFullIT {

    static Path db;
    static SqliteEngine engine;

    public static class P {
        private int id;
        private String name;
        private int age;
        public int getId() { return id; }
        public void setId(int v) { id = v; }
        public String getName() { return name; }
        public void setName(String n) { name = n; }
        public int getAge() { return age; }
        public void setAge(int a) { age = a; }
    }

    @BeforeAll
    static void setupAll() throws Exception {
        db = Files.createTempFile("sqlite_full", ".db");
        engine = new SqliteEngine();
        engine.addDataSource("main", db.toString());
        var ex = engine.getExecutor();
        ex.execute("CREATE TABLE p (id INTEGER PRIMARY KEY, name TEXT, age INTEGER)");
        ex.execute("INSERT INTO p VALUES (1,'Alice',20),(2,'Bob',30),(3,'Cathy',25)");
    }

    @AfterAll
    static void teardownAll() throws Exception {
        if (engine != null) engine.close();
        Files.deleteIfExists(db);
    }

    @Test @Order(1)
    void eq_ne() {
        assertNotNull(engine.query(P.class).eq(P::getName, "Alice").one());
        assertEquals(2, engine.query(P.class).ne(P::getName, "Alice").list().size());
    }

    @Test @Order(2)
    void gt_ge_lt_le() {
        assertEquals(2, engine.query(P.class).gt(P::getAge, 22).list().size());
        assertEquals(2, engine.query(P.class).ge(P::getAge, 25).list().size());
        assertEquals(1, engine.query(P.class).lt(P::getAge, 22).list().size());
        assertEquals(2, engine.query(P.class).le(P::getAge, 25).list().size());
    }

    @Test @Order(3)
    void like_variants() {
        assertEquals(1, engine.query(P.class).like(P::getName, "lic").list().size(), "like");
        assertEquals(1, engine.query(P.class).likeLeft(P::getName, "ce").list().size(), "likeLeft");
        assertEquals(1, engine.query(P.class).likeRight(P::getName, "Bo").list().size(), "likeRight");
    }

    @Test @Order(4)
    void in_notIn_between() {
        assertEquals(2, engine.query(P.class).in(P::getId, List.of(1, 3)).list().size(), "in");
        assertEquals(1, engine.query(P.class).notIn(P::getId, List.of(1, 2)).list().size(), "notIn");
        assertEquals(2, engine.query(P.class).between(P::getAge, 20, 25).list().size(), "between");
    }

    @Test @Order(5)
    void orderByAscDesc() {
        var asc = engine.query(P.class).orderByAsc(P::getAge).one();
        assertNotNull(asc);
        assertEquals(20, asc.getAge());

        var desc = engine.query(P.class).orderByDesc(P::getAge).one();
        assertNotNull(desc);
        assertEquals(30, desc.getAge());
    }

    @Test @Order(6)
    void update_delete_verify() {
        int u = engine.update(P.class).set(P::getName, "Alicia").eq(P::getId, 1).update();
        assertEquals(1, u);
        assertEquals("Alicia", engine.query(P.class).eq(P::getId, 1).one().getName());

        int d = engine.delete(P.class).eq(P::getId, 2).remove();
        assertEquals(1, d);
        assertEquals(2, engine.query(P.class).list().size());
    }

    @Test @Order(7)
    void executor_rawSql() {
        var rows = engine.getExecutor().query("SELECT COUNT(*) AS c FROM p");
        assertFalse(rows.isEmpty());
        assertTrue(((Number) rows.get(0).values().iterator().next()).intValue() >= 2);
    }

    @Test @Order(8)
    void executor_executeDdl() {
        assertDoesNotThrow(() -> {
            engine.getExecutor().execute("CREATE TABLE IF NOT EXISTS tmp_t (x INT)");
            engine.getExecutor().execute("DROP TABLE IF EXISTS tmp_t");
        });
    }
}
