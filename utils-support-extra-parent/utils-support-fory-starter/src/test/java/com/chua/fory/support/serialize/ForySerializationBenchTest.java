package com.chua.fory.support.serialize;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** @author CH */
class ForySerializationBenchTest {

    @Test
    void benchFuryConcurrent() throws Exception {
        ForySerialization ser = new ForySerialization();
        int rows = 1000;
        var data = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < rows; i++) {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", i);
            m.put("name", "user_" + i);
            m.put("age", 20 + i % 40);
            m.put("score", (i * 7) % 100 + 1.0);
            m.put("active", i % 2 == 0);
            m.put("created_at", "2026-01-01");
            data.add(m);
        }

        byte[] bytes = ser.serialize(data);
        Object back = ser.deserialize(bytes, List.class);
        assertEquals(data, back, "序列化/反序列化结果一致");

        int threads = 8;
        int warmupMs = 500;
        int measureMs = 1000;
        int rounds = 3;

        // 单线程预热
        for (int i = 0; i < warmupMs / 10; i++) {
            ser.serialize(data);
            ser.deserialize(bytes, List.class);
        }

        // 单线程基准
        long singleSer = bestOf(() -> { try { ser.serialize(data); } catch (Exception e) { throw new RuntimeException(e); } }, measureMs, rounds);
        long singleDes = bestOf(() -> { try { ser.deserialize(bytes, List.class); } catch (Exception e) { throw new RuntimeException(e); } }, measureMs, rounds);
        long singleAvg = (singleSer + singleDes) / 2;

        System.err.printf("单线程: 序列化=%,d ops/s  反序列化=%,d ops/s  平均=%,d ops/s%n", singleSer, singleDes, singleAvg);

        // 多线程基准
        var executor = Executors.newFixedThreadPool(threads);
        long multiSer = bestOfMulti(executor, threads, () -> { try { ser.serialize(data); } catch (Exception e) { throw new RuntimeException(e); } }, measureMs, rounds);
        long multiDes = bestOfMulti(executor, threads, () -> { try { ser.deserialize(bytes, List.class); } catch (Exception e) { throw new RuntimeException(e); } }, measureMs, rounds);
        long multiAvg = (multiSer + multiDes) / 2;
        executor.shutdown();

        System.err.printf("8线程:   序列化=%,d ops/s  反序列化=%,d ops/s  平均=%,d ops/s%n", multiSer, multiDes, multiAvg);
        System.err.printf("加速比:  序列化=%.1fx  反序列化=%.1fx%n", (double) multiSer / singleSer, (double) multiDes / singleDes);

        assertTrue(multiSer > singleSer * 1.5, "8线程序列化应有显著加速");
        assertTrue(multiDes > singleDes * 1.5, "8线程反序列化应有显著加速");
    }

    long measureOps(Runnable task, long ms) {
        long start = System.nanoTime();
        long deadline = start + ms * 1_000_000L;
        long count = 0;
        while (System.nanoTime() < deadline) { task.run(); count++; }
        return count * 1_000_000_000L / (System.nanoTime() - start);
    }

    long bestOf(Runnable task, long ms, int rounds) {
        long best = 0;
        for (int i = 0; i < rounds; i++) best = Math.max(best, measureOps(task, ms));
        return best;
    }

    long bestOfMulti(ExecutorService executor, int threads, Runnable task, long ms, int rounds) throws Exception {
        long best = 0;
        for (int r = 0; r < rounds; r++) {
            var start = System.nanoTime();
            var deadline = start + ms * 1_000_000L;
            var count = new java.util.concurrent.atomic.AtomicLong(0);
            var latch = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                executor.submit(() -> {
                    long c = 0;
                    while (System.nanoTime() < deadline) { task.run(); c++; }
                    count.addAndGet(c);
                    latch.countDown();
                });
            }
            latch.await();
            long elapsed = System.nanoTime() - start;
            long ops = count.get() * 1_000_000_000L / elapsed;
            best = Math.max(best, ops);
        }
        return best;
    }
}
