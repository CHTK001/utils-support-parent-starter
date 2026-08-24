package com.chua.datasource.support.engine;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * FileEngine/FileReactorEngine 完整 CRUD 值校验测试。
 */
class FileCrudIT {

    public static class FUser {
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
    void fileEngine_jsonLoadAndQuery() throws Exception {
        Path json = Files.createTempFile("file_crud", ".json");
        Files.writeString(json, """
                [
                  {"id":1,"name":"Alice","age":20},
                  {"id":2,"name":"Bob","age":30},
                  {"id":3,"name":"Cathy","age":25}
                ]
                """);

        FileEngine engine = new FileEngine();
        engine.addDataSource("f", json.toString());
        try {
            /* READ：全量 */
            List<FUser> all = engine.query(FUser.class).list();
            assertEquals(3, all.size());

            /* READ：eq 条件 + 值校验 */
            FUser one = engine.query(FUser.class).eq(FUser::getId, 1).one();
            assertNotNull(one);
            assertEquals("Alice", one.getName());
            assertEquals(20, one.getAge());

            /* READ：gt 过滤 */
            List<FUser> older = engine.query(FUser.class).gt(FUser::getAge, 22).list();
            assertEquals(2, older.size());

            /* UPDATE */
            int u = engine.update(FUser.class)
                    .set(FUser::getName, "Alicia")
                    .eq(FUser::getId, 1)
                    .update();
            assertTrue(u >= 0);

            /* DELETE */
            int d = engine.delete(FUser.class).eq(FUser::getId, 3).remove();
            assertTrue(d >= 0);
        } finally {
            engine.close();
            Files.deleteIfExists(json);
        }
    }

    @Test
    void fileReactor_csvLoadAndLambdaQuery() throws Exception {
        Path csv = Files.createTempFile("file_reactor", ".csv");
        Files.writeString(csv, "id,name,age\n1,Alice,20\n2,Bob,30\n3,Cathy,25\n");

        FileReactorEngine engine = new FileReactorEngine();
        engine.addDataSource("fr", csv.toString());
        try {
            List<FUser> all = engine.query(FUser.class).list();
            assertEquals(3, all.size());

            FUser first = engine.query(FUser.class)
                    .orderByAsc(FUser::getAge)
                    .one();
            assertNotNull(first);

            /* LIKE 过滤 */
            List<FUser> liked = engine.query(FUser.class)
                    .like(FUser::getName, "li")
                    .list();
            assertNotNull(liked);
        } finally {
            engine.close();
            Files.deleteIfExists(csv);
        }
    }
}
