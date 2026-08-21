package com.chua.common.support.network.cluster;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.scatter.DefaultScatterServiceDiscovery;
import com.chua.common.support.scatter.ScatterSetting;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ClusterManager addServer 测试（新 API：ServerEntry）。
 *
 * @author CH
 * @since 4.0.0.42
 */
class ClusterManagerTest {

    @Test
    void addServerShouldRegisterIntoClusterGroup() throws Exception {
        ScatterSetting setting = new ScatterSetting();
        setting.setNodeId("node-test");
        setting.setGroupId("order");
        setting.setPort(19001);
        setting.setPersistenceEnabled(false);

        try (DefaultScatterServiceDiscovery discovery = new DefaultScatterServiceDiscovery(setting)) {
            discovery.start();
            ClusterManager manager = new ClusterManager(discovery, "weight", "node-test");

            manager.addServer(ServerEntry.http("/api", "192.168.1.10", 8080));

            Set<Discovery> nodes = manager.nodes("/api");
            Discovery added = nodes.stream()
                    .filter(d -> "192.168.1.10:8080".equals(d.getServerId()))
                    .findFirst().orElse(null);
            assertNotNull(added, "addServer 后应能在集群视图查到该服务器");
            assertEquals("order", added.getScatterId());
            assertEquals("http", added.getProtocol());
            assertEquals(8080, added.getPort());
        }
    }

    @Test
    void addServersShouldRegisterMultipleServers() throws Exception {
        ScatterSetting setting = new ScatterSetting();
        setting.setNodeId("node-test-2");
        setting.setGroupId("pay");
        setting.setPort(19002);
        setting.setPersistenceEnabled(false);

        try (DefaultScatterServiceDiscovery discovery = new DefaultScatterServiceDiscovery(setting)) {
            discovery.start();
            ClusterManager manager = new ClusterManager(discovery, "weight", "node-test-2");

            manager.addServers(java.util.List.of(
                    ServerEntry.tcp("/pay", "192.168.1.30", 9001),
                    ServerEntry.tcp("/pay", "192.168.1.31", 9001)
            ));

            Set<Discovery> nodes = manager.nodes("/pay");
            assertTrue(nodes.stream().anyMatch(d -> "192.168.1.30:9001".equals(d.getServerId())),
                    "应注册第一台服务器");
            assertTrue(nodes.stream().anyMatch(d -> "192.168.1.31:9001".equals(d.getServerId())),
                    "应注册第二台服务器");
        }
    }

    @Test
    void addServerSamePathDifferentProtocolShouldAllow() throws Exception {
        ScatterSetting setting = new ScatterSetting();
        setting.setNodeId("node-mix");
        setting.setGroupId("mix");
        setting.setPort(19003);
        setting.setPersistenceEnabled(false);

        try (DefaultScatterServiceDiscovery discovery = new DefaultScatterServiceDiscovery(setting)) {
            discovery.start();
            ClusterManager manager = new ClusterManager(discovery, "weight", "node-mix");

            manager.addServer(ServerEntry.http("/api", "192.168.1.10", 8080));
            manager.addServer(ServerEntry.tcp("/api", "192.168.1.20", 8080));

            Set<Discovery> nodes = manager.nodes("/api");
            assertTrue(nodes.stream().anyMatch(d -> "192.168.1.10:8080".equals(d.getServerId())),
                    "http 协议节点应存在");
            assertTrue(nodes.stream().anyMatch(d -> "192.168.1.20:8080".equals(d.getServerId())),
                    "tcp 协议节点应存在");
        }
    }
}
