package com.chua.datasource.support.engine;

import com.chua.common.support.file.reactive.DefaultReactorFileSystem;
import com.chua.common.support.file.reactive.ReactorFileSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DefaultReactorFileSystem 完整增删改查值校验测试。
 */
class ReactorFileSystemTest {

    ReactorFileSystem fs;
    Path tempFile;
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        fs = new DefaultReactorFileSystem(1024);
        tempDir = Files.createTempDirectory("rfs_test");
        tempFile = tempDir.resolve("test.txt");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fs != null) fs.delete(tempFile).block();
        if (Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        }
    }

    @Test
    void writeString_readString_valueRoundTrip() {
        String content = "hello reactive file system\nline2\nline3";

        fs.writeString(tempFile, content).block();

        var result = fs.readString(tempFile).block();
        assertNotNull(result);
        assertEquals(content, result, "读写往返内容应一致");
    }

    @Test
    void writeBytes_readBytes_roundTrip() {
        byte[] data = {1, 2, 3, 4, 5};

        fs.writeBytes(tempFile, data).block();
        var result = fs.readBytes(tempFile).block();

        assertNotNull(result);
        assertArrayEquals(data, result);
    }

    @Test
    void readLines_returnsCorrectCountAndValues() throws Exception {
        Files.writeString(tempFile, "line1\nline2\nline3");

        List<String> lines = fs.readLines(tempFile).collectList().block();
        assertNotNull(lines);
        assertEquals(3, lines.size());
        assertEquals("line1", lines.get(0));
        assertEquals("line3", lines.get(2));
    }

    @Test
    void appendBytes_appendsToEnd() throws Exception {
        Files.writeString(tempFile, "part1-");

        fs.appendBytes(tempFile, "part2".getBytes()).block();

        var content = fs.readString(tempFile).block();
        assertNotNull(content);
        assertTrue(content.contains("part1-part2"), "追加后应包含两段内容");
    }

    @Test
    void delete_removesFile() throws Exception {
        Files.writeString(tempFile, "to be deleted");

        Boolean deleted = fs.delete(tempFile).block();
        assertTrue(Boolean.TRUE.equals(deleted));
        assertFalse(fs.exists(tempFile).block());
    }

    @Test
    void size_returnsCorrectValue() throws Exception {
        Files.writeString(tempFile, "12345"); // 5 bytes

        Long size = fs.size(tempFile).block();
        assertNotNull(size);
        assertEquals(5L, size.longValue());
    }

    @Test
    void exists_trueAndFalse() throws Exception {
        Files.writeString(tempFile, "data");
        assertTrue(fs.exists(tempFile).block());

        fs.delete(tempFile).block();
        assertFalse(fs.exists(tempFile).block());
    }

    @Test
    void readNonExistent_throwsNoSuchFile() {
        var missing = tempDir.resolve("no_such_file.txt");
        assertThrows(Exception.class, () -> fs.readString(missing).block());
    }
}
