package com.chua.common.support.scattergather;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.ServiceDiscovery;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ScatterGatherDiscoverySupport 测试。
 *
 * @author CH
 */
class ScatterGatherDiscoverySupportTest {

    @Test
    void testConstructor() {
        ScatterGatherSetting setting = new ScatterGatherSetting()
                .setNodeId("test-node")
                .setHost("127.0.0.1")
                .setTcpPort(19001);
        ServiceDiscovery mockDiscovery = new MockServiceDiscovery();
        ScatterGatherDiscoverySupport support = new ScatterGatherDiscoverySupport(mockDiscovery, setting);
        assertNotNull(support);
        support.close();
    }

    @Test
    void testRegisterTcpNode() {
        ScatterGatherSetting setting = new ScatterGatherSetting()
                .setNodeId("node1")
                .setHost("192.168.1.10")
                .setTcpPort(19001);
        MockServiceDiscovery mockDiscovery = new MockServiceDiscovery();
        ScatterGatherDiscoverySupport support = new ScatterGatherDiscoverySupport(mockDiscovery, setting);
        support.registerTcpNode();
        Set<Discovery> services = mockDiscovery.getServiceAll("/scatter-gather");
        assertNotNull(services);
        assertTrue(services.stream().anyMatch(d -> "node1".equals(d.getId())));
        support.close();
    }

    @Test
    void testRegisterHttpApiDisabled() {
        ScatterGatherSetting setting = new ScatterGatherSetting()
                .setHttpApiEnabled(false)
                .setHttpPort(8080);
        MockServiceDiscovery mockDiscovery = new MockServiceDiscovery();
        ScatterGatherDiscoverySupport support = new ScatterGatherDiscoverySupport(mockDiscovery, setting);
        support.registerHttpApi();
        // HTTP API 未启用，不应注册
        Set<Discovery> services = mockDiscovery.getServiceAll("/scatter-gather/query");
        assertTrue(services == null || services.isEmpty());
        support.close();
    }

    @Test
    void testRegisterHttpApiEnabled() {
        ScatterGatherSetting setting = new ScatterGatherSetting()
                .setNodeId("node-http")
                .setHost("10.0.0.1")
                .setHttpPort(8080)
                .setHttpApiEnabled(true);
        MockServiceDiscovery mockDiscovery = new MockServiceDiscovery();
        ScatterGatherDiscoverySupport support = new ScatterGatherDiscoverySupport(mockDiscovery, setting);
        support.registerHttpApi();
        Set<Discovery> services = mockDiscovery.getServiceAll("/scatter-gather/query");
        assertNotNull(services);
        assertTrue(services.stream().anyMatch(d -> "http".equalsIgnoreCase(d.getProtocol())));
        support.close();
    }

    @Test
    void testHeartbeat() throws InterruptedException {
        ScatterGatherSetting setting = new ScatterGatherSetting()
                .setNodeId("hb-node")
                .setHost("10.0.0.1")
                .setTcpPort(19001)
                .setHeartbeatEnabled(true)
                .setHeartbeatIntervalMillis(50); // 短间隔加速测试
        MockServiceDiscovery mockDiscovery = new MockServiceDiscovery();
        ScatterGatherDiscoverySupport support = new ScatterGatherDiscoverySupport(mockDiscovery, setting);
        support.startHeartbeat();
        // 等待一次心跳发送
        Thread.sleep(100);
        assertNotNull(mockDiscovery.getServiceAll("/scatter-gather"));
        support.stopHeartbeat();
        support.close();
    }

    @Test
    void testFaultTolerance() {
        ScatterGatherSetting setting = new ScatterGatherSetting()
                .setFailureThreshold(3)
                .setRecoveryThreshold(1);
        MockServiceDiscovery mockDiscovery = new MockServiceDiscovery();
        ScatterGatherDiscoverySupport support = new ScatterGatherDiscoverySupport(mockDiscovery, setting);
        ScatterGatherFaultTolerance ft = support.getFaultTolerance("node-a");
        assertNotNull(ft);
        ft.recordFailure("node-a");
        assertEquals(1, ft.getFailureCount("node-a"));
        support.close();
    }

    @Test
    void testPeers() {
        ScatterGatherSetting setting = new ScatterGatherSetting()
                .setNodeId("self")
                .setHost("127.0.0.1")
                .setTcpPort(19001);
        MockServiceDiscovery mockDiscovery = new MockServiceDiscovery();
        // 注册自身
        mockDiscovery.registerService("/scatter-gather", Discovery.builder()
                .id("self").serverId("self").protocol("tcp")
                .host("127.0.0.1").port(19001).build());
        // 注册一个对端
        mockDiscovery.registerService("/scatter-gather", Discovery.builder()
                .id("peer-1").serverId("peer-1").protocol("tcp")
                .host("10.0.0.2").port(19002).build());
        ScatterGatherDiscoverySupport support = new ScatterGatherDiscoverySupport(mockDiscovery, setting);
        assertEquals(1, support.peers().size());
        assertEquals("peer-1", support.peers().get(0).getNodeId());
        support.close();
    }

    @Test
    void testClose() {
        ScatterGatherSetting setting = new ScatterGatherSetting();
        ScatterGatherDiscoverySupport support = new ScatterGatherDiscoverySupport(new MockServiceDiscovery(), setting);
        // close 不应抛异常
        support.close();
    }

    /**
     * 内存中的 Mock ServiceDiscovery 实现。
     */
    private static class MockServiceDiscovery implements ServiceDiscovery {
        private final java.util.concurrent.ConcurrentHashMap<String, java.util.List<Discovery>> store = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void start() throws Exception {
        }

        @Override
        public ServiceDiscovery registerService(String path, Discovery discovery) {
            store.computeIfAbsent(path, k -> new java.util.LinkedList<>()).add(discovery);
            return this;
        }

        @Override
        public ServiceDiscovery unregisterService(String path, Discovery discovery) {
            store.computeIfPresent(path, (k, list) -> {
                list.removeIf(d -> d.getServerId() != null && d.getServerId().equals(discovery.getServerId()));
                return list.isEmpty() ? null : list;
            });
            return this;
        }

        @Override
        public ServiceDiscovery unregisterService(String path, String serverId) {
            store.computeIfPresent(path, (k, list) -> {
                list.removeIf(d -> d.getServerId() != null && d.getServerId().equals(serverId));
                return list.isEmpty() ? null : list;
            });
            return this;
        }

        @Override
        public ServiceDiscovery updateService(String path, Discovery discovery) {
            return this;
        }

        @Override
        public Discovery getService(String path, String balance, String protocol) {
            java.util.List<Discovery> list = store.get(path);
            if (list == null || list.isEmpty()) return null;
            return list.get(0);
        }

        @Override
        public Set<Discovery> getServiceAll(String path) {
            java.util.List<Discovery> list = store.get(path);
            if (list == null || list.isEmpty()) return null;
            return Set.copyOf(list);
        }

        @Override
        public boolean isSupportSubscribe() {
            return false;
        }

        @Override
        public void subscribe(String serviceName, com.chua.common.support.network.discovery.ServiceDiscoveryListener listener) {
        }

        @Override
        public void close() {
            store.clear();
        }
    }
}