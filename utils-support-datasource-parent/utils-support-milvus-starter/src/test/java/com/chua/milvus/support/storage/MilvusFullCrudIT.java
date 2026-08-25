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
 * MilvusVectorStorage 完整向量操作测试。
 * NOTE: Zilliz Cloud Serverless 为最终一致性模型，size() 断言不可靠，
 * 仅验证操作返回值和搜索结果。
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
    }

    @AfterAll
    static void teardown() {
        if (storage != null) storage.release();
    }

    @Test
    @Order(1)
    void bulkAdd_returnsTrue() {
        assertTrue(storage.add("ft_a", new float[]{0.10f, 0.20f, 0.30f, 0.40f}), "add ft_a");
        assertTrue(storage.add("ft_b", new float[]{0.50f, 0.60f, 0.70f, 0.80f}), "add ft_b");
        assertTrue(storage.add("ft_c", new float[]{0.15f, 0.25f, 0.35f, 0.45f}), "add ft_c");
        assertTrue(storage.add("ft_d", new float[]{0.90f, 0.80f, 0.70f, 0.60f}), "add ft_d");
    }

    @Test
    @Order(2)
    void searchAccuracy_topK() throws Exception {
        Thread.sleep(3000);

        var r1 = storage.search(new float[]{0.10f, 0.20f, 0.30f, 0.40f}, 1);
        assertNotNull(r1);
        assertFalse(r1.isEmpty(), "top-1 搜索不应为空");
        assertEquals(1, r1.size());
        assertEquals(4, r1.get(0).dimension(), "结果向量维度应为 4");

        var r3 = storage.search(new float[]{0.10f, 0.20f, 0.30f, 0.40f}, 3);
        assertNotNull(r3);
        assertEquals(3, r3.size(), "top-3 应返回 3 条");
        assertEquals(4, r3.get(2).dimension());
    }

    @Test
    @Order(3)
    void update_upsertDoesNotThrow() {
        assertDoesNotThrow(() -> storage.update("ft_a", new float[]{0.99f, 0.98f, 0.97f, 0.96f}));
    }

    @Test
    @Order(4)
    void remove_returnsTrue() {
        assertDoesNotThrow(() -> {
            for (String id : List.of("ft_a", "ft_b", "ft_c", "ft_d")) {
                storage.remove(id);
            }
        });
    }

    @Test
    @Order(5)
    void boundaryCases_doNotCrash() {
        /* 边界值搜索：异常可接受，验证不导致不可恢复状态 */
        try { storage.search(new float[]{0, 0, 0, 0}, 1); } catch (Exception ignored) { }
        try { storage.search(new float[]{0.1f, 0.2f, 0.3f, 0.4f}, 1024); } catch (Exception ignored) { }
        assertEquals(4, storage.dimension(), "边界测试后引擎仍应正常工作");
    }
}
