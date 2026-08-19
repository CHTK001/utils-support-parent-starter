package com.chua.starter.datasync.scheduler;

import com.chua.datasync.agent.support.DataSyncAgentSink;
import com.chua.datasync.agent.support.DataSyncAgentSource;
import com.chua.common.support.task.scheduler.Trigger;
import com.chua.starter.datasync.DataSyncServer;
import com.chua.starter.datasync.DefaultDataSyncServer;
import com.chua.starter.datasync.agent.AgentServerManager;
import com.chua.starter.datasync.mapping.DefaultDataSyncMapping;
import com.chua.starter.datasync.model.DataSyncMapping;
import com.chua.datasync.agent.support.model.Direction;
import com.chua.datasync.agent.support.model.Directional;
import com.chua.starter.datasync.mapping.DefaultDataSyncMappingManager;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class DefaultSyncDataSchedulerManagerTest {

    @Test
    void shouldStartAndStop() {
        DataSyncServer server = createServer();
        SyncDataSchedulerManager scheduler = server.schedulerManager();
        assertDoesNotThrow(scheduler::start);
        assertDoesNotThrow(scheduler::stop);
    }

    @Test
    void shouldDelegateAddMapping() {
        DataSyncServer server = createServer();
        server.schedulerManager().addMapping(createMapping("m1"));
        assertEquals(1, server.mappingManager().getMappings().size());
    }

    @Test
    void shouldRemoveMapping() {
        DataSyncServer server = createServer();
        server.schedulerManager().addMapping(createMapping("m1"));
        server.schedulerManager().addMapping(createMapping("m2"));
        server.schedulerManager().removeMapping("m1");
        assertEquals(1, server.mappingManager().getMappings().size());
        assertEquals("m2", server.mappingManager().getMappings().get(0).mappingId());
    }

    @Test
    void shouldGetMapping() {
        DataSyncServer server = createServer();
        server.schedulerManager().addMapping(createMapping("m1"));
        assertNotNull(server.schedulerManager().getMapping("m1"));
        assertNull(server.schedulerManager().getMapping("nonexistent"));
    }

    @Test
    void triggerShouldOverrideCron() {
        AtomicBoolean fired = new AtomicBoolean(false);
        Trigger customTrigger = new Trigger() {
            @Override public LocalDateTime nextExecutionTime() { return null; }
            @Override public LocalDateTime nextExecutionTime(LocalDateTime from) {
                if (!fired.get()) { fired.set(true); return LocalDateTime.now(); }
                return null;
            }
            @Override public List<LocalDateTime> getFireTimes(int count) { return List.of(); }
            @Override public List<LocalDateTime> getFireTimes(int count, LocalDateTime from) { return List.of(); }
        };
        var mapping = new DefaultDataSyncMapping("t1", "in1", "src1", "out1",
                "sink1", null, List.of(), 100, "cron", "0 0 0 1 1 ? 2099",
                Map.of(), customTrigger);
        assertNotNull(mapping.trigger());
    }

    @Test
    void shouldSkipMappingWithEmptySourceId() {
        DataSyncServer server = createServer();
        var mapping = new DefaultDataSyncMapping("m1", "in1", "", "out1", "sink1",
                null, List.of(), 100, "", "", Map.of(), null);
        server.schedulerManager().addMapping(mapping);
    }

    @Test
    void shouldSkipMappingWithEmptySinkId() {
        DataSyncServer server = createServer();
        SourceImpl src = new SourceImpl("src1");
        server.registerSource(src);
        var mapping = new DefaultDataSyncMapping("m1", "in1", "src1", "out1", "",
                null, List.of(), 100, "", "", Map.of(), null);
        server.schedulerManager().addMapping(mapping);
    }

    private static DataSyncServer createServer() {
        return new DefaultDataSyncServer(new AgentServerManager() {
            @Override public void register(com.chua.datasync.agent.support.DataSyncAgent a) {}
            @Override public void unregister(String id) {}
            @Override public com.chua.datasync.agent.support.DataSyncAgent getAgent(String id) { return null; }
            @Override public List<com.chua.datasync.agent.support.DataSyncAgent> getAgents() { return List.of(); }
            @Override public void push(String id, String sid, List<Map<String, Object>> d) {}
        });
    }

    private static DataSyncMapping createMapping(String id) {
        return new DefaultDataSyncMapping(id, "in1", "src1", "out1", "sink1",
                null, List.of(), 100, "", "", Map.of(), null);
    }

    private record SourceImpl(String sourceId) implements DataSyncAgentSource {
        @Override public String inputId() { return sourceId; }
        @Override public Flux<Map<String, Object>> read(Map<String, Object> p) { return Flux.empty(); }
        @Override public void close() {}
    }

    /**
     * 始终触发的 Trigger，用于测试。
     */
    private static class AlwaysFireTrigger implements Trigger {
        @Override
        public LocalDateTime nextExecutionTime() {
            return null;
        }
        @Override
        public LocalDateTime nextExecutionTime(LocalDateTime from) {
            return from.minusSeconds(1);
        }
        @Override
        public List<LocalDateTime> getFireTimes(int count) {
            return List.of();
        }
        @Override
        public List<LocalDateTime> getFireTimes(int count, LocalDateTime from) {
            return List.of();
        }
    }

    /**
     * 模拟 Source，支持方向校验与 read 调用追踪。
     */
    private record MockAgentSource(
        String sourceId,
        Direction direction,
        CountDownLatch readLatch,
        List<Map<String, Object>> readCalls
    ) implements DataSyncAgentSource, Directional {
        @Override
        public String inputId() {
            return sourceId;
        }
        @Override
        public Flux<Map<String, Object>> read(Map<String, Object> params) {
            readCalls.add(params);
            readLatch.countDown();
            return Flux.just(Map.of("id", 1L, "name", "test"));
        }
        @Override
        public void close() {
        }
        @Override
        public Direction direction() {
            return direction;
        }
    }

    /**
     * 模拟 Sink，支持方向校验与 write 调用追踪。
     */
    private record MockAgentSink(
        String sinkId,
        CountDownLatch writeLatch,
        List<Map<String, Object>> receivedData,
        Direction direction
    ) implements DataSyncAgentSink, Directional {
        @Override
        public void write(Flux<Map<String, Object>> data) {
            data.collectList().subscribe(list -> {
                receivedData.addAll(list);
                writeLatch.countDown();
            });
        }
        @Override
        public void close() {
        }
        @Override
        public Direction direction() {
            return direction;
        }
    }

    /**
     * 模拟 Source，追踪 writeOffset 调用。
     */
    private record MockOffsetSource(
        String sourceId,
        CountDownLatch writeOffsetLatch,
        List<com.chua.datasync.agent.support.model.SyncDataOffset> offsets
    ) implements DataSyncAgentSource, Directional {
        @Override
        public String inputId() {
            return sourceId;
        }
        @Override
        public Flux<Map<String, Object>> read(Map<String, Object> params) {
            return Flux.just(Map.of("id", 1L, "name", "test"));
        }
        @Override
        public void close() {
        }
        @Override
        public Direction direction() {
            return Direction.INPUT;
        }
        @Override
        public void writeOffset(com.chua.datasync.agent.support.model.SyncDataOffset offset) {
            offsets.add(offset);
            writeOffsetLatch.countDown();
        }
    }

    // ==================== 新增核心单元测试 ====================

    @Test
    void concurrentAddMappingShouldNotLoseData() throws Exception {
        DefaultDataSyncMappingManager manager = new DefaultDataSyncMappingManager();
        int threadCount = 10;
        int opsPerThread = 100;
        int expectedTotal = threadCount * opsPerThread;
        CountDownLatch latch = new CountDownLatch(threadCount);
        for (int i = 0; i < threadCount; i++) {
            int threadId = i;
            new Thread(() -> {
                for (int j = 0; j < opsPerThread; j++) {
                    String mappingId = "mapping-" + threadId + "-" + j;
                    DataSyncMapping mapping = new DefaultDataSyncMapping(
                        mappingId, "in" + threadId, "src" + threadId,
                        "out" + threadId, "sink" + threadId, null, List.of(),
                        100, "", "", Map.of(), null
                    );
                    manager.addMapping(mapping);
                }
                latch.countDown();
            }).start();
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS), "所有线程应在10秒内完成");
        assertEquals(expectedTotal, manager.getMappings().size(), "并发添加映射不应丢失数据");
    }

    @Test
    void shouldExecuteMappingWithCorrectDirection() throws Exception {
        DataSyncServer server = createServer();
        CountDownLatch writeLatch = new CountDownLatch(1);
        List<Map<String, Object>> receivedData = new ArrayList<>();
        MockAgentSink sink = new MockAgentSink("sink1", writeLatch, receivedData, Direction.OUTPUT);
        server.registerSink(sink);
        CountDownLatch readLatch = new CountDownLatch(1);
        MockAgentSource source = new MockAgentSource("src1", Direction.INPUT, readLatch, new ArrayList<>());
        server.registerSource(source);
        Trigger alwaysFire = new AlwaysFireTrigger();
        DataSyncMapping mapping = new DefaultDataSyncMapping(
            "m1", "in1", "src1", "out1", "sink1",
            null, List.of(), 100, "", "", Map.of(), alwaysFire
        );
        server.schedulerManager().addMapping(mapping);
        server.start();
        boolean writeCalled = writeLatch.await(5, TimeUnit.SECONDS);
        server.stop();
        assertTrue(writeCalled, "Sink write 应被调用");
        assertEquals(1, receivedData.size(), "应收到一笔数据");
    }

    @Test
    void shouldSkipExecutionWhenSourceDirectionIsWrong() throws Exception {
        DataSyncServer server = createServer();
        CountDownLatch readLatch = new CountDownLatch(1);
        MockAgentSource wrongSource = new MockAgentSource("src1", Direction.OUTPUT, readLatch, new ArrayList<>());
        server.registerSource(wrongSource);
        CountDownLatch writeLatch = new CountDownLatch(1);
        MockAgentSink sink = new MockAgentSink("sink1", writeLatch, new ArrayList<>(), Direction.OUTPUT);
        server.registerSink(sink);
        Trigger alwaysFire = new AlwaysFireTrigger();
        DataSyncMapping mapping = new DefaultDataSyncMapping(
            "m1", "in1", "src1", "out1", "sink1",
            null, List.of(), 100, "", "", Map.of(), alwaysFire
        );
        server.schedulerManager().addMapping(mapping);
        server.start();
        boolean readCalled = readLatch.await(3, TimeUnit.SECONDS);
        server.stop();
        assertFalse(readCalled, "Source 方向错误时，read 不应被调用");
    }

    @Test
    void shouldSkipExecutionWhenSinkDirectionIsWrong() throws Exception {
        DataSyncServer server = createServer();
        CountDownLatch readLatch = new CountDownLatch(1);
        MockAgentSource source = new MockAgentSource("src1", Direction.INPUT, readLatch, new ArrayList<>());
        server.registerSource(source);
        CountDownLatch writeLatch = new CountDownLatch(1);
        MockAgentSink wrongSink = new MockAgentSink("sink1", writeLatch, new ArrayList<>(), Direction.INPUT);
        server.registerSink(wrongSink);
        Trigger alwaysFire = new AlwaysFireTrigger();
        DataSyncMapping mapping = new DefaultDataSyncMapping(
            "m1", "in1", "src1", "out1", "sink1",
            null, List.of(), 100, "", "", Map.of(), alwaysFire
        );
        server.schedulerManager().addMapping(mapping);
        server.start();
        boolean readCalled = readLatch.await(3, TimeUnit.SECONDS);
        server.stop();
        assertFalse(readCalled, "Sink 方向错误时，Source read 不应被调用");
    }

    @Test
    void shouldPersistOffsetAfterSuccessfulPublish() throws Exception {
        DataSyncServer server = createServer();
        CountDownLatch writeOffsetLatch = new CountDownLatch(1);
        List<com.chua.datasync.agent.support.model.SyncDataOffset> offsets = new ArrayList<>();
        MockOffsetSource source = new MockOffsetSource("src1", writeOffsetLatch, offsets);
        server.registerSource(source);
        CountDownLatch writeLatch = new CountDownLatch(1);
        MockAgentSink sink = new MockAgentSink("sink1", writeLatch, new ArrayList<>(), Direction.OUTPUT);
        server.registerSink(sink);
        Trigger alwaysFire = new AlwaysFireTrigger();
        DataSyncMapping mapping = new DefaultDataSyncMapping(
            "m1", "in1", "src1", "out1", "sink1",
            null, List.of(), 100, "", "", Map.of(), alwaysFire
        );
        server.schedulerManager().addMapping(mapping);
        server.start();
        boolean offsetPersisted = writeOffsetLatch.await(5, TimeUnit.SECONDS);
        server.stop();
        assertTrue(offsetPersisted, "Offset 应被持久化");
        assertEquals(1, offsets.size(), "应记录一次 offset");
        assertEquals("src1", offsets.get(0).sourceId());
        assertEquals("m1", offsets.get(0).mappingId());
    }
}

