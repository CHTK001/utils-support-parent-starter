package com.chua.runtime.e2e;

import com.chua.runtime.apm.ApmBootstrap;
import com.chua.runtime.apm.handler.HandleLeakHandler;
import com.chua.runtime.apm.handler.LogHandler;
import com.chua.runtime.apm.handler.NetHandler;
import com.chua.runtime.apm.storage.ApmStorage;
import com.chua.runtime.apm.storage.InMemoryStorage;
import com.chua.runtime.apm.storage.LeakRecord;
import com.chua.runtime.apm.storage.LogRecord;
import com.chua.runtime.apm.storage.NoopStorage;
import com.chua.runtime.apm.storage.Query;
import com.chua.runtime.apm.storage.StorageConfig;
import com.chua.runtime.apm.storage.StorageManager;
import com.chua.runtime.apm.storage.TransmissionEvent;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;
import com.chua.runtime.protocol.StatusCode;
import com.chua.runtime.protocol.TransmissionRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Storage SPI + HTTP 端到端集成测试。
 *
 * @author CH
 * @since 4.0.0.42
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {RuntimeTestConfiguration.class}
)
class StorageHttpIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ApmBootstrap apmBootstrap;

    private final RestTemplate restTemplate = new RestTemplate();

    @Test
    void storageSpiLoadsInMemoryByDefault() {
        // 默认 SPI 加载顺序：NoopStorage → InMemoryStorage
        ApmStorage storage = StorageManager.get();
        assertNotNull(storage);
        StorageManager.shutdown();
        StorageManager.init(new StorageConfig(Map.of(
                "apm.storage.type", "inmemory",
                "apm.storage.capacity", "5000",
                "apm.storage.retention.ms", "60000"
        )));
        ApmStorage active = StorageManager.get();
        assertEquals("inmemory", active.name());
        assertTrue(active instanceof InMemoryStorage);
    }

    @Test
    void storageFallsBackOnUnknownType() {
        StorageManager.shutdown();
        StorageManager.init(new StorageConfig(Map.of(
                "apm.storage.type", "does-not-exist"
        )));
        ApmStorage s = StorageManager.get();
        assertNotNull(s);
        // 兜底为 NoopStorage 或第一个 SPI（inmemory）
        assertTrue(s instanceof NoopStorage || "inmemory".equals(s.name()));
    }

    @Test
    void handlersWriteAcrossTypes() {
        StorageManager.shutdown();
        InMemoryStorage storage = new InMemoryStorage();
        StorageManager.register(storage);
        StorageManager.init(new StorageConfig(Map.of(
                "apm.storage.type", "inmemory",
                "apm.storage.capacity", "1000"
        )));

        // 1. appendTransmission（扁平 + 自动设 id）
        TransmissionRecord rec = new TransmissionRecord();
        rec.setTraceId("t-1");
        rec.setSpanId("s-1");
        rec.setProtocol(Protocol.HTTP);
        rec.setSoftware(Software.TOMCAT);
        rec.setSource(Endpoint.builder().kind(EndpointKind.CLIENT).protocol(Protocol.HTTP).software(Software.TOMCAT).host("127.0.0.1").port(12345).path("/").build());
        rec.setTarget(Endpoint.builder().kind(EndpointKind.SERVER).protocol(Protocol.HTTP).software(Software.TOMCAT).host("127.0.0.1").port(port).path("/api/x").build());
        rec.setStartTime(System.currentTimeMillis() - 100);
        rec.setEndTime(System.currentTimeMillis());
        rec.setDuration(100L);
        rec.setStatus(StatusCode.OK);
        StorageManager.appendTransmission(rec);
        assertEquals(1, storage.queryTransmissions(Query.all()).size());

        // 2. appendLeak（直接 entity）
        LeakRecord leak = LeakRecord.builder()
                .handleId("h-1")
                .kind("java/io/FileInputStream")
                .name("java/io/FileInputStream")
                .thread("main")
                .createdAt(System.currentTimeMillis() - 5000)
                .closedAt(0L)
                .stackTrace("at Foo.bar(Foo.java:1)")
                .build();
        StorageManager.get().appendLeak(leak);
        assertEquals(1, storage.queryLeaks(Query.all()).size());
        LeakRecord loaded = storage.queryLeaks(Query.all()).get(0);
        assertEquals("java/io/FileInputStream", loaded.getKind());
        assertTrue(loaded.isActive());

        // 3. appendLog
        LogRecord log = LogRecord.builder()
                .timestamp(System.currentTimeMillis())
                .level("INFO")
                .logger("com.example.X")
                .className("X")
                .methodName("y")
                .message("hello")
                .traceId("t-1")
                .build();
        StorageManager.get().appendLog(log);
        assertEquals(1, storage.queryLogs(Query.all()).size());
    }

    @Test
    void queryPaginationReturnsDifferentPages() {
        StorageManager.shutdown();
        StorageManager.init(new StorageConfig(Map.of(
                "apm.storage.type", "inmemory",
                "apm.storage.capacity", "100"
        )));
        long now = System.currentTimeMillis();
        for (int i = 0; i < 50; i++) {
            StorageManager.get().appendLog(LogRecord.builder()
                    .timestamp(now - i * 1000)
                    .level("INFO")
                    .message("msg-" + i)
                    .build());
        }
        Query q1 = new Query().setLimit(10).setOffset(0);
        Query q2 = new Query().setLimit(10).setOffset(40);
        assertEquals(10, StorageManager.get().queryLogs(q1).size());
        assertEquals(10, StorageManager.get().queryLogs(q2).size());
        assertNotEquals(
                StorageManager.get().queryLogs(q1).get(0).getMessage(),
                StorageManager.get().queryLogs(q2).get(0).getMessage());
    }

    @Test
    void queryFiltersByTraceId() {
        StorageManager.shutdown();
        StorageManager.init(new StorageConfig(Map.of("apm.storage.type", "inmemory")));
        StorageManager.get().appendTransmission(TransmissionEvent.builder().traceId("trace-A").protocol("HTTP").startTime(System.currentTimeMillis()).build());
        StorageManager.get().appendTransmission(TransmissionEvent.builder().traceId("trace-B").protocol("HTTP").startTime(System.currentTimeMillis()).build());
        List<TransmissionEvent> a = StorageManager.get().queryTransmissions(new Query().setTraceId("trace-A"));
        assertEquals(1, a.size());
        assertEquals("trace-A", a.get(0).getTraceId());
    }

    @Test
    void queryFiltersByTimeRange() {
        StorageManager.shutdown();
        StorageManager.init(new StorageConfig(Map.of("apm.storage.type", "inmemory")));
        long now = System.currentTimeMillis();
        StorageManager.get().appendTransmission(TransmissionEvent.builder().traceId("old").startTime(now - 10000).build());
        StorageManager.get().appendTransmission(TransmissionEvent.builder().traceId("recent").startTime(now - 1000).build());
        Query q = new Query().setStartTime(now - 5000L).setEndTime(now);
        List<TransmissionEvent> filtered = StorageManager.get().queryTransmissions(q);
        assertTrue(filtered.stream().anyMatch(e -> "recent".equals(e.getTraceId())));
    }

    @Test
    void transmissionEventFromRecordCopiesAllFields() {
        TransmissionRecord rec = new TransmissionRecord();
        rec.setTraceId("abc123");
        rec.setSpanId("def456");
        rec.setProtocol(Protocol.GRPC);
        rec.setSoftware(Software.GRPC);
        rec.setOperation("invoke");
        rec.setStartTime(100L);
        rec.setEndTime(200L);
        rec.setDuration(100L);
        rec.setStatus(StatusCode.ERROR);
        rec.setErrorType("RuntimeException");
        rec.setErrorMessage("boom");
        TransmissionEvent e = TransmissionEvent.fromRecord(rec);
        assertEquals("abc123", e.getTraceId());
        assertEquals(Protocol.GRPC, e.getProtocol());
        assertEquals(StatusCode.ERROR, e.getStatus());
        assertEquals("RuntimeException", e.getErrorType());
        assertEquals("boom", e.getErrorMessage());
        assertEquals(100L, e.getDuration());
    }

    @Test
    void httpEndpointIsReachable() {
        ResponseEntity<Map> resp = restTemplate.getForEntity(
                "http://localhost:" + port + "/test/echo", Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("echo", resp.getBody().get("status"));
    }

    @Test
    void handlersAreAccessibleAfterBootstrap() {
        assertNotNull(apmBootstrap);
        assertTrue(apmBootstrap.isStarted());
        assertNotNull(apmBootstrap.getHandler(LogHandler.class));
        assertNotNull(apmBootstrap.getHandler(NetHandler.class));
        // 通过 StorageManager 触发一次 dummy append
        StorageManager.shutdown();
        StorageManager.init(new StorageConfig(Map.of("apm.storage.type", "inmemory")));
        StorageManager.get().appendLog(LogRecord.builder()
                .timestamp(System.currentTimeMillis())
                .level("INFO")
                .message("test")
                .build());
        assertEquals(1, StorageManager.get().queryLogs(Query.all()).size());
    }

    @Test
    void concurrentHttpRequestsDoNotCrash() throws Exception {
        StorageManager.shutdown();
        StorageManager.init(new StorageConfig(Map.of("apm.storage.type", "inmemory")));

        int n = 20;
        AtomicInteger success = new AtomicInteger();
        CompletableFuture<?>[] futures = new CompletableFuture[n];
        for (int i = 0; i < n; i++) {
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    ResponseEntity<Map> r = restTemplate.getForEntity(
                            "http://localhost:" + port + "/test/echo", Map.class);
                    if (r.getStatusCode() == HttpStatus.OK) {
                        success.incrementAndGet();
                    }
                } catch (Exception ignored) {
                }
            });
        }
        CompletableFuture.allOf(futures).get();
        assertEquals(n, success.get());

        // 进程没崩、Handler 仍可访问
        assertNotNull(apmBootstrap.getHandler(NetHandler.class));
        assertNotNull(apmBootstrap.getHandler(LogHandler.class));
    }

    @Test
    void storageStatsReflectInsertions() {
        StorageManager.shutdown();
        InMemoryStorage storage = new InMemoryStorage();
        StorageManager.register(storage);
        StorageManager.init(new StorageConfig(Map.of("apm.storage.type", "inmemory")));

        for (int i = 0; i < 5; i++) {
            StorageManager.get().appendLog(LogRecord.builder()
                    .timestamp(System.currentTimeMillis())
                    .message("m" + i).build());
        }
        for (int i = 0; i < 3; i++) {
            StorageManager.get().appendLeak(LeakRecord.builder()
                    .handleId("h" + i).kind("X").name("X").thread("t").createdAt(System.currentTimeMillis()).build());
        }
        Map<String, Long> stats = StorageManager.get().stats();
        assertEquals(5L, stats.get("logs"));
        assertEquals(3L, stats.get("leaks"));
    }
}