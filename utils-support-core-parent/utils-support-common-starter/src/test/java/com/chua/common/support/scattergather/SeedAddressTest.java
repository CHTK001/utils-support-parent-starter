package com.chua.common.support.scattergather;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SeedAddress 地址解析测试。
 *
 * @author CH
 */
class SeedAddressTest {

    @Test
    void testIpv4HostOnly() {
        SeedAddress addr = SeedAddress.parse("192.168.1.1");
        assertNotNull(addr);
        assertEquals("192.168.1.1", addr.getHost());
        assertEquals(-1, addr.getPort());
        assertFalse(addr.isPortSpecified());
        assertEquals(19001, addr.effectivePort(19001));
    }

    @Test
    void testIpv4HostWithPort() {
        SeedAddress addr = SeedAddress.parse("192.168.1.1:19001");
        assertNotNull(addr);
        assertEquals("192.168.1.1", addr.getHost());
        assertEquals(19001, addr.getPort());
        assertTrue(addr.isPortSpecified());
        assertEquals(19001, addr.effectivePort(20000));
        assertEquals("192.168.1.1:19001", addr.nodeId(20000));
    }

    @Test
    void testHostnameOnly() {
        SeedAddress addr = SeedAddress.parse("my-server");
        assertNotNull(addr);
        assertEquals("my-server", addr.getHost());
        assertEquals(-1, addr.getPort());
        assertFalse(addr.isPortSpecified());
    }

    @Test
    void testHostnameWithPort() {
        SeedAddress addr = SeedAddress.parse("my-server:9090");
        assertNotNull(addr);
        assertEquals("my-server", addr.getHost());
        assertEquals(9090, addr.getPort());
        assertTrue(addr.isPortSpecified());
    }

    @Test
    void testIpv6BracketedWithPort() {
        SeedAddress addr = SeedAddress.parse("[::1]:19001");
        assertNotNull(addr);
        assertEquals("::1", addr.getHost());
        assertEquals(19001, addr.getPort());
        assertTrue(addr.isPortSpecified());
    }

    @Test
    void testIpv6BracketedNoPort() {
        SeedAddress addr = SeedAddress.parse("[::1]");
        assertNotNull(addr);
        assertEquals("::1", addr.getHost());
        assertEquals(-1, addr.getPort());
        assertFalse(addr.isPortSpecified());
    }

    @Test
    void testIpv6FullWithPort() {
        SeedAddress addr = SeedAddress.parse("[2001:db8::1]:8080");
        assertNotNull(addr);
        assertEquals("2001:db8::1", addr.getHost());
        assertEquals(8080, addr.getPort());
        assertTrue(addr.isPortSpecified());
    }

    @Test
    void testIpv6ColonNotation() {
        // 纯 IPv6 地址（无括号，多冒号，无端口）
        SeedAddress addr = SeedAddress.parse("2001:db8::1");
        assertNotNull(addr);
        assertEquals("2001:db8::1", addr.getHost());
        assertEquals(-1, addr.getPort());
        assertFalse(addr.isPortSpecified());
    }

    @Test
    void testNullAndEmpty() {
        assertNull(SeedAddress.parse(null));
        assertNull(SeedAddress.parse("  "));
    }

    @Test
    void testToString() {
        assertEquals("my-host:8080", SeedAddress.parse("my-host:8080").toString());
        assertEquals("my-host", SeedAddress.parse("my-host").toString());
    }

    @Test
    void testEdgeCases() {
        // 纯端口号格式（空 host）应为无效
        assertNull(SeedAddress.parse(":8080"));
        // 带点 hostname
        SeedAddress addr = SeedAddress.parse("node.cluster.local:19001");
        assertNotNull(addr);
        assertTrue(addr.isPortSpecified());
        assertEquals(19001, addr.getPort());
    }
}