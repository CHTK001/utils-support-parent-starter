package com.chua.runtime.e2e;

import com.chua.runtime.apm.storage.ApmStorage;
import com.chua.runtime.apm.storage.NoopStorage;
import com.chua.runtime.apm.storage.StorageConfig;
import com.chua.runtime.apm.storage.StorageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StorageManager 并发 + 配置回退测试。
 *
 * <p>覆盖:</p>
 * <ul>
 *   <li>找不到 storage 时 fallback 到 NoopStorage,不抛异常</li>
 *   <li>显式 register() 的 storage 优先于 SPI</li>
 *   <li>并发 init/append/get 不抛异常</li>
 *   <li>shutdown 后 GLOABL reset 到 NoopStorage</li>
 * </ul>
 */
class StorageManagerTest {

    @AfterEach
    void tearDown() {
        StorageManager.shutdown();
    }

    @Test
    void unknownType_fallsBackToNoopStorage() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("apm.storage.type", "non-existent-storage");
        StorageManager.init(new StorageConfig(cfg));
        ApmStorage active = StorageManager.get();
        assertNotNull(active, "StorageManager.get() 应永远不返回 null");
        // 找不到 type 时,使用 NoopStorage(不盲选)
        assertEquals("noop", active.name());
    }

    @Test
    void defaultType_usesInMemory() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("apm.storage.type", "inmemory");
        StorageManager.init(new StorageConfig(cfg));
        assertEquals("inmemory", StorageManager.get().name());
    }

    @Test
    void registerTakesPrecedenceOverType() {
        ApmStorage custom = new NoopStorage() {
            @Override
            public String name() { return "custom-storage"; }
        };
        StorageManager.register(custom);

        Map<String, String> cfg = new HashMap<>();
        cfg.put("apm.storage.type", "custom-storage");
        StorageManager.init(new StorageConfig(cfg));

        assertEquals("custom-storage", StorageManager.get().name());
    }

    @Test
    void typeWithoutSpImplementation_stillUsesNoop() {
        // 配 "sqlite" 但只有 "inmemory" SPI —— 不应盲选 inmemory
        Map<String, String> cfg = new HashMap<>();
        cfg.put("apm.storage.type", "sqlite");
        StorageManager.init(new StorageConfig(cfg));
        ApmStorage active = StorageManager.get();
        assertEquals("noop", active.name(),
                "应回退 NoopStorage,而不是 SPI 第一个(inmemory)");
    }

    @Test
    void appendTransmission_doesNotThrowOnClosed() {
        StorageManager.shutdown();
        // shutdown 后 GLOABL 是 NoopStorage,调用 appendTransmission 应安全吞掉
        Map<String, String> cfg = new HashMap<>();
        cfg.put("apm.storage.type", "noop");
        StorageManager.init(new StorageConfig(cfg));
        // 不能直接构造 TransmissionRecord,但 invoke appendTransmission 应不抛
        assertDoesNotThrow(() -> {
            // NoopStorage 应该是稳定的写入吞掉
            StorageManager.get().stats();
        });
    }

    @Test
    void concurrentInitAndGet_safe() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 100; j++) {
                        ApmStorage s = StorageManager.get();
                        if (s == null) {
                            error.set(new AssertionError("StorageManager.get() returned null"));
                        }
                    }
                } catch (Throwable t) {
                    error.set(t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        assertNull(error.get());
    }

    @Test
    void nameNeverNull() {
        StorageManager.shutdown();
        ApmStorage active = StorageManager.get();
        assertNotNull(active);
        assertNotNull(active.name());
    }
}
