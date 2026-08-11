package com.chua.common.support.scattergather;

import com.chua.common.support.network.discovery.Discovery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ScatterGatherBuilder 链式构建器测试。
 *
 * @author CH
 */
class ScatterGatherBuilderTest {

    @Test
    void testChainBuilderTcp() {
        ScatterGatherBuilder<?> builder = ScatterGatherBuilder.builder()
                .nodeId("node1")
                .host("192.168.1.10")
                .port(19001)
                .protocol("tcp")
                .tcpMode("seed")
                .seeds("192.168.1.20:19002", "192.168.1.30")
                .defaultPort(19001)
                .timeout(5000)
                .cleanupOnClose(true);

        ScatterGatherSetting setting = builder.setting();
        assertEquals("node1", setting.getNodeId());
        assertEquals("192.168.1.10", setting.getHost());
        assertEquals(19001, setting.getTcpPort());
        assertEquals("tcp", setting.getTransportProtocol());
        assertEquals("seed", setting.getTcpMode());
        assertEquals(2, setting.getSeedAddresses().size());
        assertEquals(19001, setting.getDefaultPort());
        assertEquals(5000L, setting.getTimeoutMillis());
        assertTrue(setting.isCleanupOnClose());
    }

    @Test
    void testChainBuilderUdpBroadcast() {
        ScatterGatherBuilder<?> builder = ScatterGatherBuilder.builder()
                .protocol("udp")
                .udpBroadcast()
                .udpBroadcastAddress("255.255.255.255")
                .udpFallbackToTcp(true);

        ScatterGatherSetting setting = builder.setting();
        assertEquals("udp", setting.getTransportProtocol());
        assertTrue(setting.isUdpBroadcast());
        assertEquals("255.255.255.255", setting.getUdpBroadcastAddress());
        assertTrue(setting.isUdpFallbackToTcp());
    }

    @Test
    void testBuildTcpClient() {
        ScatterGatherRemoteClient<Object> client = ScatterGatherBuilder.builder()
                .protocol("tcp")
                .buildClient();
        assertNotNull(client);
        assertInstanceOf(com.chua.common.support.taskdistribution.scattergather.TcpScatterGatherRemoteClient.class, client);
    }

    @Test
    void testBuildUdpClient() {
        ScatterGatherRemoteClient<Object> client = ScatterGatherBuilder.builder()
                .protocol("udp")
                .buildClient();
        assertNotNull(client);
        assertInstanceOf(com.chua.common.support.taskdistribution.scattergather.UdpScatterGatherRemoteClient.class, client);
    }

    @Test
    void testBuildNodeServer() {
        ScatterGatherNodeServer server = ScatterGatherBuilder.builder()
                .host("127.0.0.1")
                .port(19001)
                .protocol("tcp")
                .buildNodeServer();
        assertNotNull(server);
    }

    @Test
    void testSeedAddressDefaultPort() {
        ScatterGatherBuilder<?> builder = ScatterGatherBuilder.builder()
                .defaultPort(20000)
                .seeds("192.168.1.88");
        ScatterGatherSetting setting = builder.setting();
        assertEquals(20000, setting.getDefaultPort());
        assertEquals(List.of("192.168.1.88"), setting.getSeedAddresses());
    }

    @Test
    void testDiscoveryFromSetting() {
        ScatterGatherSetting setting = new ScatterGatherSetting();
        setting.setNodeId("n1");
        setting.setHost("127.0.0.1");
        setting.setTcpPort(19001);
        setting.setTransportProtocol("tcp");
        setting.setTcpMode("seed");
        setting.setSeedAddresses(List.of("10.0.0.1:19001", "10.0.0.2"));
        setting.setDefaultPort(19001);

        ScatterGatherServiceDiscovery discovery = new ScatterGatherServiceDiscovery(
                new com.chua.common.support.network.discovery.DiscoveryOption(), setting);
        discovery.queryHandler(context -> Discovery.builder().serverId("local").host("127.0.0.1").port(19001).build());

        try {
            discovery.start();
            assertNotNull(discovery.getService("/scatter-gather", "weight", "tcp"));
        } catch (Exception e) {
            fail("启动失败: " + e.getMessage());
        } finally {
            try {
                discovery.stop();
            } catch (Exception ignored) {
                // 忽略
            }
        }
    }
}