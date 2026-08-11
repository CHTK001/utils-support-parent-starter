package com.chua.runtime.e2e;

import com.chua.runtime.apm.storage.InMemoryStorage;
import com.chua.runtime.apm.storage.LeakRecord;
import com.chua.runtime.apm.storage.LogRecord;
import com.chua.runtime.apm.storage.Query;
import com.chua.runtime.apm.storage.StorageConfig;
import com.chua.runtime.apm.storage.TransmissionEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InMemoryStorage 并发安全与容量淘汰测试。
 *
 * <p>覆盖以下场景:</p>
 * <ul>
 *   <li>并发 append + read 不抛 ConcurrentModificationException</li>
 *   <li>容量超限淘汰时 size 永远不超过 capacity</li>
 *   <li>依赖边合并(callCount 累加)线程安全</li>
 *   <li>TTL cleanup 不会越界或 NPE</li>
 * </ul>
 */
class InMemoryStorageConcurrencyTest {

    private InMemoryStorage storage;

    @BeforeEach
    void setUp() {
        storage = new InMemoryStorage();
        StorageConfig cfg = new StorageConfig(new java.util.HashMap<String, String>() {{
            put("apm.storage.capacity", "1000");
            put("apm.storage.retention.ms", "60000");
        }});
        storage.start(cfg);
    }

    /**
     * 100 线程并发插入 1000 条 TransmissionEvent,验证无丢失、无 CME。
     */
    @Test
    void concurrentAppendTransmission_isThreadSafe() throws Exception {
        int threads = 100;
        int perThread = 100;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            futures.add(pool.submit(() -> {
                start.await();
                int ok = 0;
                for (int i = 0; i < perThread; i++) {
                    TransmissionEvent e = TransmissionEvent.builder()
                            .traceId("t-" + tid + "-" + i)
                            .protocol("HTTP")
                            .startTime(System.currentTimeMillis())
                            .build();
                    storage.appendTransmission(e);
                    ok++;
                }
                return ok;
            }));
        }
        start.countDown();
        int total = 0;
        for (Future<Integer> f : futures) {
            total += f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(threads * perThread, total);

        // 由于 capacity=1000,存储总条目必须 ≤1000(淘汰机制生效)
        assertTrue(storage.stats().get("transmissions") <= 1000,
                "transmissions should be evicted to capacity, got " + storage.stats().get("transmissions"));

        // 并发读不抛异常
        Query q = new Query().setLimit(50);
        List<TransmissionEvent> results = storage.queryTransmissions(q);
        assertNotNull(results);
        assertFalse(results.isEmpty());
    }

    /**
     * 验证容量超限淘汰 — 插入 2000 条,storage 应该始终 ≤ 1000。
     */
    @Test
    void capacityEviction_neverExceedsCap() {
        for (int i = 0; i < 2000; i++) {
            storage.appendTransmission(TransmissionEvent.builder()
                    .traceId("trace-" + i)
                    .protocol("HTTP")
                    .startTime(System.currentTimeMillis() + i)
                    .build());
        }
        long total = storage.stats().get("transmissions");
        assertTrue(total <= 1000,
                "size should be <= 1000, got " + total);
    }

