package com.chua.starter.datasync;

import com.chua.datasync.agent.support.DataSyncAgent;
import com.chua.datasync.agent.support.DataSyncAgentSink;
import com.chua.datasync.agent.support.DataSyncAgentSource;
import com.chua.starter.datasync.agent.AgentServerManager;
import com.chua.starter.datasync.mapping.DataSyncFieldMapping;
import com.chua.starter.datasync.mapping.DefaultDataSyncMapping;
import com.chua.starter.datasync.model.DataSyncMapping;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** @author CH */
class DataSyncServerTest {

    @Test
    void shouldStartAndStop() {
        DataSyncServer server = new DefaultDataSyncServer(new MockAgentManager());
        assertDoesNotThrow(server::start);
        assertDoesNotThrow(server::stop);
    }

    @Test
    void shouldRegisterAndGetSource() {
        DataSyncServer server = new DefaultDataSyncServer(new MockAgentManager());
        var source = new MockSource("src1");
        server.registerSource(source);
        assertSame(source, server.getSource("src1"));
        assertEquals(1, server.getSources().size());
    }

    @Test
    void shouldRegisterAndGetSink() {
        DataSyncServer server = new DefaultDataSyncServer(new MockAgentManager());
        var sink = new MockSink("sink1");
        server.registerSink(sink);
        assertSame(sink, server.getSink("sink1"));
        assertEquals(1, server.getSinks().size());
    }

    @Test
    void shouldHandleNullSourceId() {
        DataSyncServer server = new DefaultDataSyncServer(new MockAgentManager());
        server.registerSource(new MockSource(null));
        assertTrue(server.getSources().isEmpty());
    }

    @Test
    void shouldAddMapping() {
        DataSyncServer server = new DefaultDataSyncServer(new MockAgentManager());
        server.addMapping(createMapping("m1"));
        assertEquals(1, server.mappingManager().getMappings().size());
    }

    @Test
    void shouldDelegateComponents() {
        DataSyncServer server = new DefaultDataSyncServer(new MockAgentManager());
        assertNotNull(server.mappingManager());
        assertNotNull(server.schedulerManager());
        assertNotNull(server.agentServerManager());
        assertNotNull(server.executorManager());
    }

    /** 创建Mapping */
    private static DataSyncMapping createMapping(String id) {
        return new DefaultDataSyncMapping(id, "in1", "src1", "out1", "sink1",
                null, List.of(), 100, "", "", Map.of(), null);
    }

    /** MockSource */
    private record MockSource(String sourceId) implements DataSyncAgentSource {
        @Override public String inputId() { return sourceId; }
        @Override public Flux<Map<String, Object>> read(Map<String, Object> p) { return Flux.empty(); }
        @Override public void close() {}
    }

    /** MockSink */
    private record MockSink(String sinkId) implements DataSyncAgentSink {
        @Override public void write(Flux<Map<String, Object>> d) {}
        @Override public void close() {}
    }

    private static class MockAgentManager implements AgentServerManager {
        final List<DataSyncAgent> agents = new CopyOnWriteArrayList<>();
        @Override public void register(DataSyncAgent a) { agents.add(a); }
        @Override public void unregister(String id) { agents.removeIf(a -> a.agentId().equals(id)); }
        @Override public DataSyncAgent getAgent(String id) { return agents.stream().filter(a -> a.agentId().equals(id)).findFirst().orElse(null); }
        @Override public List<DataSyncAgent> getAgents() { return List.copyOf(agents); }
        @Override public void push(String id, String sid, List<Map<String, Object>> d) {}
    }
}

