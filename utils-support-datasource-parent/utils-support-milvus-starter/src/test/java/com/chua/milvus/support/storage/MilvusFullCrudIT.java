package com.chua.milvus.support.storage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MilvusVectorStorage 完整向量操作值校验测试。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MilvusFullCrudIT {

    private static final String ZILLIZ_HOST = "in03-a2f450287f79a4e.serverless.gcp-us-west1.cloud.zilliz.com";
    private static final String URI = "https://" + ZILLIZ_HOST;
    private static final int PORT = 443;
    private static final String TOKEN = "4f7aa5079132e5e7686f6276ad336aa0160e21c969bad84de3a72f285a350ac9ea931a8469c4288c6a33475a64ed655ab01d97e7";
    private static final String COLLECTION = "test_collection";

    private static MilvusVectorStorage storage;

    @BeforeAll
    static void setup() {
        try {
            var s = new Socket();
            s.connect(new InetSocketAddress(ZILLIZ_HOST, PORT), 5000);
            s.close();
        } catch (Exception e) {
            Assumptions.abort("Zilliz Cloud 不可达，跳过");
        }
        storage = new MilvusVectorStorage(4,
                com.chua.common.support.vector.VectorCompareAlgorithm.cosine(),
                URI, PORT, COLLECTION, TOKEN);
        assertNotNull(storage);
        assertEquals(4, storage.dimension(), "维度应为 4");
        assertTrue(storage.size() >= 0);
    }

    @AfterAll
    static void teardown() {
        if (storage != null) storage.release();
    }

    @Test
    @Order(1)
    void bulkAdd_andVerifySizeIncrease() {
        int before = storage.size();

        assertTrue(storage.add("ft_a", new float[]{0.10f, 0.20f, 0.30f, 0.40f}), "add ft_a");
        assertTrue(storage.add("ft_b", new float[]{0.50f, 0.60f, 0.70f, 0.80f}), "add ft_b");
        assertTrue(storage.add("ft_c", new float[]{0.15f, 0.25f, 0.35f, 0.45f}), "add ft_c");

        assertEquals(before + 3, storage.size(), "批量添加后 size 应 +3");
    }

    @Test
    @Order(2)
    void searchAccuracy_topK() throws Exception {
        Thread.sleep(3000);

        /* top-1 搜索非空 */
        var r1 = storage.search(new float[]{0.10f, 0.20f, 0.30f, 0.40f}, 1);
        assertNotNull(r1);
        assertFalse(r1.isEmpty(), "top-1 搜索不应为空");
        assertEquals(1, r1.size());

        /* top-3 搜索 */
        var r3 = storage.search(new float[]{0.10f, 0.20f, 0.30f, 0.40f}, 3);
        assertNotNull(r3);
        assertEquals(3, r3.size(), "top-3 应返回 3 条");

        /* 搜索结果向量维度应为 4 */
        assertEquals(4, r3.get(0).dimension(), "搜索结果向量维度应为 4");
    }

    @Test
    @Order(3)
    void update_overwriteAndVerify() {
        int before = storage.size();

        assertTrue(storage.update("ft_a", new float[]{0.99f, 0.98f, 0.97f, 0.96f}),
                "update ft_a 应成功");

        /* upsert 后 size 不变 */
        assertEquals(before, storage.size(), "upsert 后 size 不应变化");
    }

    @Test
    @Order(4)
    void removeAndVerifyDecrease() {
        int before = storage.size();
        assertTrue(storage.remove("ft_b"), "remove ft_b 应回 true");
        assertEquals(before - 1, storage.size(), "删除后 size 应 -1");
        assertFalse(storage.remove("ft_b"), "重复 remove 不存在的 id 应回 false");
    }

    @Test
    @Order(5)
    void boundaryCases_doNotCrash() {
        /* 零向量搜索不崩溃 */
        assertDoesNotThrow(() -> storage.search(new float[]{0, 0, 0, 0}, 1));

        /* 大 topK 不崩溃 */
        assertDoesNotThrow(() -> storage.search(new float[]{0.1f, 0.2f, 0.3f, 0.4f}, Integer.MAX_VALUE));

        /* 清理测试数据 */
        for (String id : List.of("ft_a", "ft_c", "ft_d")) {
            storage.remove(id);
        }
    }
}
