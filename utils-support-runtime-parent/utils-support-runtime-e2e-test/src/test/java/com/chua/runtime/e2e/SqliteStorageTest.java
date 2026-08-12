package com.chua.runtime.e2e;

import com.chua.runtime.apm.storage.LeakRecord;
import com.chua.runtime.apm.storage.LogRecord;
import com.chua.runtime.apm.storage.Query;
import com.chua.runtime.apm.storage.SqliteStorage;
import com.chua.runtime.apm.storage.StorageConfig;
import com.chua.runtime.apm.storage.TransmissionEvent;
import com.chua.runtime.protocol.DependencyEdge;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;
import com.chua.runtime.protocol.StatusCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQLite 存储单元测试 — 验证落盘、查询、聚合、清理。
 *
 * @author CH
 * @since 4.0.0.42
 */
class SqliteStorageTest {

    /**
     * 临时目录（JUnit 自动清理）
     */
    @TempDir
    Path tempDir;

    /**
     * 被测存储实例
     */
    private SqliteStorage storage;

    /**
     * 数据库文件路径
     */
    private String dbPath;

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("apm-test.db").toString();
        storage = new SqliteStorage();
        storage.start(new StorageConfig()
                .put("apm.storage.path", dbPath)
                .put("apm.storage.retention.ms", "60000")
                .put("apm.storage.capacity", "1000"));
    }

    @AfterEach
    void tearDown() {
        storage.stop();
    }

    @Test
    void sqliteStorageRoundTripsTransmission() {
        TransmissionEvent e = TransmissionEvent.builder()
                .traceId("trace-1")
                .spanId("span-1")
                .protocol("HTTP")
                .software("TOMCAT")
                .operation("GET /api/x")
                .status(StatusCode.OK)
                .statusCode(200)
                .startTime(1000L)
                .endTime(1010L)
                .duration(10L)
                .sourceProtocol("HTTP")
                .sourceHost("127.0.0.1")
                .sourcePort(8080)
                .targetProtocol("HTTP")
                .targetHost("localhost")
                .targetPort(9090)
                .build();
        storage.appendTransmission(e);

        List<TransmissionEvent> result = storage.queryTransmissions(Query.all());
        assertEquals(1, result.size());
        TransmissionEvent got = result.get(0);
        assertEquals("trace-1", got.getTraceId());
        assertEquals("HTTP", got.getProtocol());
        assertEquals("TOMCAT", got.getSoftware());
        assertEquals(8080, got.getSourcePort());
        assertEquals(9090, got.getTargetPort());
        assertEquals(StatusCode.OK, got.getStatus());
    }

    @Test
    void sqliteStorageAccumulatesDependencyEdges() {
        DependencyEdge e1 = DependencyEdge.builder()
                .source(Endpoint.builder().kind(EndpointKind.CLIENT)
                        .protocol(Protocol.HTTP).host("a").port(1).build())
                .target(Endpoint.builder().kind(EndpointKind.SERVER)
                        .protocol(Protocol.HTTP).host("b").port(2).build())
                .protocol(Protocol.HTTP).software(Software.TOMCAT)
                .callCount(1L).totalDuration(100L)
                .build();
        DependencyEdge e2 = DependencyEdge.builder()
                .source(Endpoint.builder().kind(EndpointKind.CLIENT)
                        .protocol(Protocol.HTTP).host("a").port(1).build())
                .target(Endpoint.builder().kind(EndpointKind.SERVER)
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
        assertEquals("a", merged.getSource().getHost());
        assertEquals("b", merged.getTarget().getHost());
    }

    @Test
    void sqliteStorageAppendLeakAndLog() {
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
    void sqliteStorageQueryAppliesFilter() {
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
    void sqliteStorageCleanupRemovesOldEntries() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            LogRecord log = LogRecord.builder()
                    .timestamp(now - 10000L + i)
                    .level("INFO")
                    .message("log " + i)
                    .build();
            storage.appendLog(log);
        }
        long removed = storage.cleanup(5000L);
        assertTrue(removed >= 1);
    }

    @Test
    void sqliteStoragePersistsAcrossRestart() {
        TransmissionEvent e = TransmissionEvent.builder()
                .traceId("persist-1")
                .protocol("HTTP")
                .startTime(1000L)
                .build();
        storage.appendTransmission(e);
        storage.stop();

        SqliteStorage reopened = new SqliteStorage();
        reopened.start(new StorageConfig().put("apm.storage.path", dbPath));
        try {
            List<TransmissionEvent> result = reopened.queryTransmissions(Query.all());
            assertEquals(1, result.size());
            assertEquals("persist-1", result.get(0).getTraceId());
        } finally {
            reopened.stop();
        }
    }
}
