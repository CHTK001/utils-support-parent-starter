package com.chua.sqlserver.support.engine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SqlServerEngine 完整增删改查值校验测试。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SqlServerCrudIT {

    private static SqlServerEngine engine;
    private static final String TABLE = "ss_crud_user";

    public static class SUser {
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
        engine = new SqlServerEngine();
        engine.addDataSource("mssql", "172.16.0.40", 1434, "master", "sa", "YourStrong!Passw0rd");
        try {
            engine.getExecutor().execute("IF OBJECT_ID('" + TABLE + "','U') IS NOT NULL DROP TABLE " + TABLE);
            engine.getExecutor().execute("CREATE TABLE " + TABLE +
                    " (id INT PRIMARY KEY, name NVARCHAR(20), age INT)");
            engine.getExecutor().execute("INSERT INTO " + TABLE + " VALUES (1,'Alice',20),(2,'Bob',30)");
        } catch (Exception e) {
            throw new RuntimeException("setup failed", e);
        }
    }

    @AfterAll
    static void teardown() {
        try {
            engine.getExecutor().execute("IF OBJECT_ID('" + TABLE + "','U') IS NOT NULL DROP TABLE " + TABLE);
        } catch (Exception ignored) {}
        engine.close();
    }

    @Test
    @Order(1)
    void create_andRead() {
        List<SUser> all = engine.query(SUser.class).list();
        assertEquals(2, all.size());

        SUser one = engine.query(SUser.class).eq(SUser::getId, 1).one();
        assertNotNull(one);
        assertEquals("Alice", one.getName());
        assertEquals(20, one.getAge());
    }

    @Test
    @Order(2)
    void update_readBackNewValue() {
        int u = engine.update(SUser.class)
                .set(SUser::getName, "Alicia")
                .eq(SUser::getId, 1)
                .update();
        assertEquals(1, u);

        SUser after = engine.query(SUser.class).eq(SUser::getId, 1).one();
        assertNotNull(after);
        assertEquals("Alicia", after.getName(), "update 后应读到新值");
    }

    @Test
    @Order(3)
    void delete_verifyGone() {
        int d = engine.delete(SUser.class).eq(SUser::getId, 2).remove();
        assertEquals(1, d);

        List<SUser> rest = engine.query(SUser.class).list();
        assertEquals(1, rest.size());
        assertEquals(1, rest.get(0).getId());
    }
}
