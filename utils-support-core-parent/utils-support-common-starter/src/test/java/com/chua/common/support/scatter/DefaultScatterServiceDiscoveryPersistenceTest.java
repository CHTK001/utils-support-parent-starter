package com.chua.common.support.scatter;

import com.chua.common.support.network.discovery.Discovery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DefaultScatterServiceDiscovery 节点持久化测试。
 * <p>验证：新鲜持久化节点启动时直接加载进 hash 表；过期节点被过滤丢弃。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultScatterServiceDiscoveryPersistenceTest {

    @TempDir
    Path tempDir;

    /**
     * 新鲜持久化节点应直接加载进 hash 表，无需重新检索。
     */
    @Test
    void shouldLoadFreshPersistedNodesOnStart() throws Exception {
        Path file = tempDir.resolve("nodes-fresh.json");
        Discovery remote = Discovery.builder()
                .serverId("192.168.1.20:19001")
                .scatterId("order")
                .protocol("tcp")
                .host("192.168.1.20")
                .port(19001)
                .metadata(Map.of("lastSeen", String.valueOf(System.currentTimeMillis())))
                .build();
        Files.writeString(file, com.chua.common.support.lang.json.Json.toJson(List.of(remote)));

        ScatterSetting setting = new ScatterSetting();
        setting.setNodeId("node-a").setGroupId("order").setPort(19001);
        setting.setPersistenceEnabled(true).setPersistenceFile(file.toString());

        try (DefaultScatterServiceDiscovery discovery = new DefaultScatterServiceDiscovery(setting)) {
            discovery.start();
            boolean found = discovery.getServiceAll("/scatter").stream()
                    .anyMatch(d -> "192.168.1.20:19001".equals(d.getServerId()));
            assertTrue(found, "新鲜持久化节点应直接加载进 hash 表");
        }
    }

    /**
     * 超过 TTL 的过期持久化节点应被过滤丢弃。
     */
    @Test
    void shouldDropExpiredPersistedNodes() throws Exception {
        Path file = tempDir.resolve("nodes-expired.json");
        Discovery expired = Discovery.builder()
                .serverId("192.168.1.21:19001")
                .scatterId("order")
                .protocol("tcp")
                .host("192.168.1.21")
                .port(19001)
                .metadata(Map.of("lastSeen", String.valueOf(System.currentTimeMillis() - 10 * 60_000L)))
                .build();
        Files.writeString(file, com.chua.common.support.lang.json.Json.toJson(List.of(expired)));

        ScatterSetting setting = new ScatterSetting();
        setting.setNodeId("node-b").setGroupId("order").setPort(19001);
        setting.setPersistenceEnabled(true).setPersistenceFile(file.toString());
        setting.setPersistenceTtlMillis(60_000L);

        try (DefaultScatterServiceDiscovery discovery = new DefaultScatterServiceDiscovery(setting)) {
            discovery.start();
            boolean found = discovery.getServiceAll("/scatter").stream()
                    .anyMatch(d -> "192.168.1.21:19001".equals(d.getServerId()));
            assertFalse(found, "过期持久化节点应被丢弃");
        }
    }
}
