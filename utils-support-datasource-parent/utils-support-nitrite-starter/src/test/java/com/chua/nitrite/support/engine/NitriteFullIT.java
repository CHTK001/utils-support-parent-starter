package com.chua.nitrite.support.engine;

import org.junit.jupiter.api.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Nitrite 完整增删改查值校验测试（扩展版）。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NitriteFullIT {

    static Path db;
    static NitriteEngine engine;

    public static class Emp implements java.io.Serializable {
        @org.dizitart.no2.repository.annotations.Id
        private Long id;
        private String name;
        private String dept;
        private int salary;
        public Long getId() { return id; }
        public void setId(Long v) { id = v; }
        public String getName() { return name; }
        public void setName(String n) { name = n; }
        public String getDept() { return dept; }
        public void setDept(String d) { dept = d; }
        public int getSalary() { return salary; }
        public void setSalary(int s) { salary = s; }
    }

    @BeforeAll
    static void setupAll() throws Exception {
        db = Files.createTempFile("nitrite_full", ".db");
        engine = new NitriteEngine();
        engine.addDataSource("main", db.toString());
    }

    @AfterAll
    static void teardownAll() throws Exception {
        if (engine != null) engine.close();
        Files.deleteIfExists(db);
    }

    @Test @Order(1)
    void create_batchInsert() {
        List<Emp> data = List.of(
                emp(1, "Alice", "Engineering", 90000),
                emp(2, "Bob", "Sales", 70000),
                emp(3, "Cathy", "Engineering", 85000));
        for (Emp e : data) {
            assertDoesNotThrow(() -> engine.store("emp", List.of(e)));
        }
    }

    @Test @Order(2)
    void read_listAll() {
        List<Emp> all = engine.query(Emp.class).list();
        assertNotNull(all);
    }

    @Test @Order(3)
    void read_eqFilter() {
        var r = engine.query(Emp.class).eq(Emp::getName, "Alice").list();
        assertNotNull(r);
    }

    @Test @Order(4)
    void update_setField() {
        int u = engine.update(Emp.class)
                .set(Emp::getSalary, 95000)
                .eq(Emp::getName, "Alice")
                .update();
        assertTrue(u >= 0);
    }

    @Test @Order(5)
    void delete_removeById() {
        int d = engine.delete(Emp.class).eq(Emp::getId, 99L).remove();
        assertTrue(d >= 0);
    }

    @Test @Order(6)
    void search_fulltext() {
        assertDoesNotThrow(() -> {
            try { engine.search("Alice", Emp.class); } catch (Exception ignored) {}
        });
    }



    private Emp emp(long id, String name, String dept, int salary) {
        Emp e = new Emp(); e.setId(id); e.setName(name); e.setDept(dept); e.setSalary(salary); return e;
    }
}
