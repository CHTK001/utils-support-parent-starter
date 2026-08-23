package com.chua.common.support.ai.rag;

import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.common.support.ai.embedding.EmbeddingClientSetting;
import com.chua.common.support.ai.splitter.TextSplitter;
import com.chua.common.support.ai.splitter.TextSplitterProvider;
import com.chua.common.support.vector.VectorStorage;
import com.chua.common.support.vector.VectorStorageBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RAG 功能集成测试：验证 uploadDocument / updateDocument / deleteDocument 完整链路
 * @author CH
 */
class RagClientIntegrationTest {

    private Path tempDir;
    private RagClient client;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("rag-test-");
        EmbeddingClient embedding = EmbeddingClient.create(
                EmbeddingClientSetting.builder().provider("memory").build());
        TextSplitter splitter = TextSplitterProvider.create("sentence", 500, 50);
        VectorStorage storage = VectorStorageBuilder.newBuilder()
                .dimension(embedding.embedding("test").length)
                .build();
        RagClientSetting setting = RagClientSetting.builder()
                .chatClient(com.chua.common.support.ai.chat.ChatClient.create(
                        com.chua.common.support.ai.chat.ChatClientSetting.builder()
                                .provider("memory").build()))
                .embeddingClient(embedding)
                .textSplitter(splitter)
                .vectorStorage(storage)
                .uploadDir(tempDir.toString())
                .topK(3)
                .similarityThreshold(0.0)
                .build();
        client = RagClient.create("memory", setting);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            try { client.close(); } catch (Exception ignored) {}
        }
        if (tempDir != null) {
            try { deleteRecursively(tempDir.toFile()); } catch (Exception ignored) {}
        }
    }

    @Test
    void testUploadReturnsFileId() {
        byte[] data = "这是一份测试文档，关于RAG检索增强生成的介绍".getBytes(StandardCharsets.UTF_8);
        RagDocument doc = client.uploadDocument("test.txt", data);

        assertNotNull(doc.id(), "fileId不能为空");
        assertEquals("test.txt", doc.fileName());
        assertEquals("READY", doc.status());
        assertTrue(doc.chunkCount() > 0, "应该有分块");
        System.out.println("[PASS] uploadDocument返回fileId=" + doc.id()
                + ", chunks=" + doc.chunkCount());
    }

    @Test
    void testDeleteRemovesVectorAndFile() throws IOException {
        byte[] data = "删除测试文档内容".getBytes(StandardCharsets.UTF_8);
        RagDocument doc = client.uploadDocument("delete-test.txt", data);
        String docId = doc.id();

        assertTrue(client.deleteDocument(docId), "删除应成功");
        assertFalse(client.documentCount() > 0
                || client.listDocuments(1, 10).stream().anyMatch(d -> d.id().equals(docId)),
                "删除后文档不应存在");

        // 验证向量存储中不再有该文档的分块
        Path filesDir = tempDir.resolve("files");
        if (Files.exists(filesDir)) {
            boolean remainingFile = Files.list(filesDir)
                    .anyMatch(p -> p.getFileName().toString().startsWith(docId));
            assertFalse(remainingFile, "上传目录中不应再有该文档文件");
        }
        System.out.println("[PASS] deleteDocument清理了向量和文件, docId=" + docId);
    }

    @Test
    void testUpdateDocument() throws IOException {
        byte[] oldData = "这是旧版本的内容，需要被替换".getBytes(StandardCharsets.UTF_8);
        RagDocument oldDoc = client.uploadDocument("update-test.txt", oldData);
        String docId = oldDoc.id();

        byte[] newData = "这是新版本的内容，更加详细和完整".getBytes(StandardCharsets.UTF_8);
        RagDocument newDoc = client.updateDocument(docId, "update-test.txt", newData);

        assertNotNull(newDoc, "updateDocument应返回非null");
        assertEquals(docId, newDoc.id(), "更新后docId不变");
        assertEquals("READY", newDoc.status());
        System.out.println("[PASS] updateDocument替换成功, docId=" + docId);
    }

    @Test
    void testListAndCount() {
        client.uploadDocument("doc1.txt", "文档一的内容".getBytes());
        client.uploadDocument("doc2.txt", "文档二的内容".getBytes());
        client.uploadDocument("doc3.txt", "文档三的内容".getBytes());

        assertEquals(3, client.documentCount());
        List<RagDocument> page1 = client.listDocuments(1, 2);
        assertEquals(2, page1.size());
        List<RagDocument> page2 = client.listDocuments(2, 2);
        assertEquals(1, page2.size());
        List<RagDocument> page3 = client.listDocuments(3, 2);
        assertEquals(0, page3.size());
        System.out.println("[PASS] listDocuments分页正确");
    }

    @Test
    void testReadDocumentContent() {
        String content = "读取测试文档的原始内容";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        RagDocument doc = client.uploadDocument("read-test.txt", data);
        String readBack = client.readDocumentContent(doc.id());
        assertNotNull(readBack);
        assertTrue(readBack.contains("读取测试"), "读取内容应包含原文");
        System.out.println("[PASS] readDocumentContent正确");
    }

    private void deleteRecursively(java.io.File file) {
        if (file.isDirectory()) {
            for (java.io.File child : file.listFiles()) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
