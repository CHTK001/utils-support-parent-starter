package com.chua.milvus.support.storage;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MilvusVectorStorage 真实云服务连接测试（Zilliz Cloud Serverless）。
 * 验证：TLS 连接、认证、集合加载、维度校验。
 */
class MilvusVectorStorageIT {

    private static final String ZILLIZ_HOST = "in03-a2f450287f79a4e.serverless.gcp-us-west1.cloud.zilliz.com";
    private static final String MILVUS_URI = "https://" + ZILLIZ_HOST;
    private static final int PORT = 443;
    private static final String TOKEN = "4f7aa5079132e5e7686f6276ad336aa0160e21c969bad84de3a72f285a350ac9ea931a8469c4288c6a33475a64ed655ab01d97e7";

    @BeforeAll
    static void assumeReachable() {
        try {
            var s = new Socket();
            s.connect(new InetSocketAddress(ZILLIZ_HOST, PORT), 5000);
            s.close();
        } catch (Exception e) {
            Assumptions.abort("Zilliz Cloud 不可达，跳过");
        }
    }

    @Test
    void connectAndVerify() throws Exception {
        var storage = new MilvusVectorStorage(4,
                com.chua.common.support.vector.VectorCompareAlgorithm.cosine(),
                MILVUS_URI, PORT, "test_collection", TOKEN);

        try {
            assertNotNull(storage);
            assertEquals(4, storage.dimension(), "维度应为 4");
            assertTrue(storage.size() >= 0, "size 应 >= 0");
        } finally {
            storage.release();
        }
    }

    @Test
    void vectorAddAndSearch() throws Exception {
        var storage = new MilvusVectorStorage(4,
                com.chua.common.support.vector.VectorCompareAlgorithm.cosine(),
                MILVUS_URI, PORT, "test_collection", TOKEN);

        try {
            String id = "vt_" + System.nanoTime();
            assertTrue(storage.add(id, new float[]{0.11f, 0.22f, 0.33f, 0.44f}));

            Thread.sleep(3000);

            var results = storage.search(new float[]{0.11f, 0.22f, 0.33f, 0.44f}, 1);
            assertNotNull(results);
            assertFalse(results.isEmpty(), "搜索应命中至少一条");
        } finally {
            storage.release();
        }
    }
}
