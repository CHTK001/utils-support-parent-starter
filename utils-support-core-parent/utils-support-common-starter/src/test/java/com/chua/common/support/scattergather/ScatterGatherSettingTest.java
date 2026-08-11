package com.chua.common.support.scattergather;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ScatterGatherSetting 配置项测试。
 *
 * @author CH
 */
class ScatterGatherSettingTest {

    @Test
    void testDefaults() {
        ScatterGatherSetting s = new ScatterGatherSetting();
        assertEquals("local", s.getNodeId());
        assertEquals("127.0.0.1", s.getHost());
        assertEquals(19001, s.getTcpPort());
        assertEquals("tcp", s.getTransportProtocol());
        assertFalse(s.isUdpBroadcast());
        assertTrue(s.isUdpFallbackToTcp());
        assertEquals("auto", s.getTcpMode());
        assertTrue(s.getSeedAddresses().isEmpty());
        assertEquals(19001, s.getDefaultPort());
        assertTrue(s.isCleanupOnClose());
        assertEquals(60000L, s.getAutoDiscoveryIntervalMillis());
        assertEquals("255.255.255.255", s.getUdpBroadcastAddress());
    }

    @Test
    void testSettingChain() {
        ScatterGatherSetting s = new ScatterGatherSetting()
                .setNodeId("test-node")
                .setHost("10.0.0.55")
                .setTcpPort(20000)
                .setTransportProtocol("udp")
                .setUdpBroadcast(true)
                .setUdpBroadcastAddress("255.255.0.1")
                .setUdpFallbackToTcp(false)
                .setTcpMode("seed")
                .setSeedAddresses(List.of("a:19001", "b"))
                .setDefaultPort(19001)
                .setCleanupOnClose(false)
                .setAutoDiscoveryIntervalMillis(120000L);

        assertEquals("test-node", s.getNodeId());
        assertEquals("10.0.0.55", s.getHost());
        assertEquals(20000, s.getTcpPort());
        assertEquals("udp", s.getTransportProtocol());
        assertTrue(s.isUdpBroadcast());
        assertEquals("255.255.0.1", s.getUdpBroadcastAddress());
        assertFalse(s.isUdpFallbackToTcp());
        assertEquals("seed", s.getTcpMode());
        assertEquals(2, s.getSeedAddresses().size());
        assertEquals(19001, s.getDefaultPort());
        assertFalse(s.isCleanupOnClose());
        assertEquals(120000L, s.getAutoDiscoveryIntervalMillis());
    }
}