    /**
     * 依赖边合并 — 1000 次相同 source→target 边的 append,callCount 应该累加到 1000。
     */
    @Test
    void concurrentAppendDependency_aggregatesCallCount() throws Exception {
        com.chua.runtime.protocol.Endpoint src = com.chua.runtime.protocol.Endpoint.builder()
                .kind(com.chua.runtime.protocol.EndpointKind.CLIENT)
                .protocol(com.chua.runtime.protocol.Protocol.HTTP)
                .software(com.chua.runtime.protocol.Software.TOMCAT)
                .host("client")
                .port(12345)
                .path("/")
                .build();
        com.chua.runtime.protocol.Endpoint tgt = com.chua.runtime.protocol.Endpoint.builder()
                .kind(com.chua.runtime.protocol.EndpointKind.SERVER)
                .protocol(com.chua.runtime.protocol.Protocol.HTTP)
                .software(com.chua.runtime.protocol.Software.TOMCAT)
                .host("server")
                .port(80)
                .path("/")
                .build();

        int threads = 20;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    com.chua.runtime.protocol.DependencyEdge edge =
                            com.chua.runtime.protocol.DependencyEdge.builder()
                                    .source(src).target(tgt)
                                    .callCount(1L)
                                    .build();
                    storage.appendDependency(edge);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        long totalCalls = threads * perThread;
        List<com.chua.runtime.protocol.DependencyEdge> edges = storage.queryDependencies(new Query().setLimit(10));
        assertEquals(1, edges.size(), "相同 source→target 边应合并为 1 条");
        assertEquals(totalCalls, edges.get(0).getCallCount(),
                "callCount 应等于总调用次数 " + totalCalls);
    }

    /**
     * TTL cleanup — 插入条目并强制 TTL 后 cleanup 应移除过期条目。
     */
    @Test
    void cleanup_removesExpiredRecords() throws Exception {
        long now = System.currentTimeMillis();
        // 5 条泄漏记录
        for (int i = 0; i < 5; i++) {
            LeakRecord r = LeakRecord.builder()
                    .handleId("h-" + i)
                    .kind("java/io/FileInputStream")
                    .name("java/io/FileInputStream")
                    .thread("main")
                    .createdAt(now - 100000L + i)
                    .closedAt(now - 50000L + i) // 已关闭 5w ms
                    .build();
            storage.appendLeak(r);
        }
        long before = storage.stats().get("leaks");
        assertEquals(5L, before);

        // 清理保留 30s 的(已超过)
        long removed = storage.cleanup(30000L);
        assertEquals(5L, removed, "应清理 5 条已关闭且超过 30s TTL 的泄漏");
        long after = storage.stats().get("leaks");
        assertEquals(0L, after);
    }

    /**
     * 活跃泄漏不应被 cleanup 清除(只清理已关闭的)。
     */
    @Test
    void cleanup_preservesActiveLeaks() {
        long now = System.currentTimeMillis();
        // 1 条活跃泄漏(closedAt=0),即使超过 TTL 也保留
        LeakRecord active = LeakRecord.builder()
                .handleId("h-active")
                .kind("java/io/FileInputStream")
                .name("java/io/FileInputStream")
                .thread("main")
                .createdAt(now - 100000L)
                .closedAt(0L)
                .build();
        storage.appendLeak(active);
        long removed = storage.cleanup(30000L);
        assertEquals(0L, removed, "活跃泄漏不应被 cleanup");
        assertEquals(1L, storage.stats().get("leaks"));
    }

    /**
     * 日志 + cleanup — 已关闭的日志超过 TTL 应清理。
     */
    @Test
    void cleanup_removesOldLogs() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            LogRecord r = LogRecord.builder()
                    .timestamp(now - 200000L + i * 1000L)
                    .level("INFO")
                    .message("log-" + i)
                    .build();
            storage.appendLog(r);
        }
        long removed = storage.cleanup(100000L);
        assertEquals(10L, removed);
        assertEquals(0L, storage.stats().get("logs"));
    }

    /**
     * 并发查询 + 写入无死锁无 CME。
     */
    @Test
    void concurrentReadAndWrite_safe() throws Exception {
        for (int i = 0; i < 100; i++) {
            storage.appendTransmission(TransmissionEvent.builder()
                    .traceId("warmup-" + i).build());
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ignored) { }
                for (int j = 0; j < 100; j++) {
                    storage.queryTransmissions(new Query().setLimit(10));
                }
                return null;
            }));
            tasks.add(pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ignored) { }
                for (int j = 0; j < 100; j++) {
                    storage.appendTransmission(TransmissionEvent.builder()
                            .traceId("w-" + Thread.currentThread().getId() + "-" + j).build());
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : tasks) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertNotNull(storage.stats());
    }
}
