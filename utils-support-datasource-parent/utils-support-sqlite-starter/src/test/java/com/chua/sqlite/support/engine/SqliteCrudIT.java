package com.chua.sqlite.support.engine;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SQLite 完整增删改查值校验测试。
 */
class SqliteCrudIT {

    public static class Sq {
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
    void crud_valueAssertions() throws Exception {
        Path db = Files.createTempFile("sqlite_crud", ".db");
        SqliteEngine engine = new SqliteEngine();
        engine.addDataSource("main", db.toString());
        try {
            var ex = engine.getExecutor();

            /* CREATE */
            ex.execute("CREATE TABLE sq (id INTEGER PRIMARY KEY, name TEXT, age INTEGER)");
            ex.execute("INSERT INTO sq VALUES (1,'Alice',20)");
            ex.execute("INSERT INTO sq VALUES (2,'Bob',30)");

            /* READ */
            List<Sq> all = engine.query(Sq.class).list();
            assertEquals(2, all.size());

            Sq one = engine.query(Sq.class).eq(Sq::getId, 1).one();
            assertNotNull(one);
            assertEquals("Alice", one.getName());
            assertEquals(20, one.getAge());

            /* UPDATE → 读回校验 */
            int u = engine.update(Sq.class)
                    .set(Sq::getName, "Alicia")
                    .eq(Sq::getId, 1)
                    .update();
            assertEquals(1, u);
            Sq after = engine.query(Sq.class).eq(Sq::getId, 1).one();
            assertNotNull(after);
            assertEquals("Alicia", after.getName(), "update 后应读到新值");

            /* DELETE → 确认不存在 */
            int d = engine.delete(Sq.class).eq(Sq::getId, 2).remove();
            assertEquals(1, d);
            List<Sq> rest = engine.query(Sq.class).list();
            assertEquals(1, rest.size());
            assertEquals(1, rest.get(0).getId());
        } finally {
            engine.close();
            Files.deleteIfExists(db);
        }
    }
}
