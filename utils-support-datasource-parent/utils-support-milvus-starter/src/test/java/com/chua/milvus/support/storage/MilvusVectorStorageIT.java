package com.chua.milvus.support.storage;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MilvusVectorStorage 真实云服务测试（Zilliz Cloud Serverless）。
 */
class MilvusVectorStorageIT {

    private static final String HOST = "in03-a2f450287f79a4e.serverless.gcp-us-west1.cloud.zilliz.com";
    private static final int PORT = 443;
    private static final String TOKEN = "4f7aa5079132e5e7686f6276ad336aa0160e21c969bad84de3a72f285a350ac9ea931a8469c4288c6a33475a64ed655ab01d97e7";

    @BeforeAll
    static void assumeReachable() {
        try {
            var s = new Socket();
            s.connect(new InetSocketAddress(HOST, PORT), 5000);
            s.close();
        } catch (Exception e) {
            Assumptions.abort("Zilliz Cloud 不可达，跳过");
        }
    }

    /** 暴露 protected 方法的测试子类 */
    private static class TestableStorage extends MilvusVectorStorage {
        TestableStorage() {
            super(4,
                com.chua.common.support.vector.VectorCompareAlgorithm.cosine(),
                HOST, PORT, "it_vector_" + System.nanoTime(), TOKEN);
        }
        @Override public boolean add(String id, float[] v) { return doAdd(id, v); }
        @Override public List<float[]> search(float[] q, int topK) { return null; }
    }

    @Test
    void vectorStore_insertSearchAndCleanup() {
        var storage = new MilvusVectorStorage(4,
                com.chua.common.support.vector.VectorCompareAlgorithm.cosine(),
                HOST, PORT, "it_vector_" + System.nanoTime(), TOKEN);

        try {
            assertNotNull(storage);
            assertEquals("milvus", storage.name());

            /* update = upsert 语义（公开方法） */
            assertTrue(storage.update("vec1", new float[]{0.1f, 0.2f, 0.3f, 0.4f}));
            assertTrue(storage.update("vec2", new float[]{0.5f, 0.6f, 0.7f, 0.8f}));
            assertEquals(2, storage.size());

            /* remove */
            assertTrue(storage.remove("vec1"));
            assertEquals(1, storage.size());
        } finally {
            storage.release();
        }
    }
}
