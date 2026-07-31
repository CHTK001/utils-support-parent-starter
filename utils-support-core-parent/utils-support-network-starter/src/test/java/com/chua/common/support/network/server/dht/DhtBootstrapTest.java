package com.chua.common.support.network.server.dht;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DHT 网络接入测试 — 实时采集公共 DHT 网络节点。
 *
 * @author CH
 */
class DhtBootstrapTest {

    @Test
    void testBootstrapToPublicDht() throws Exception {
        DHTServer server = DHTServer.builder()
                .port(6881)
                .build();

        System.out.println("启动 DHT 服务器...");
        server.start();
        System.out.println("NodeId: " + server.selfId());
        System.out.println("监听端口: 6881");
        System.out.println("种子节点: " + String.join(", ",
                "router.bittorrent.com:6881",
                "dht.transmissionbt.com:6881",
                "dht.aelitis.com:6881",
                "router.utorrent.com:6881"));
        System.out.println("---");

        AtomicInteger round = new AtomicInteger(0);

        for (int i = 0; i < 6; i++) {
            Thread.sleep(5000);
            int r = round.incrementAndGet();
            int totalPeers = server.protocol().routingTable().totalPeers();
            List<DhtPeer> allPeers = server.protocol().routingTable().getAllPeers();
            int incomingCount = server.protocol().valueStore().size();

            System.out.printf("[%2ds] 路由表: %3d 节点 | 入站消息: %d | 最新节点: ",
                    r * 5, totalPeers, incomingCount);

            if (!allPeers.isEmpty()) {
                DhtPeer last = allPeers.get(allPeers.size() - 1);
                System.out.printf("%s@%s:%d (距上次 %.1fs)",
                        last.getNodeId().substring(0, 8) + "...",
                        last.getHost(), last.getPort(),
                        (System.currentTimeMillis() - last.getLastSeen()) / 1000.0);
            } else {
                System.out.print("无");
            }
            System.out.println();

            if (totalPeers >= 10) {
                System.out.println();
                System.out.println("已采集 " + totalPeers + " 个节点！网络接入成功。");
                System.out.println("示例节点:");
                allPeers.stream().limit(5).forEach(p ->
                        System.out.printf("  %s %s:%d%n", p.getNodeId(), p.getHost(), p.getPort()));
                break;
            }
        }

        System.out.println("---");
        System.out.println("最终路由表: " + server.protocol().routingTable().totalPeers() + " 个节点");
        server.stop();
    }
}
