package com.chua.sqlite.support.engine;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SqliteEngine 嵌入式真实测试（临时文件库）。
 */
class SqliteEngineIT {

    @Test
    void crud_rawSql_andLambda() throws Exception {
        Path db = Files.createTempFile("sqlite_it", ".db");
        SqliteEngine engine = new SqliteEngine();
        engine.addDataSource("main", db.toString());
        try {
            engine.getExecutor().execute("CREATE TABLE sq (id INTEGER PRIMARY KEY, name TEXT, age INTEGER)");
            engine.getExecutor().execute("INSERT INTO sq VALUES (1,'Alice',20),(2,'Bob',30),(3,'Cathy',25)");

            List<Integer> ids = engine.query(Sq.class).list()
                    .stream().map(Sq::getId).sorted().toList();
            assertEquals(List.of(1, 2, 3), ids);

            int updated = engine.update(Sq.class).set(Sq::getName, "Alicia").eq(Sq::getId, 1).update();
            assertEquals(1, updated);
            Sq one = engine.query(Sq.class).eq(Sq::getId, 1).one();
            assertNotNull(one);
            assertEquals("Alicia", one.getName());

            int deleted = engine.delete(Sq.class).ge(Sq::getAge, 30).remove();
            assertTrue(deleted >= 1);

            /* LIKE 操作符 */
            assertEquals(1, engine.query(Sq.class)
                    .like(Sq::getName, "ath").list().size());
        } finally {
            engine.close();
            Files.deleteIfExists(db);
        }
    }

    /** Lambda 实体（表名 = sq） */
    public static class Sq {
        private Integer id;
        private String name;
        private Integer age;

        public Integer getId() { return id; }
        public void setId(Integer id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Integer getAge() { return age; }
        public void setAge(Integer age) { this.age = age; }
    }
}
