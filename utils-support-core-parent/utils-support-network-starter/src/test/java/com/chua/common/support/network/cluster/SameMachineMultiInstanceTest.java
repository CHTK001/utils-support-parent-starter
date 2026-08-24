package com.chua.common.support.network.cluster;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.scatter.ScatterSyncHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 同机多实例集群验证：两个节点跑在同一台机器的不同端口上。
 */
class SameMachineMultiInstanceTest {

    private ClusterServer nodeA;
    private ClusterServer nodeB;

    @BeforeEach
    void setUp() throws Exception {
        ScatterSyncHelper.resetForTest();
        java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(".scatter-nodes-node-a.json"));
        java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(".scatter-nodes-node-b.json"));

        // 节点 B：种子网关
        nodeB = ClusterServer.builder()
                .nodeId("node-b")
                .host("127.0.0.1")
                .port(0)
                .scatterId("same-machine-test")
                .servicePaths(java.util.List.of("/svc"))
                .timeoutMillis(3000)
                .build();
        nodeB.start();

        // 节点 A：连接到 B 的 scatter 端口
        nodeA = ClusterServer.builder()
                .nodeId("node-a")
                .host("127.0.0.1")
                .port(0)
                .scatterId("same-machine-test")
                .seeds("127.0.0.1:" + nodeB.getScatterPort())
                .servicePaths(java.util.List.of("/svc"))
                .addServer("/svc", "127.0.0.1", 19003, "http")
                .build();
        nodeA.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (nodeA != null) { try { nodeA.close(); } catch (Exception ignored) {} }
        if (nodeB != null) { try { nodeB.close(); } catch (Exception ignored) {} }
        java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(".scatter-nodes-node-a.json"));
        java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(".scatter-nodes-node-b.json"));
    }

    @Test
    void sameMachine_twoInstances_discoverEachOther() throws Exception {
        // 轮询等待双向发现（最长 20 秒）
        boolean aSeesB = false;
        boolean bSeesA = false;
        for (int i = 0; i < 20; i++) {
            Set<Discovery> aView = nodeA.discovery().getServiceAll("/svc");
            Set<Discovery> bView = nodeB.discovery().getServiceAll("/svc");
            aSeesB = aView.stream().anyMatch(d -> "node-b".equals(d.getServerId()));
            bSeesA = bView.stream().anyMatch(d -> "node-a".equals(d.getServerId()));
            if (aSeesB && bSeesA) break;
            TimeUnit.MILLISECONDS.sleep(1000);
        }
        assertTrue(aSeesB, "A 应发现 B");
        assertTrue(bSeesA, "B 应发现 A");
    }

    private boolean aSeesA() { return true; }

    @Test
    void sameMachine_differentPorts_uniqueNodeIds() {
        assertNotEquals(nodeA.getHttpPort(), nodeB.getHttpPort(), "HTTP 端口不应相同");
        assertNotEquals(nodeA.getScatterPort(), nodeB.getScatterPort(), "Scatter 端口不应相同");
    }
}
