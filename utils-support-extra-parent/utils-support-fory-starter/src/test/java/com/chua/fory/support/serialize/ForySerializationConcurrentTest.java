package com.chua.fory.support.serialize;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ForySerializationConcurrentTest {

    @Test
    void concurrentSerializeDeserialize() throws Exception {
        ForySerialization ser = new ForySerialization();
        int threads = 8;
        int rounds = 500;
        AtomicInteger errors = new AtomicInteger(0);
        var executor = Executors.newFixedThreadPool(threads);

        var map = new HashMap<String, Object>();
        map.put("id", 12345);
        map.put("name", "hello fury");
        map.put("list", Arrays.asList("a", "b", "c"));
        map.put("nested", Map.of("x", 1, "y", 2));

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                for (int i = 0; i < rounds; i++) {
                    try {
                        byte[] data = ser.serialize(map);
                        Object result = ser.deserialize(data, Object.class);
                        assertNotNull(result);
                        assertInstanceOf(Map.class, result);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "并发序列化/反序列化不应出现异常");
    }

    @Test
    void concurrentSerializeDeserializeMixed() throws Exception {
        ForySerialization ser = new ForySerialization();
        int threads = 8;
        int rounds = 500;
        AtomicInteger errors = new AtomicInteger(0);
        var executor = Executors.newFixedThreadPool(threads);

        var maps = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 10; i++) {
            var m = new HashMap<String, Object>();
            m.put("index", i);
            m.put("data", "value-" + i);
            m.put("list", Arrays.asList(i, i * 2, i * 3));
            maps.add(m);
        }

        for (int t = 0; t < threads; t++) {
            int threadIdx = t;
            executor.submit(() -> {
                for (int i = 0; i < rounds; i++) {
                    try {
                        var m = maps.get((threadIdx + i) % maps.size());
                        byte[] data = ser.serialize(m);
                        Object result = ser.deserialize(data, Object.class);
                        assertNotNull(result);
                        assertInstanceOf(Map.class, result);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "混合数据并发序列化/反序列化不应出现异常");
    }
}