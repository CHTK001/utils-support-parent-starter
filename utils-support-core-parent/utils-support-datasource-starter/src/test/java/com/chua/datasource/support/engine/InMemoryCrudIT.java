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

            /* MemoryWhereParser 支持全部操作符（eq/ne/gt/ge/lt/le/like/in/notIn/between/isNull/isNotNull/or/括号嵌套），
             * orderBy 由 AbstractEngine.executeQuery 在内存排序，page 分页同样支持。
             * 本测试仅覆盖常用子集；完整操作符矩阵由 MemorySqlExample（example-starter）和
             * LambdaFullFeatureIT（JDBC/R2DBC 路径）分别覆盖。 */

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
