package com.chua.starter.datasync.agent;

import com.chua.datasync.agent.support.DataSyncAgent;
import com.chua.datasync.agent.support.DataSyncAgentSink;
import com.chua.datasync.agent.support.DataSyncAgentSource;
import com.chua.starter.datasync.DataSyncServer;
import com.chua.starter.datasync.DefaultDataSyncServer;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** @author CH */
class DataSyncAgentServerTest {

    @Test
    void shouldRegisterAndUnregisterAgent() {
        DataSyncServer server = createServer();
        DataSyncAgentServer agentServer = new DataSyncAgentServer(server, new MockSyncServer(), "srv1");
        DataSyncAgent agent = new MockAgent("agent1");
        agentServer.register(agent);
        assertNotNull(agentServer.getAgent("agent1"));
        assertEquals(1, agentServer.getAgents().size());
        agentServer.unregister("agent1");
        assertNull(agentServer.getAgent("agent1"));
    }

    @Test
    void shouldPushToLocalAgent() {
        DataSyncServer server = createServer();
        DataSyncAgentServer agentServer = new DataSyncAgentServer(server, new MockSyncServer(), "srv1");
        AtomicBoolean received = new AtomicBoolean(false);
        DataSyncAgent agent = new DataSyncAgent() {
            @Override public String agentId() { return "local1"; }
            @Override public void onDataReceived(List<Map<String, Object>> data) { received.set(true); }
            @Override public void start() {}
            @Override public void stop() {}
        };
        agentServer.register(agent);
        agentServer.push("local1", "sink1", List.of(Map.of("k", "v")));
        assertTrue(received.get());
    }

    @Test
    void shouldRegisterAgentResourcesOnServer() {
        DataSyncServer server = createServer();
        DataSyncAgentServer agentServer = new DataSyncAgentServer(server, new MockSyncServer(), "srv1");
        agentServer.register(new MockAgent("agent1"));
        assertNotNull(server.getSource("src1"));
        assertNotNull(server.getSink("sink1"));
    }

    @Test
    void startStopShouldNotThrow() {
        DataSyncServer server = createServer();
        DataSyncAgentServer agentServer = new DataSyncAgentServer(server, new MockSyncServer(), "srv1");
        assertDoesNotThrow(agentServer::start);
        assertDoesNotThrow(agentServer::stop);
    }

    private static DataSyncServer createServer() {
        return new DefaultDataSyncServer(new AgentServerManager() {
            /** Agents */
            private final List<DataSyncAgent> agents = new CopyOnWriteArrayList<>();
            @Override public void register(DataSyncAgent a) { agents.add(a); }
            @Override public void unregister(String id) { agents.removeIf(a -> a.agentId().equals(id)); }
            @Override public DataSyncAgent getAgent(String id) { return agents.stream().filter(a -> a.agentId().equals(id)).findFirst().orElse(null); }
            @Override public List<DataSyncAgent> getAgents() { return List.copyOf(agents); }
            @Override public void push(String id, String sid, List<Map<String, Object>> d) {}
        });
    }

    private static class MockAgent implements DataSyncAgent {
        /** 标识 */
        /** ID */
        private final String id;
        MockAgent(String id) { this.id = id; }
        @Override public String agentId() { return id; }
        @Override public void start() {}
        @Override public void stop() {}
        @Override public List<DataSyncAgentSource> sources() {
            return List.of(new DataSyncAgentSource() {
                @Override public String sourceId() { return "src1"; }
                @Override public String inputId() { return "in1"; }
                @Override public Flux<Map<String, Object>> read(Map<String, Object> p) { return Flux.empty(); }
                @Override public void close() {}
            });
        }
        @Override public List<DataSyncAgentSink> sinks() {
            return List.of(new DataSyncAgentSink() {
                @Override public String sinkId() { return "sink1"; }
                @Override public void write(Flux<Map<String, Object>> d) {}
                @Override public void close() {}
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static class MockSyncServer implements com.chua.common.support.network.server.SyncServer {
        @Override public void publish(String topic, Object msg) {}
        @Override public void send(String c, String t, Object m) {}
        @Override public List<String> getConnectedClients() { return List.of(); }
        @Override public Map<String, Object> getClientMetadata(String c) { return Map.of(); }
        @Override public void addListener(com.chua.common.support.network.server.SyncServerListener l) {}
        @Override public void removeListener(com.chua.common.support.network.server.SyncServerListener l) {}
        @Override public void start() {}
        @Override public void stop() {}
        @Override public boolean isRunning() { return false; }
        @Override public com.chua.common.support.network.ProtocolType getProtocolType() { return null; }
        @Override public int getPort() { return 0; }
        @Override public com.chua.common.support.network.server.ServerSetting getSetting() { return null; }
        @Override public com.chua.common.support.objects.ObjectContext getObjectContext() { return null; }
        @Override public void setObjectContext(com.chua.common.support.objects.ObjectContext ctx) {}
        @Override public List<com.chua.common.support.network.server.filter.ServerFilter> getFilters() { return List.of(); }
        @Override public com.chua.common.support.network.server.Server addFilter(com.chua.common.support.network.server.filter.ServerFilter f) { return this; }
        @Override public com.chua.common.support.network.server.Server removeFilter(com.chua.common.support.network.server.filter.ServerFilter f) { return this; }
        @Override public com.chua.common.support.network.server.Server refreshFilters() { return this; }
        @Override public com.chua.common.support.network.server.Server registerBean(Object b) { return this; }
        @Override public com.chua.common.support.network.server.Server unregisterBean(Object b) { return this; }
        @Override public void close() {}
    }
}


