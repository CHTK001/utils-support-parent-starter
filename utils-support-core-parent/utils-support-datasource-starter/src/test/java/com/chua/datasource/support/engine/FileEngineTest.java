package com.chua.datasource.support.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FileEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void testLoadJson() throws Exception {
        Path json = tempDir.resolve("test.json");
        Files.writeString(json, "[{\"id\":1,\"name\":\"zhangsan\"},{\"id\":2,\"name\":\"lisi\"}]");

        FileEngine engine = new FileEngine();
        engine.load("user", json.toString());
        assertNotNull(engine.query(UserEntity.class).list());
    }

    @Test
    void testLoadAndClose() throws Exception {
        Path json = tempDir.resolve("test.json");
        Files.writeString(json, "[{\"id\":1}]");

        FileEngine engine = new FileEngine();
        engine.load("user", json.toString());
        assertNotNull(engine.query(UserEntity.class).list());

        engine.close();
        List<?> after = engine.query(UserEntity.class).list();
        assertTrue(after.isEmpty());
    }

    public static class UserEntity {
        public int id;
        public String name;
    }
}