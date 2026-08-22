package com.chua.common.support.scatter;

import com.chua.common.support.network.discovery.Discovery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * scatter TCP 双节点集群场景测试：HTTP API 帧协议透传 + seed 服务发现
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ScatterTcpClusterSceneTest {

    @BeforeAll
    static void setUp() {
        ScatterSyncHelper.resetForTest();
    }

    @AfterEach
    void tearDown() {
        ScatterSyncHelper.resetForTest();
    }

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
        // 等待首轮 discoveryRound 完成（registerSelf + 可能触发的首次同步），确保 nodeA 的服务表已写入
        Thread.sleep(400);

        // seed 使用 nodeA 的实际 scatter 通信端口（port+2），不是 HTTP 业务端口
        int scatterPortA = nodeA.getPort();
        Scatter nodeB = new TcpScatterBuilder()
                .nodeId("node-b").host("127.0.0.1").port(portB)
                .groupId("order").servicePath("/scatter")
                .seeds(List.of("127.0.0.1:" + scatterPortA))
                .autoDiscoveryInterval(200).heartbeatInterval(500).failRemoveCount(3)
                .persistenceEnabled(false)
                .build();
        nodeB.start();

        try {
            // 等待多轮 discoveryRound 完成（seed sync + pushSelf 双向同步）
            TimeUnit.SECONDS.sleep(3);
            boolean foundA = waitForTrue(() -> {
                Set<Discovery> services = nodeB.discovery().getServiceAll("/scatter");
                return services.stream().anyMatch(d -> "node-a".equals(d.getServerId()));
            }, "node-b 应发现 node-a(seed 同步)");
            Assertions.assertTrue(foundA);
            boolean foundSelf = nodeB.discovery().getServiceAll("/scatter").stream()
                    .anyMatch(d -> "node-b".equals(d.getServerId()));
            Assertions.assertTrue(foundSelf, "node-b 应知道自己");
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

        // seed 使用 nodeA 的实际 scatter 通信端口（port+2），不是 HTTP 业务端口
        int scatterPortA = nodeA.getPort();
        Scatter nodeB = new TcpScatterBuilder()
                .nodeId("node-b").host("127.0.0.1").port(portB)
                .groupId("order").servicePath("/scatter")
                .seeds(List.of("127.0.0.1:" + scatterPortA))
                .autoDiscoveryInterval(200).heartbeatInterval(500).failRemoveCount(2)
                .persistenceEnabled(false)
                .build();
        nodeB.start();

        try {
            TimeUnit.SECONDS.sleep(3);
            boolean foundBefore = waitForTrue(() ->
                    nodeB.discovery().getServiceAll("/scatter").stream()
                            .anyMatch(d -> "node-a".equals(d.getServerId())),
                    "同步前 node-a 应存在");
            Assertions.assertTrue(foundBefore);

            // node-a 下线，等待 healthCheck 连续失败后移除（failRemoveCount=2, interval=500ms → 约 1-2s）
            nodeA.stop();
            TimeUnit.SECONDS.sleep(3);
            // 断言：node-a 下线后 node-b 服务表中不应再有 node-a
            Assertions.assertFalse(nodeB.discovery().getServiceAll("/scatter").stream()
                    .anyMatch(d -> "node-a".equals(d.getServerId())),
                    "node-a 下线后 node-b 服务表中不应再有 node-a");
        } finally {
            nodeB.stop();
            nodeA.stop();
        }
    }

    private boolean waitForTrue(java.util.function.BooleanSupplier supplier, String message) throws TimeoutException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 5000) {
            if (supplier.getAsBoolean()) {
                return true;
            }
            try { Thread.sleep(200); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new TimeoutException(message);
    }

    private boolean waitForFalse(java.util.function.BooleanSupplier supplier, long timeoutSec, String message) throws TimeoutException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutSec * 1000) {
            if (!supplier.getAsBoolean()) {
                return true;
            }
            try { Thread.sleep(200); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new TimeoutException(message);
    }

    private int freePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
