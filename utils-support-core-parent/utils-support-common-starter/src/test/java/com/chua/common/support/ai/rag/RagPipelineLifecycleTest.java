package com.chua.common.support.ai.rag;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.MemoryChatClient;
import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.common.support.ai.embedding.EmbeddingClientSetting;
import com.chua.common.support.ai.splitter.SentenceTextSplitter;
import com.chua.common.support.ai.splitter.TextChunk;
import com.chua.common.support.vector.MemoryVectorStorage;
import com.chua.common.support.vector.VectorCompareAlgorithm;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RagPipeline 生命周期冒烟测试（update / delete / list / read / reindex）。
 *
 * <p>遵循项目约定使用 {@code main} 方法直接运行（本模块无 JUnit 依赖）：</p>
 * <pre>
 * 运行方式：{@code java com.chua.common.support.ai.rag.RagPipelineLifecycleTest}
 * 全部通过输出 PASS，任一失败输出 FAIL 并以非零码退出。
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RagPipelineLifecycleTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testUploadAndCount();
        testUpdateKeepsDocId();
        testDeleteRemovesFromVectorAndDocs();
        testDeleteNonexistentReturnsFalse();
        testListDocumentsPagination();
        testReadDocumentContent();
        testReindex();
        testSplitterProducesChunks();

        System.out.println("");
        System.out.println("[RESULT] passed=" + passed + " failed=" + failed);
        System.out.println(failed == 0 ? "ALL-PASSED" : "HAS-FAILURES");
        System.exit(failed == 0 ? 0 : 1);
    }

    static void testUploadAndCount() {
        RagPipeline p = newPipeline();
        RagDocument d1 = p.uploadDocument("a.txt", "first document content".getBytes(StandardCharsets.UTF_8));
        check("READY".equals(d1.status()), "upload returns READY");
        check(p.documentCount() == 1, "documentCount == 1 after 1 upload");
        p.close();
    }

    static void testUpdateKeepsDocId() {
        RagPipeline p = newPipeline();
        RagDocument d1 = p.uploadDocument("a.txt", "first version of document content".getBytes(StandardCharsets.UTF_8));
        RagDocument d2 = p.updateDocument(d1.id(), "a.txt", "second version with entirely different content".getBytes(StandardCharsets.UTF_8));
        check(d1.id().equals(d2.id()), "update keeps the same docId");
        check("READY".equals(d2.status()), "update returns READY");
        check(p.documentCount() == 1, "documentCount stays 1 after update (no duplicate)");
        p.close();
    }

    static void testDeleteRemovesFromVectorAndDocs() {
        RagPipeline p = newPipeline();
        RagDocument d1 = p.uploadDocument("a.txt", "hello world, this is a test document about RAG".getBytes(StandardCharsets.UTF_8));
        RagDocument d2 = p.uploadDocument("b.txt", "another test document about something else entirely different".getBytes(StandardCharsets.UTF_8));
        boolean removed = p.deleteDocument(d1.id());
        check(removed, "deleteDocument returns true for existing doc");
        check(p.documentCount() == 1, "documentCount == 1 after delete");
        check(p.readDocumentContent(d1.id()) == null, "readDocumentContent returns null after delete");
        check(p.readDocumentContent(d2.id()) != null, "readDocumentContent still works for surviving doc");
        p.close();
    }

    static void testDeleteNonexistentReturnsFalse() {
        RagPipeline p = newPipeline();
        check(!p.deleteDocument("nonexistent-doc-id"), "deleteDocument returns false for unknown docId");
        p.close();
    }

    static void testListDocumentsPagination() {
        RagPipeline p = newPipeline();
        for (int i = 0; i < 5; i++) {
            p.uploadDocument("doc" + i + ".txt", ("document number " + i + " content for retrieval").getBytes(StandardCharsets.UTF_8));
        }
        check(p.documentCount() == 5, "documentCount == 5");
        List<RagDocument> page1 = p.listDocuments(1, 3);
        List<RagDocument> page2 = p.listDocuments(2, 3);
        List<RagDocument> page3 = p.listDocuments(3, 3);
        check(page1.size() == 3, "page1 size == 3");
        check(page2.size() == 2, "page2 size == 2");
        check(page3.isEmpty(), "page3 is empty (out of range)");
        p.close();
    }

    static void testReadDocumentContent() {
        RagPipeline p = newPipeline();
        byte[] data = "readable content here".getBytes(StandardCharsets.UTF_8);
        RagDocument d = p.uploadDocument("c.txt", data);
        check("readable content here".equals(p.readDocumentContent(d.id())),
                "readDocumentContent returns original text");
        p.close();
    }

    static void testReindex() {
        RagPipeline p = newPipeline();
        p.uploadDocument("a.txt", "reindex test document about RAG".getBytes(StandardCharsets.UTF_8));
        p.uploadDocument("b.txt", "second document for reindex".getBytes(StandardCharsets.UTF_8));
        int count = p.reindex();
        check(count == 2, "reindex returns 2 (all READY docs re-indexed)");
        check(p.documentCount() == 2, "documentCount unchanged after reindex");
        p.close();
    }

    static void testSplitterProducesChunks() {
        SentenceTextSplitter splitter = new SentenceTextSplitter(100, 20);
        List<TextChunk> chunks = splitter.split(
                "First sentence is long enough to stand alone. "
                        + "Second sentence follows the first one. "
                        + "Third sentence wraps things up nicely.");
        check(chunks.size() >= 1, "splitter produces at least 1 chunk");
        check(chunks.get(0).index() == 0, "first chunk index is 0");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static final String UPLOAD_DIR = System.getProperty("java.io.tmpdir")
            + "rag-lifecycle-" + System.nanoTime();

    static RagPipeline newPipeline() {
        MemoryVectorStorage storage = new MemoryVectorStorage(1536, VectorCompareAlgorithm.cosine());
        EmbeddingClient emb = EmbeddingClient.create(EmbeddingClientSetting.builder().provider("memory").build())
                .dimensions(1536);
        ChatClient chat = new MemoryChatClient();
        return RagPipeline.builder()
                .chatClient(chat)
                .embeddingClient(emb)
                .textSplitter(new SentenceTextSplitter(100, 20))
                .vectorStorage(storage)
                .uploadDir(UPLOAD_DIR)
                .topK(5)
                .similarityThreshold(0.0)
                .build();
    }

    static void check(boolean ok, String message) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + message);
        } else {
            failed++;
            System.out.println("[FAIL] " + message);
        }
    }
}
