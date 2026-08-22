package com.chua.common.support.ai.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LocalFileUploadProvider 测试
 */
class LocalFileUploadProviderTest {

    private Path tempDir;
    private LocalFileUploadProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("upload-provider-test-");
        provider = new LocalFileUploadProvider(tempDir.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (provider != null) provider.delete("test-doc");
        if (tempDir != null) deleteRecursively(tempDir.toFile());
    }

    @Test
    void testUploadAndRead() {
        byte[] data = "Hello RAG document content".getBytes(StandardCharsets.UTF_8);
        String fileId = provider.upload("doc-001", "test.txt", data);

        assertEquals("doc-001", fileId);

        byte[] readBack = provider.read("doc-001");
        assertNotNull(readBack);
        assertArrayEquals(data, readBack);
    }

    @Test
    void testReadNonExistent() {
        assertNull(provider.read("non-existent"));
    }

    @Test
    void testDelete() {
        byte[] data = "test content".getBytes(StandardCharsets.UTF_8);
        provider.upload("doc-delete", "test.txt", data);
        assertTrue(provider.delete("doc-delete"));
        assertNull(provider.read("doc-delete"));
    }

    @Test
    void testDeleteNonExistent() {
        assertFalse(provider.delete("non-existent"));
    }

    @Test
    void testMultipleUploads() {
        provider.upload("doc-a", "a.txt", "content-a".getBytes());
        provider.upload("doc-b", "b.txt", "content-b".getBytes());
        provider.upload("doc-c", "c.txt", "content-c".getBytes());

        assertArrayEquals("content-a".getBytes(), provider.read("doc-a"));
        assertArrayEquals("content-b".getBytes(), provider.read("doc-b"));
        assertArrayEquals("content-c".getBytes(), provider.read("doc-c"));

        provider.delete("doc-b");
        assertNull(provider.read("doc-b"));
        assertNotNull(provider.read("doc-a"));
    }

    private void deleteRecursively(java.io.File file) {
        if (file.isDirectory()) {
            for (java.io.File child : file.listFiles()) deleteRecursively(child);
        }
        file.delete();
    }
}
