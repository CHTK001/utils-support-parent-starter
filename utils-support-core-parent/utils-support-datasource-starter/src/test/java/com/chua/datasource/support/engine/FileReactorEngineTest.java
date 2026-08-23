package com.chua.datasource.support.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文件响应式引擎测试，验证 AsynchronousFileChannel 真响应式加载。
 */
class FileReactorEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void testLoadJsonReactive() throws Exception {
        FileReactorEngine engine = new FileReactorEngine();
        Path json = tempDir.resolve("test.json");
        Files.writeString(json, "[{\"id\":1,\"name\":\"zhangsan\"},{\"id\":2,\"name\":\"lisi\"}]");

        engine.load("user", json.toString()).block();

        var result = engine.query(Object.class).list().collectList().block();
        assertNotNull(result);
        assertEquals(2, result.size());
        engine.close();
    }

    @Test
    void testLoadCsvReactive() throws Exception {
        FileReactorEngine engine = new FileReactorEngine();
        Path csv = tempDir.resolve("test.csv");
        Files.writeString(csv, "id,name,age\n1,zhangsan,20\n2,lisi,30\n");

        engine.load("user", csv.toString()).block();

        var result = engine.query(Object.class).list().collectList().block();
        assertNotNull(result);
        assertEquals(2, result.size());
        engine.close();
    }

    @Test
    void testLoadCsvWithQuotes() throws Exception {
        FileReactorEngine engine = new FileReactorEngine();
        Path csv = tempDir.resolve("test.csv");
        Files.writeString(csv, "name,desc\nzhangsan,\"hello,world\"\nlisi,test\n");

        engine.load("user", csv.toString()).block();

        var result = engine.query(Object.class).list().collectList().block();
        assertNotNull(result);
        assertEquals(2, result.size());
        engine.close();
    }

    @Test
    void testLoadJsonFileNotFound() {
        FileReactorEngine engine = new FileReactorEngine();
        Path missing = tempDir.resolve("nonexistent.json");

        assertThrows(Exception.class, () ->
                engine.load("user", missing.toString()).block());
        engine.close();
    }

    @Test
    void testLoadAndQueryReactive() throws Exception {
        FileReactorEngine engine = new FileReactorEngine();
        Path json = tempDir.resolve("data.json");
        Files.writeString(json, "[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"}]");

        engine.load("data", json.toString()).block();

        var flux = engine.query(Object.class).list();
        assertNotNull(flux);
        assertEquals(2, flux.collectList().block().size());
        engine.close();
    }
}