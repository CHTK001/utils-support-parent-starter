package com.chua.milvus.support.storage;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MilvusVectorStorage 真实云服务连接测试（Zilliz Cloud Serverless）。
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

    @Test
    void connectAndVerify() throws Exception {
        var storage = new MilvusVectorStorage(4,
                com.chua.common.support.vector.VectorCompareAlgorithm.cosine(),
                HOST, PORT, "it_vector_" + System.nanoTime(), TOKEN);

        try {
            assertNotNull(storage);
            assertEquals(4, storage.dimension(), "维度应为 4");

            /* add（AbstractVectorStorage 公开方法） */
            assertTrue(storage.add("vec1", new float[]{0.1f, 0.2f, 0.3f, 0.4f}));
            assertTrue(storage.add("vec2", new float[]{0.5f, 0.6f, 0.7f, 0.8f}));

            /* search */
            var results = storage.search(new float[]{0.1f, 0.2f, 0.3f, 0.4f}, 2);
            assertNotNull(results);

            /* remove + size */
            assertTrue(storage.remove("vec1"));
            assertEquals(1, storage.size());
        } finally {
            storage.release();
        }
    }
}
