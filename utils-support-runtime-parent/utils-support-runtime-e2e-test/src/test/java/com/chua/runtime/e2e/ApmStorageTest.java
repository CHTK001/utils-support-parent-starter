package com.chua.runtime.e2e;

import com.chua.runtime.apm.storage.InMemoryStorage;
import com.chua.runtime.apm.storage.LeakRecord;
import com.chua.runtime.apm.storage.LogRecord;
import com.chua.runtime.apm.storage.Query;
import com.chua.runtime.apm.storage.StorageConfig;
import com.chua.runtime.apm.storage.StorageManager;
import com.chua.runtime.apm.storage.TransmissionEvent;
import com.chua.runtime.protocol.DependencyEdge;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;
import com.chua.runtime.protocol.StatusCode;
import com.chua.runtime.protocol.TransmissionRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Storage SPI 单元测试 — NoopStorage + InMemoryStorage + StorageManager。
 *
 * @author CH
 * @since 4.0.0.42
 */
class ApmStorageTest {

    @Test
    void noopStorageDropsAllWrites() {
        // 使用临时 StorageManager.reset 隔离全局状态
        com.chua.runtime.apm.storage.StorageManager.shutdown();
        com.chua.runtime.apm.storage.StorageManager.init(
                new com.chua.runtime.apm.storage.StorageConfig(java.util.Map.of(
                        "apm.storage.type", "noop"
                )));
        com.chua.runtime.apm.storage.ApmStorage storage =
                com.chua.runtime.apm.storage.StorageManager.get();
        TransmissionEvent e = TransmissionEvent.builder()
                .traceId("t1").protocol("HTTP").build();
        storage.appendTransmission(e);
        assertEquals(0, storage.queryTransmissions(Query.all()).size());
        assertEquals("noop", storage.name());
    }

    @Test
    void inmemoryStorageRoundTripsTransmission() {
        InMemoryStorage storage = new InMemoryStorage();
        storage.start(StorageConfig.defaults());
        TransmissionRecord r = new TransmissionRecord();
        r.setTraceId("trace-1");
        r.setSpanId("span-1");
        r.setProtocol(Protocol.HTTP);
        r.setSoftware(Software.TOMCAT);
        r.setOperation("GET /api/x");
        r.setStartTime(1000L);
        r.setEndTime(1010L);
        r.setDuration(10L);
        r.setStatus(StatusCode.OK);
        r.setSource(Endpoint.builder().kind(com.chua.runtime.protocol.EndpointKind.CLIENT)
                .protocol(Protocol.HTTP).host("127.0.0.1").port(8080).build());
        r.setTarget(Endpoint.builder().kind(com.chua.runtime.protocol.EndpointKind.SERVER)
                .protocol(Protocol.HTTP).host("localhost").port(9090).build());
        storage.appendTransmission(TransmissionEvent.fromRecord(r));

        List<TransmissionEvent> result = storage.queryTransmissions(Query.all());
        assertEquals(1, result.size());
        TransmissionEvent got = result.get(0);
        assertEquals("trace-1", got.getTraceId());
        assertEquals("HTTP", got.getProtocol());
        assertEquals("TOMCAT", got.getSoftware());
        assertEquals(8080, got.getSourcePort());
        assertEquals(9090, got.getTargetPort());
    }

