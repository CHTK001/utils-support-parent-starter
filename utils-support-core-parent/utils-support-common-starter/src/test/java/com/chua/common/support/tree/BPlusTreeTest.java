package com.chua.common.support.tree;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * B+ 树单元测试。
 */
public class BPlusTreeTest {

    @Test
    public void testSmallInsertAndQuery() {
        BPlusTree<Integer, String> tree = new BPlusTree<>(4);
        for (int i = 0; i < 20; i++) {
            tree.put(i, "v" + i);
        }
        assertEquals(20, tree.size());
        for (int i = 0; i < 20; i++) {
            assertTrue(tree.containsKey(i), "missing key: " + i);
        }
    }

    @Test
    public void test100Sequential() {
        BPlusTree<Integer, String> tree = new BPlusTree<>(100);
        for (int i = 0; i < 100; i++) {
            tree.put(i, "v" + i);
        }
        assertEquals(100, tree.size());
        for (int i = 0; i < 100; i++) {
            assertTrue(tree.containsKey(i), "missing key: " + i);
        }
        // range
        var range = tree.range(0, 100);
        assertEquals(100, range.size());
    }

    @Test
    public void test200Sequential() {
        BPlusTree<Integer, String> tree = new BPlusTree<>(100);
        for (int i = 0; i < 200; i++) {
            tree.put(i, "v" + i);
        }
        assertEquals(200, tree.size());
        for (int i = 0; i < 200; i++) {
            assertTrue(tree.containsKey(i), "missing key: " + i);
        }
        var range = tree.range(0, 200);
        assertEquals(200, range.size());
    }

    @Test
    public void test500Sequential() {
        BPlusTree<Integer, String> tree = new BPlusTree<>(100);
        for (int i = 0; i < 500; i++) {
            tree.put(i, "v" + i);
        }
        assertEquals(500, tree.size());
        for (int i = 0; i < 500; i++) {
            assertTrue(tree.containsKey(i), "missing key: " + i);
        }
        var range = tree.range(0, 500);
        assertEquals(500, range.size());
    }

    @Test
    public void test1000Sequential() {
        BPlusTree<Integer, String> tree = new BPlusTree<>(100);
        for (int i = 0; i < 1000; i++) {
            tree.put(i, "v" + i);
        }
        assertEquals(1000, tree.size());
        for (int i = 0; i < 1000; i++) {
            assertTrue(tree.containsKey(i), "missing key: " + i);
        }
    }
}
