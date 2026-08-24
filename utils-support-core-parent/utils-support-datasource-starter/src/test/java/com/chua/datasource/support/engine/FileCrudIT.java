package com.chua.datasource.support.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/**
 * FileReactorEngine CRUD 值校验测试。
 */
class FileCrudIT {

    @TempDir
    Path tempDir;

    @Test
    void jsonLoad_queryUpdateDelete() throws Exception {
        Path json = tempDir.resolve("crud.json");
        Files.writeString(json, """
                [
                  {"id":1,"name":"Alice","age":20},
                  {"id":2,"name":"Bob","age":30},
                  {"id":3,"name":"Cathy","age":25}
                ]
                """);

        FileReactorEngine engine = new FileReactorEngine();
        engine.load("fuser", json.toString()).block();
        try {
            /* READ：全量 + 数量校验 */
            var all = engine.query(Object.class).list().collectList().block();
            assertNotNull(all);
            assertEquals(3, all.size(), "JSON 加载后应有 3 条");

            /* UPDATE */
            int u = engine.update(FUser.class)
                    .set(FUser::getName, "Alicia")
                    .eq(FUser::getId, 1)
                    .update().block();
            assertTrue(u >= 0, "update 应执行成功");

            /* DELETE */
            int d = engine.delete(FUser.class)
                    .eq(FUser::getId, 3)
                    .remove().block();
            assertTrue(d >= 0, "delete 应执行成功");
        } finally {
            engine.close();
        }
    }

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
}
