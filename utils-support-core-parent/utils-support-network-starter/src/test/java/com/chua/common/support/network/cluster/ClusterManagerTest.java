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
 * ClusterManager addServer 测试。
 * <p>验证：addServer 注册的服务器进入集群（按 groupId 分组），可被集群视图查询。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class ClusterManagerTest {

    /**
     * addServer 注册的服务器应进入集群视图，并携带正确的分组与协议。
     */
    @Test
    void addServerShouldRegisterIntoClusterGroup() throws Exception {
        ScatterSetting setting = new ScatterSetting();
        setting.setNodeId("node-test").setGroupId("order").setPort(19001);
        setting.setPersistenceEnabled(false);

        try (DefaultScatterServiceDiscovery discovery = new DefaultScatterServiceDiscovery(setting)) {
            discovery.start();
            ClusterManager manager = new ClusterManager(discovery, "weight", "node-test");

            manager.addServer("/api", "192.168.1.10", 8080, "http");

            Set<Discovery> nodes = manager.nodes("/api");
            Discovery added = nodes.stream()
                    .filter(d -> "192.168.1.10:8080".equals(d.getServerId()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(added, "addServer 后应能在集群视图查到该服务器");
            assertEquals("order", added.getScatterId());
            assertEquals("http", added.getProtocol());
            assertEquals(8080, added.getPort());
        }
    }

    /**
     * 批量 addServers 注册多台服务器。
     */
    @Test
    void addServersShouldRegisterMultipleServers() throws Exception {
        ScatterSetting setting = new ScatterSetting();
        setting.setNodeId("node-test-2").setGroupId("pay").setPort(19002);
        setting.setPersistenceEnabled(false);

        try (DefaultScatterServiceDiscovery discovery = new DefaultScatterServiceDiscovery(setting)) {
            discovery.start();
            ClusterManager manager = new ClusterManager(discovery, "weight", "node-test-2");

            manager.addServers("/pay", "tcp", "192.168.1.30:9001", "192.168.1.31:9001");

            Set<Discovery> nodes = manager.nodes("/pay");
            assertTrue(nodes.stream().anyMatch(d -> "192.168.1.30:9001".equals(d.getServerId())),
                    "应注册第一台服务器");
            assertTrue(nodes.stream().anyMatch(d -> "192.168.1.31:9001".equals(d.getServerId())),
                    "应注册第二台服务器");
        }
    }
}
