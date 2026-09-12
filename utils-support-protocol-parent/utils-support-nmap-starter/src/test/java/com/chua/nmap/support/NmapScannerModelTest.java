package com.chua.nmap.support;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
   * nmapscanner 数据模型测试（不依赖动态库）
 * @author CH
 * @since 4.0.0
 */
class NmapScannerModelTest {

 // ── 扫描期权 ───────────────────────────────────────────────────────────

    @Test
    void scanOptions_defaults() {
        NmapScanner.ScanOptions opts = NmapScanner.ScanOptions.defaults();
        assertEquals(1000, opts.getTimeout());
        assertEquals(100, opts.getConcurrency());
        assertEquals(1, opts.getRetries());
        assertEquals(0, opts.getDelay());
        assertFalse(opts.isServiceDetection());
        assertFalse(opts.isOsDetection());
        assertEquals(NmapScanner.ScanType.TCP_CONNECT, opts.getScanType());
    }

    @Test
    void scanOptions_fast() {
        NmapScanner.ScanOptions opts = NmapScanner.ScanOptions.fast();
        assertEquals(500, opts.getTimeout());
        assertEquals(500, opts.getConcurrency());
    }

    @Test
    void scanOptions_thorough() {
        NmapScanner.ScanOptions opts = NmapScanner.ScanOptions.thorough();
        assertEquals(3000, opts.getTimeout());
        assertEquals(3, opts.getRetries());
        assertTrue(opts.isServiceDetection());
    }

    @Test
    void scanOptions_chaining() {
        NmapScanner.ScanOptions opts = new NmapScanner.ScanOptions()
                .setTimeout(2000)
                .setConcurrency(200)
                .setServiceDetection(true)
                .setOsDetection(true)
                .setScanType(NmapScanner.ScanType.TCP_SYN);
        assertEquals(2000, opts.getTimeout());
        assertEquals(200, opts.getConcurrency());
        assertTrue(opts.isServiceDetection());
        assertTrue(opts.isOsDetection());
        assertEquals(NmapScanner.ScanType.TCP_SYN, opts.getScanType());
    }

 // ── 端口信息 ──────────────────────────────────────────────────────────────

    @Test
    void portInfo_toString_open() {
        NmapScanner.PortInfo p = new NmapScanner.PortInfo();
        p.setPort(80);
        p.setProtocol("TCP");
        p.setState(NmapScanner.PortState.OPEN);
        p.setServiceName("http");
        String s = p.toString();
        assertTrue(s.contains("80"));
        assertTrue(s.contains("TCP"));
        assertTrue(s.contains("http"));
    }

    @Test
    void portInfo_all_states() {
        for (NmapScanner.PortState state : NmapScanner.PortState.values()) {
            NmapScanner.PortInfo p = new NmapScanner.PortInfo();
            p.setState(state);
            assertNotNull(p.getState());
        }
    }

 // ── 主机信息 ──────────────────────────────────────────────────────────────

    @Test
    void hostInfo_toString_alive() {
        NmapScanner.HostInfo h = new NmapScanner.HostInfo();
        h.setIp("192.168.1.1");
        h.setHostname("router.local");
        h.setAlive(true);
        String s = h.toString();
        assertTrue(s.contains("192.168.1.1"));
        assertTrue(s.contains("alive"));
    }

    @Test
    void hostInfo_toString_down() {
        NmapScanner.HostInfo h = new NmapScanner.HostInfo();
        h.setIp("10.0.0.1");
        h.setAlive(false);
        assertTrue(h.toString().contains("down"));
    }

    @Test
    void hostInfo_fields() {
        NmapScanner.HostInfo h = new NmapScanner.HostInfo();
        h.setIp("1.2.3.4");
        h.setMac("AA:BB:CC:DD:EE:FF");
        h.setVendor("Cisco");
        h.setLatency(5L);
        h.setTtl(64);
        assertEquals("1.2.3.4", h.getIp());
        assertEquals("AA:BB:CC:DD:EE:FF", h.getMac());
        assertEquals("Cisco", h.getVendor());
        assertEquals(5L, h.getLatency());
        assertEquals(64, h.getTtl());
    }

 // ── 扫描结果 ────────────────────────────────────────────────────────────

    @Test
    void scanResult_fields() {
        NmapScanner.ScanResult r = new NmapScanner.ScanResult();
        r.setHost("192.168.1.1");
        r.setTotalPorts(100);
        r.setOpenPorts(3);
        r.setDuration(500L);
        assertEquals("192.168.1.1", r.getHost());
        assertEquals(100, r.getTotalPorts());
        assertEquals(3, r.getOpenPorts());
        assertEquals(500L, r.getDuration());
    }

 // ── 服务信息 ───────────────────────────────────────────────────────────

    @Test
    void serviceInfo_fields() {
        NmapScanner.ServiceInfo s = new NmapScanner.ServiceInfo();
        s.setPort(443);
        s.setName("https");
        s.setProduct("nginx");
        s.setVersion("1.24");
        s.setConfidence(90);
        assertEquals(443, s.getPort());
        assertEquals("https", s.getName());
        assertEquals("nginx", s.getProduct());
        assertEquals(90, s.getConfidence());
    }

 // ── os信息 ────────────────────────────────────────────────────────────────

    @Test
    void osInfo_fields() {
        NmapScanner.OsInfo o = new NmapScanner.OsInfo();
        o.setName("Linux");
        o.setFamily("Unix");
        o.setVersion("5.15");
        o.setVendor("Canonical");
        o.setDeviceType("server");
        o.setAccuracy(85);
        assertEquals("Linux", o.getName());
        assertEquals(85, o.getAccuracy());
    }

 // ── 扫描进步 ──────────────────────────────────────────────────────────

    @Test
    void scanProgress_fields() {
        NmapScanner.ScanProgress p = new NmapScanner.ScanProgress();
        p.setCurrentPort(80);
        p.setScannedPorts(50);
        p.setTotalPorts(100);
        p.setProgress(50.0);
        p.setOpenPorts(3);
        assertEquals(80, p.getCurrentPort());
        assertEquals(50.0, p.getProgress());
    }

 // ── 扫描类型 enum ─────────────────────────────────────────────────────────

    @Test
    void scanType_all_values() {
        assertEquals(4, NmapScanner.ScanType.values().length);
        assertNotNull(NmapScanner.ScanType.valueOf("TCP_CONNECT"));
        assertNotNull(NmapScanner.ScanType.valueOf("TCP_SYN"));
        assertNotNull(NmapScanner.ScanType.valueOf("UDP"));
        assertNotNull(NmapScanner.ScanType.valueOf("PING"));
    }
}
