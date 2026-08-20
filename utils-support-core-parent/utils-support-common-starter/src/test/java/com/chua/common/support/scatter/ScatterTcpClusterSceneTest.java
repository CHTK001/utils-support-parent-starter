package com.chua.common.support.scatter;

import com.chua.common.support.network.discovery.Discovery;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * scatter TCP 双节点集群场景测试（新 API：帧协议短连接 + seed 引导）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ScatterTcpClusterSceneTest {

    @Test
    public void testTcpSeedDiscovery() throws Exception {
        int portA = freePort();
        int portB = freePort();

        Scatter nodeA = new TcpScatterBuilder()
                .nodeId("node-a").host("127.0.0.1").port(portA)
                .groupId("order").servicePath("/scatter")
                .autoDiscoveryInterval(200).heartbeatInterval(500).failRemoveCount(3)
                .persistenceEnabled(false)
                .build();
        nodeA.start();

        Scatter nodeB = new TcpScatterBuilder()
                .nodeId("node-b").host("127.0.0.1").port(portB)
                .groupId("order").servicePath("/scatter")
                .seeds(List.of("127.0.0.1:" + portA))
                .autoDiscoveryInterval(200).heartbeatInterval(500).failRemoveCount(3)
                .persistenceEnabled(false)
                .build();
        nodeB.start();

        try {
            // 等 gossip 周期拉取
            TimeUnit.SECONDS.sleep(2);
            Set<Discovery> services = nodeB.discovery().getServiceAll("/scatter");
            boolean foundA = services.stream().anyMatch(d -> "node-a".equals(d.getServerId()));
            boolean foundSelf = services.stream().anyMatch(d -> "node-b".equals(d.getServerId()));
            Assertions.assertTrue(foundA, "node-b 应发现 node-a(seed 同步)");
            Assertions.assertTrue(foundSelf, "node-b 应含自身");
        } finally {
            nodeB.stop();
            nodeA.stop();
        }
    }

    @Test
    public void testTcpHeartbeatRemove() throws Exception {
        int portA = freePort();
        int portB = freePort();

        Scatter nodeA = new TcpScatterBuilder()
                .nodeId("node-a").host("127.0.0.1").port(portA)
                .groupId("order").servicePath("/scatter")
                .autoDiscoveryInterval(200).heartbeatInterval(500).failRemoveCount(2)
                .persistenceEnabled(false)
                .build();
        nodeA.start();

        Scatter nodeB = new TcpScatterBuilder()
                .nodeId("node-b").host("127.0.0.1").port(portB)
                .groupId("order").servicePath("/scatter")
                .seeds(List.of("127.0.0.1:" + portA))
                .autoDiscoveryInterval(200).heartbeatInterval(500).failRemoveCount(2)
                .persistenceEnabled(false)
                .build();
        nodeB.start();

        try {
            TimeUnit.SECONDS.sleep(2);
            boolean foundBefore = nodeB.discovery().getServiceAll("/scatter").stream()
                    .anyMatch(d -> "node-a".equals(d.getServerId()));
            Assertions.assertTrue(foundBefore, "掉线前应发现 node-a");

            // node-a 掉线：心跳 500ms × 失败 2 次 ≈ 1-2s 剔除
            nodeA.stop();
            TimeUnit.SECONDS.sleep(4);
            boolean foundAfter = nodeB.discovery().getServiceAll("/scatter").stream()
                    .anyMatch(d -> "node-a".equals(d.getServerId()));
            Assertions.assertFalse(foundAfter, "node-a 掉线后应从 node-b 剔除");
        } finally {
            nodeB.stop();
            nodeA.stop();
        }
    }

    private int freePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
