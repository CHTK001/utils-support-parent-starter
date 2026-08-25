package com.chua.postgresql.support.engine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgresqlEngine 完整增删改查值校验测试。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostgresqlCrudIT {

    private static PostgresqlEngine engine;
    private static final String TABLE = "pg_crud_user";

    public static class PUser {
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
    static void setup() {
        engine = new PostgresqlEngine();
        engine.addDataSource("pg", "172.16.0.40", 5433, "testdb", "postgres", "postgres");
        engine.getExecutor().execute("DROP TABLE IF EXISTS " + TABLE);
        engine.getExecutor().execute("CREATE TABLE " + TABLE +
                " (id INT PRIMARY KEY, name VARCHAR(20), age INT)");
        engine.getExecutor().execute("INSERT INTO " + TABLE + " VALUES (1,'Alice',20),(2,'Bob',30)");
    }

    @AfterAll
    static void teardown() {
        try { engine.getExecutor().execute("DROP TABLE IF EXISTS " + TABLE); } catch (Exception ignored) {}
        engine.close();
    }

    @Test
    @Order(1)
    void create_andRead() {
        List<PUser> all = engine.query(PUser.class).list();
        assertEquals(2, all.size());

        PUser one = engine.query(PUser.class).eq(PUser::getId, 1).one();
        assertNotNull(one);
        assertEquals("Alice", one.getName());
        assertEquals(20, one.getAge());
    }

    @Test
    @Order(2)
    void update_readBackNewValue() {
        int u = engine.update(PUser.class)
                .set(PUser::getName, "Alicia")
                .eq(PUser::getId, 1)
                .update();
        assertEquals(1, u);

        PUser after = engine.query(PUser.class).eq(PUser::getId, 1).one();
        assertNotNull(after);
        assertEquals("Alicia", after.getName(), "update 后应读到新值");
    }

    @Test
    @Order(3)
    void delete_verifyGone() {
        int d = engine.delete(PUser.class).eq(PUser::getId, 2).remove();
        assertEquals(1, d);

        List<PUser> rest = engine.query(PUser.class).list();
        assertEquals(1, rest.size());
        assertEquals(1, rest.get(0).getId());
    }
}