    @Test
    void inmemoryStorageAccumulatesDependencyEdges() {
        InMemoryStorage storage = new InMemoryStorage();
        storage.start(StorageConfig.defaults());

        DependencyEdge e1 = DependencyEdge.builder()
                .source(Endpoint.builder().kind(com.chua.runtime.protocol.EndpointKind.CLIENT)
                        .protocol(Protocol.HTTP).host("a").port(1).build())
                .target(Endpoint.builder().kind(com.chua.runtime.protocol.EndpointKind.SERVER)
                        .protocol(Protocol.HTTP).host("b").port(2).build())
                .protocol(Protocol.HTTP).software(Software.TOMCAT)
                .callCount(1L).totalDuration(100L)
                .build();
        DependencyEdge e2 = DependencyEdge.builder()
                .source(Endpoint.builder().kind(com.chua.runtime.protocol.EndpointKind.CLIENT)
                        .protocol(Protocol.HTTP).host("a").port(1).build())
                .target(Endpoint.builder().kind(com.chua.runtime.protocol.EndpointKind.SERVER)
                        .protocol(Protocol.HTTP).host("b").port(2).build())
                .protocol(Protocol.HTTP).software(Software.TOMCAT)
                .callCount(1L).totalDuration(200L)
                .build();
        storage.appendDependency(e1);
        storage.appendDependency(e2);

        List<DependencyEdge> edges = storage.queryDependencies(Query.all());
        assertEquals(1, edges.size());
        DependencyEdge merged = edges.get(0);
        assertEquals(2L, merged.getCallCount());
        assertEquals(300L, merged.getTotalDuration());
    }

    @Test
    void inmemoryStorageAppendLeakAndLog() {
        InMemoryStorage storage = new InMemoryStorage();
        storage.start(StorageConfig.defaults());

        LeakRecord leak = LeakRecord.builder()
                .handleId("h-1")
                .kind("java/io/FileInputStream")
                .name("/tmp/test.log")
                .thread("main")
                .createdAt(System.currentTimeMillis())
                .closedAt(0L)
                .stackTrace("at com.example.Foo.bar(Foo.java:42)")
                .build();
        storage.appendLeak(leak);

        LogRecord log = LogRecord.builder()
                .timestamp(System.currentTimeMillis())
                .level("INFO")
                .logger("com.example.Foo")
                .className("com.example.Foo")
                .methodName("bar")
                .message("hello world")
                .traceId("trace-xyz")
                .build();
        storage.appendLog(log);

        List<LeakRecord> leaks = storage.queryLeaks(Query.all());
        assertEquals(1, leaks.size());
        assertEquals("h-1", leaks.get(0).getHandleId());
        assertTrue(leaks.get(0).isActive());

        List<LogRecord> logs = storage.queryLogs(Query.all());
        assertEquals(1, logs.size());
        assertEquals("hello world", logs.get(0).getMessage());
        assertEquals("trace-xyz", logs.get(0).getTraceId());

        Map<String, Long> stats = storage.stats();
        assertEquals(1L, stats.get("leaks"));
        assertEquals(1L, stats.get("logs"));
    }

    @Test
    void storageManagerFallsBackToNoopWhenNoSpi() {
        // 测试：清空 REGISTERED 后只应有 NoopStorage
        StorageManager.shutdown();
        StorageManager.init(new StorageConfig());
        assertEquals("noop", StorageManager.get().name());
    }

    @Test
    void queryAppliesFilter() {
        InMemoryStorage storage = new InMemoryStorage();
        storage.start(StorageConfig.defaults());

        for (int i = 0; i < 5; i++) {
            TransmissionEvent e = TransmissionEvent.builder()
                    .traceId(i % 2 == 0 ? "even" : "odd")
                    .protocol("HTTP")
                    .startTime(1000L + i)
                    .build();
            storage.appendTransmission(e);
        }

        Query evenOnly = new Query().setTraceId("even");
        List<TransmissionEvent> result = storage.queryTransmissions(evenOnly);
        assertEquals(3, result.size());
        for (TransmissionEvent e : result) {
            assertEquals("even", e.getTraceId());
        }
    }

    @Test
    void cleanupRemovesOldEntries() {
        InMemoryStorage storage = new InMemoryStorage();
        storage.start(StorageConfig.defaults());
        long now = System.currentTimeMillis();

        for (int i = 0; i < 5; i++) {
            LogRecord log = LogRecord.builder()
                    .timestamp(now - 10000L + i)
                    .level("INFO")
                    .message("log " + i)
                    .build();
            storage.appendLog(log);
        }
        // 5 秒前之前的数据应该被清理
        long removed = storage.cleanup(5000L);
        assertTrue(removed >= 1);
    }
}