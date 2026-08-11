package com.chua.runtime.e2e;

import com.chua.runtime.apm.handler.BoundedRecordList;
import org.junit.jupiter.api.Test;

import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BoundedRecordList 单元测试 — 线程安全 + O(1) 驱逐 + 不可变快照。
 */
class BoundedRecordListTest {

    @Test
    void basicAddAndSize() {
        BoundedRecordList<String> list = new BoundedRecordList<>(10);
        list.add("a");
        list.add("b");
        list.add("c");
        assertEquals(3, list.size());
    }

    @Test
    void nullAdd_isIgnored() {
        BoundedRecordList<String> list = new BoundedRecordList<>(10);
        list.add(null);
        list.add("a");
        assertEquals(1, list.size());
    }

    @Test
    void capacityExceeded_evictsOldest() {
        BoundedRecordList<Integer> list = new BoundedRecordList<>(5);
        for (int i = 1; i <= 10; i++) list.add(i);
        assertEquals(5, list.size());
        // 最老的 1-5 应被淘汰,保留 6-10
        List<Integer> snapshot = list.snapshot();
        assertEquals(6, snapshot.get(0));
        assertEquals(10, snapshot.get(snapshot.size() - 1));
    }

    @Test
    void snapshot_isUnmodifiable() {
        BoundedRecordList<String> list = new BoundedRecordList<>(10);
        list.add("a");
        List<String> snap = list.snapshot();
        assertEquals(1, snap.size());
        assertThrows(UnsupportedOperationException.class,
                () -> snap.add("b"));
    }

    @Test
    void tail_returnsLastN() {
        BoundedRecordList<Integer> list = new BoundedRecordList<>(10);
        for (int i = 1; i <= 5; i++) list.add(i);
        List<Integer> last3 = list.tail(3);
        assertEquals(3, last3.size());
        assertEquals(3, last3.get(0));
        assertEquals(5, last3.get(2));
    }

    @Test
    void tail_largerThanSize_returnsAll() {
        BoundedRecordList<Integer> list = new BoundedRecordList<>(10);
        for (int i = 1; i <= 3; i++) list.add(i);
        List<Integer> all = list.tail(10);
        assertEquals(3, all.size());
    }

    @Test
    void tailZeroOrNegative_returnsEmpty() {
        BoundedRecordList<String> list = new BoundedRecordList<>(10);
        list.add("a");
        assertTrue(list.tail(0).isEmpty());
        assertTrue(list.tail(-1).isEmpty());
    }

    @Test
    void iterator_doesNotThrowCME_duringConcurrentAdd() throws Exception {
        final BoundedRecordList<Integer> list = new BoundedRecordList<>(1000);
        for (int i = 0; i < 100; i++) list.add(i);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 100; j++) {
                        for (Integer ignored : list) {
                            // 迭代期间其他线程在 add —— 不抛 CME
                        }
                        list.add(ThreadLocalRandom.current().nextInt());
                    }
                } catch (Throwable e) {
                    error.set(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        assertNull(error.get(), "并发迭代 + add 应安全");
    }

    @Test
    void addOverCapacity_neverExceeds() throws Exception {
        final BoundedRecordList<Integer> list = new BoundedRecordList<>(100);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ignored) { }
                for (int i = 0; i < 1000; i++) {
                    list.add(i);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        // 容量上限必须严格守住
        assertTrue(list.size() <= 100, "size=" + list.size() + " 超过 100");
    }

    @Test
    void clear_emptiesList() {
        BoundedRecordList<String> list = new BoundedRecordList<>(10);
        list.add("a"); list.add("b");
        list.clear();
        assertEquals(0, list.size());
        assertTrue(list.snapshot().isEmpty());
    }
}
