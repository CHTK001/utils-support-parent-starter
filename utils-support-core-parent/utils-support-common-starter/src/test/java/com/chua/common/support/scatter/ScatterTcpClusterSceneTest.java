package com.chua.common.support.scatter;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TCP 双节点 seed 互发现 + gossip 合并场景测试。
 * <p>节点 A 注册自身服务；节点 B 通过 seed 引导 + gossip 拉取到 A 的服务，合并进本地 hash 表。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class ScatterTcpClusterSceneTest {

    /**
     * 获取空闲端口。
     */
    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * 注册节点服务端响应逻辑：收到 sync/request 后回发 ScatterResultWithRequestId。
     */
    private void attachResponseHandler(ScatterNodeServer nodeServer, ScatterServiceDiscovery discovery,
                                       String nodeId) {
        SyncServer syncServer = nodeServer.getSyncServer();
        syncServer.addListener(new SyncServerListener() {
            @Override
            /** OnMessage */
            public void onMessage(String clientId, String messageTopic, Object message) {
                if (!"sync/request".equals(messageTopic) || message == null) {
                    return;
                }
                try {
                    String payload = message.toString();
                    String requestId = null;
                    String path = null;
                    int ri = payload.indexOf("\"requestId\":\"");
                    if (ri >= 0) {
                        requestId = payload.substring(ri + 14, payload.indexOf('"', ri + 14));
                    }
                    int pi = payload.indexOf("\"path\":\"");
                    if (pi >= 0) {
                        path = payload.substring(pi + 8, payload.indexOf('"', pi + 8));
                    }
                    if (requestId == null || path == null) {
                        return;
                    }
                    Set<Discovery> services = discovery.getServiceAll(path);
                    Discovery picked = services.stream().findFirst().orElse(null);
                    ScatterResult<Discovery> result = picked != null
                            ? ScatterResult.success(nodeId, picked)
                            : ScatterResult.failure(nodeId, "无服务");
                    syncServer.send(clientId, "sync/response",
                            new ScatterResultWithRequestId<>(requestId, result));
                } catch (Exception e) {
                    // 忽略
                }
            }
        });
    }

    /**
     * B 通过 seed 引导，gossip 拉取并合并 A 的服务。
     */
    @Test
    void shouldDiscoverRemoteNodeViaSeedAndGossip() throws Exception {
        int portA = freePort();
        int portB = freePort();

        // 节点 A
        ScatterSetting settingA = new ScatterSetting();
        settingA.setNodeId("node-a").setGroupId("order").setHost("127.0.0.1").setPort(portA);
        settingA.setServicePath("/scatter").setPersistenceEnabled(false);
        settingA.setAutoDiscoveryIntervalMillis(200).setTimeoutMillis(1000);
        DefaultScatterServiceDiscovery discoveryA = new DefaultScatterServiceDiscovery(settingA);
        discoveryA.start();
        // A 注册自身服务
        discoveryA.registerService("/scatter", Discovery.builder()
                .serverId("node-a").scatterId("order").protocol("tcp")
                .host("127.0.0.1").port(portA).weight(1).build());
        // A 节点服务端（响应 B 的 gossip 查询）
        ScatterNodeServer serverA = new TcpScatterBuilder(settingA).buildNodeServer();
        attachResponseHandler(serverA, discoveryA, "node-a");
        serverA.start();

        // 节点 B：seed 指向 A，gossip 拉取
        ScatterSetting settingB = new ScatterSetting();
        settingB.setNodeId("node-b").setGroupId("order").setHost("127.0.0.1").setPort(portB);
        settingB.setServicePath("/scatter").setPersistenceEnabled(false);
        settingB.setSeeds(java.util.List.of("127.0.0.1:" + portA));
        settingB.setAutoDiscoveryIntervalMillis(200).setTimeoutMillis(1000);
        DefaultScatterServiceDiscovery discoveryB = new DefaultScatterServiceDiscovery(settingB);
        discoveryB.remoteClient(new TcpScatterBuilder(settingB).buildRemoteClient());
        discoveryB.start();

        // 等待 gossip 周期拉取
        TimeUnit.SECONDS.sleep(1);

        // 断言 B 已合并 A 的服务
        boolean found = discoveryB.getServiceAll("/scatter").stream()
                .anyMatch(d -> "node-a".equals(d.getServerId()));
        assertTrue(found, "B 应通过 gossip 合并到 A 的服务");

        serverA.close();
        discoveryB.close();
        discoveryA.close();
    }
}
