package com.chua.common.support.vector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VectorStorage 基础功能测试
 */
class VectorStorageTest {

    private VectorStorage storage;

    @BeforeEach
    void setUp() {
        storage = VectorStorageBuilder.newBuilder()
                .dimension(3)
                .algorithm(VectorCompareAlgorithm.COSINE)
                .build();
    }

    @AfterEach
    void tearDown() {
        if (storage != null) storage.close();
    }

    @Test
    void testAddAndSearch() {
        storage.add(new Vector("v1", new float[]{1.0f, 0.0f, 0.0f}));
        storage.add(new Vector("v2", new float[]{0.0f, 1.0f, 0.0f}));
        storage.add(new Vector("v3", new float[]{1.0f, 1.0f, 0.0f}));

        assertEquals(3, storage.size());

        List<Vector> results = storage.search(new float[]{1.0f, 0.1f, 0.0f}, 2);
        assertEquals(2, results.size());
        assertEquals("v1", results.get(0).id()); // highest similarity
    }

    @Test
    void testRemoveByIdPrefix() {
        storage.add(new Vector("doc1_chunk0", new float[]{1.0f, 0.0f, 0.0f}));
        storage.add(new Vector("doc1_chunk1", new float[]{0.0f, 1.0f, 0.0f}));
        storage.add(new Vector("doc2_chunk0", new float[]{1.0f, 1.0f, 0.0f}));
        storage.add(new Vector("other_item", new float[]{0.5f, 0.5f, 0.5f}));

        assertEquals(4, storage.size());

        int removed = storage.removeByIdPrefix("doc1_");
        assertEquals(2, removed);
        assertEquals(2, storage.size());

        // other_item and doc2_chunk0 should remain
        List<Vector> remaining = storage.search(new float[]{1.0f, 1.0f, 0.0f}, 10);
        assertEquals(2, remaining.size());
    }

    @Test
    void testRemoveNonExistentPrefix() {
        storage.add(new Vector("doc1_chunk0", new float[]{1.0f, 0.0f, 0.0f}));
        int removed = storage.removeByIdPrefix("nonexistent_");
        assertEquals(0, removed);
        assertEquals(1, storage.size());
    }

    @Test
    void testRemoveSingle() {
        storage.add(new Vector("only_one", new float[]{1.0f, 0.0f, 0.0f}));
        assertTrue(storage.remove("only_one"));
        assertFalse(storage.remove("only_one")); // second remove should fail
        assertEquals(0, storage.size());
    }

    @Test
    void testClear() {
        storage.add(new Vector("v1", new float[]{1.0f, 0.0f, 0.0f}));
        storage.add(new Vector("v2", new float[]{0.0f, 1.0f, 0.0f}));
        storage.clear();
        assertEquals(0, storage.size());
    }
}
