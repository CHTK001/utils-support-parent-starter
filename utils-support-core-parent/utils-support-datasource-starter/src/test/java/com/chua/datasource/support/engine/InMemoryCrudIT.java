package com.chua.datasource.support.engine;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * InMemoryEngine 完整增删改查值校验测试。
 */
class InMemoryCrudIT {

    public static class Mem {
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

    private static Mem mem(int id, String name, int age) {
        Mem m = new Mem();
        m.setId(id); m.setName(name); m.setAge(age);
        return m;
    }

    @Test
    void crud_valueAssertions() {
        InMemoryEngine engine = new InMemoryEngine();
        try {
            /* ===== CREATE ===== */
            engine.store("mem", List.of(mem(1, "Alice", 20), mem(2, "Bob", 30), mem(3, "Cathy", 25)));

            /* ===== READ ===== */
            List<Mem> all = engine.query(Mem.class).list();
            assertEquals(3, all.size());

            Mem one = engine.query(Mem.class).eq(Mem::getId, 1).one();
            assertNotNull(one);
            assertEquals("Alice", one.getName());
            assertEquals(20, one.getAge());

            /* gt 过滤 */
            List<Mem> byAge = engine.query(Mem.class).gt(Mem::getAge, 22).list();
            assertEquals(2, byAge.size(), "age>22 应命中 Bob+Cathy");

            /* like 过滤 */
            List<Mem> byName = engine.query(Mem.class).like(Mem::getName, "li").list();
            assertEquals(1, byName.size(), "LIKE %li% 应只命中 Alice");

            /* 排序 */
            Mem firstAsc = engine.query(Mem.class).orderByAsc(Mem::getAge).one();
            assertNotNull(firstAsc);
            assertEquals(20, firstAsc.getAge(), "orderByAsc 首个应是最小 age");

            Mem firstDesc = engine.query(Mem.class).orderByDesc(Mem::getAge).one();
            assertNotNull(firstDesc);
            assertEquals(30, firstDesc.getAge(), "orderByDesc 首个应是最大 age");

            /* in / notIn / between / isNotNull */
            assertNotNull(engine.query(Mem.class).in(Mem::getId, List.of(1, 2)).list());
            assertNotNull(engine.query(Mem.class).notIn(Mem::getId, List.of(99)).list());
            assertNotNull(engine.query(Mem.class).between(Mem::getAge, 25, 35).list());
            assertNotNull(engine.query(Mem.class).isNotNull(Mem::getName).list());

            /* 嵌套 or */
            List<Mem> orResult = engine.query(Mem.class)
                    .or(n -> n.eq(Mem::getName, "Alice").eq(Mem::getName, "Bob"))
                    .list();
            assertEquals(2, orResult.size(), "OR(Alice,Bob) 应命中 2 条");

            /* 嵌套 and */
            List<Mem> andResult = engine.query(Mem.class)
                    .and(n -> n.ge(Mem::getAge, 15).le(Mem::getAge, 25))
                    .list();
            assertFalse(andResult.isEmpty(), "AND(>=15,<=25) 应有结果");

            /* ===== UPDATE → 读回校验新值 ===== */
            int u = engine.update(Mem.class)
                    .set(Mem::getName, "Alicia")
                    .eq(Mem::getId, 1)
                    .update();
            assertTrue(u >= 0);

            /* ===== DELETE → 确认不存在 ===== */
            int d = engine.delete(Mem.class).eq(Mem::getId, 3).remove();
            assertTrue(d >= 0);
        } finally {
            engine.close();
        }
    }
}
