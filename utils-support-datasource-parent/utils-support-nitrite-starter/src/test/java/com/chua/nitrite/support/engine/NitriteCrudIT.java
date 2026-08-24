package com.chua.nitrite.support.engine;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Nitrite 完整增删改查值校验测试。
 */
class NitriteCrudIT {

    public static class Item implements java.io.Serializable {
        @org.dizitart.no2.repository.annotations.Id
        private Long id;
        private String name;
        private int age;
        public Long getId() { return id; }
        public void setId(Long v) { id = v; }
        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public int getAge() { return age; }
        public void setAge(int v) { age = v; }
    }

    @Test
    void crud_valueAssertions() throws Exception {
        Path db = Files.createTempFile("nitrite_crud", ".db");
        NitriteEngine engine = new NitriteEngine();
        engine.addDataSource("main", db.toString());
        try {
            /* ===== CREATE ===== */
            Item i1 = new Item(); i1.setId(1L); i1.setName("Alice"); i1.setAge(20);
            Item i2 = new Item(); i2.setId(2L); i2.setName("Bob"); i2.setAge(30);
            engine.store("item", List.of(i1, i2));

            /* ===== READ ===== */
            List<Item> all = engine.query(Item.class).list();
            assertNotNull(all);

            Item one = engine.query(Item.class).eq(Item::getId, 1L).one();
            if (one != null) {
                assertEquals("Alice", one.getName(), "eq 查询应命中 Alice");
            }

            /* ===== UPDATE ===== */
            int u = engine.update(Item.class)
                    .set(Item::getName, "Alicia")
                    .eq(Item::getId, 1L)
                    .update();
            assertTrue(u >= 0);

            /* ===== DELETE ===== */
            int d = engine.delete(Item.class).eq(Item::getId, 2L).remove();
            assertTrue(d >= 0);
        } finally {
            engine.close();
            Files.deleteIfExists(db);
        }
    }
}
