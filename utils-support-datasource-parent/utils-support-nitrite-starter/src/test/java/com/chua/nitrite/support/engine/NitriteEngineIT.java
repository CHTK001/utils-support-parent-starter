package com.chua.nitrite.support.engine;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * NitriteEngine 嵌入式文档数据库真实测试。
 */
class NitriteEngineIT {

    public static class Item implements java.io.Serializable {
        @org.dizitart.no2.repository.annotations.Id
        private Long id;
        private String name;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @Test
    void crud_andLambdaQuery() throws Exception {
        Path db = Files.createTempFile("nitrite_it", ".db");
        NitriteEngine engine = new NitriteEngine();
        engine.addDataSource("main", db.toString());
        try {
            int updated = engine.update(Item.class)
                    .set(Item::getName, "Alicia")
                    .eq(Item::getId, 1L)
                    .update();
            assertTrue(updated >= 0);

            List<Item> items = engine.query(Item.class).list();
            assertNotNull(items);

            int deleted = engine.delete(Item.class).eq(Item::getId, 99L).remove();
            assertTrue(deleted >= 0);
        } finally {
            engine.close();
            Files.deleteIfExists(db);
        }
    }
}
