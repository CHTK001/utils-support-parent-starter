package com.chua.runtime.e2e;

import com.chua.runtime.apm.ApmBootstrap;
import com.chua.runtime.apm.handler.FileHandler;
import com.chua.runtime.apm.handler.NetHandler;
import com.chua.runtime.apm.handler.LogHandler;
import com.chua.runtime.apm.handler.TraceHandler;
import com.chua.runtime.apm.handler.TransmissionHandler;
import com.chua.runtime.apm.handler.DependencyGraphHandler;
import com.chua.runtime.apm.handler.HandleLeakHandler;
import com.chua.runtime.apm.handler.JedisHandler;
import com.chua.runtime.apm.handler.KafkaHandler;
import com.chua.runtime.apm.handler.ZooKeeperHandler;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ApmBootstrap 启动顺序与线程安全测试。
 */
class ApmBootstrapTest {

    @Test
    void registersAllDefaultHandlers() {
        ApmBootstrap boot = new ApmBootstrap(Paths.get(System.getProperty("java.io.tmpdir")));
        assertEquals(10, boot.getHandlers().size());
        assertNotNull(boot.getHandler(LogHandler.class));
        assertNotNull(boot.getHandler(NetHandler.class));
        assertNotNull(boot.getHandler(FileHandler.class));
        assertNotNull(boot.getHandler(TraceHandler.class));
        assertNotNull(boot.getHandler(TransmissionHandler.class));
        assertNotNull(boot.getHandler(DependencyGraphHandler.class));
        assertNotNull(boot.getHandler(HandleLeakHandler.class));
        assertNotNull(boot.getHandler(JedisHandler.class));
        assertNotNull(boot.getHandler(KafkaHandler.class));
        assertNotNull(boot.getHandler(ZooKeeperHandler.class));
    }

    /**
     * 100 线程并发读 getHandler/getHandlers,无 CME。
     */
    @Test
    void concurrentHandlerAccess_safe() throws Exception {
        ApmBootstrap boot = new ApmBootstrap(Paths.get(System.getProperty("java.io.tmpdir")));
        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Throwable[] errors = new Throwable[threads];
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 100; j++) {
                        boot.getHandler(LogHandler.class);
                        boot.getHandlers().size();
                    }
                } catch (Throwable e) {
                    errors[tid] = e;
                }
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        for (Throwable e : errors) {
            assertNull(e, "并发读抛出 " + e);
        }
    }

    @Test
    void status_returnsAllHandlerStatuses() {
        ApmBootstrap boot = new ApmBootstrap(Paths.get(System.getProperty("java.io.tmpdir")));
        String status = boot.status();
        assertNotNull(status);
        assertTrue(status.contains("LogHandler"));
        assertTrue(status.contains("NetHandler"));
    }
}
