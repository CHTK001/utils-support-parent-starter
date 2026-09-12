package com.chua.nmap.support.runner;

import com.chua.nmap.support.NmapScanner;
import com.chua.nmap.support.RustNmapScanner;
import com.chua.nmap.support.bridge.RustNmapBridge;

import java.util.List;

/**
 * Nmap 独立测试入口，打包成 fat jar 后在 Linux 服务器上直接运行
   * 用法: Java -jar nmap-测试.jar [Target_ip]
 * @author CH
 * @since 4.0.0
 */
public class NmapTestRunner {

    private static final String SEP = "─".repeat(50); // SEP

    /**
     * main。
     * @param args 参数
     */
    public static void main(String[] args) {
        String target = args.length > 0 ? args[0] : "172.16.0.40";

        System.out.println("╔" + "═".repeat(50) + "╗");
        System.out.println("║  Rust Nmap 测试 - Linux 环境验证");
        System.out.println("║  目标: " + target);
        System.out.println("╚" + "═".repeat(50) + "╝");
        System.out.println();

        // 1. 检查动态库
        System.out.println("[1] 动态库加载状态");
        System.out.println(SEP);
        boolean loaded = RustNmapBridge.isLoaded();
        System.out.println("  isLoaded = " + loaded);
        if (!loaded) {
            System.out.println("  错误: " + (RustNmapBridge.getLoadError() != null
                    ? RustNmapBridge.getLoadError().getMessage() : "unknown"));
            System.out.println("  ✗ 动态库加载失败，退出");
            System.exit(1);
        }
        System.out.println("  版本: " + RustNmapBridge.getVersion());
        System.out.println("  ✓ 动态库加载成功");
        System.out.println();

        // 2. IP 验证
        System.out.println("[2] IP 验证");
        System.out.println(SEP);
        System.out.println("  isValidIp(" + target + ") = " + RustNmapBridge.isValidIp(target));
        System.out.println();

        RustNmapScanner scanner = new RustNmapScanner();
        scanner.setOptions(new NmapScanner.ScanOptions()
                .setTimeout(3000)
                .setConcurrency(100));

        // 3. Ping
        System.out.println("[3] Ping 主机存活检测");
        System.out.println(SEP);
        long t = System.currentTimeMillis();
        NmapScanner.HostInfo hostInfo = scanner.ping(target, 5000);
        System.out.printf("  alive=%-5s  latency=%dms%n", hostInfo.isAlive(), System.currentTimeMillis() - t);
        System.out.println();

        // 4. 常用端口扫描
        System.out.println("[4] 常用端口扫描");
        System.out.println(SEP);
        t = System.currentTimeMillis();
        NmapScanner.ScanResult common = scanner.scanCommonPorts(target);
        System.out.printf("  总端口=%d  开放=%d  耗时=%dms%n",
                common.getTotalPorts(), common.getOpenPorts(), System.currentTimeMillis() - t);
        for (NmapScanner.PortInfo p : common.getPorts()) {
            if (p.getState() == NmapScanner.PortState.OPEN) {
                System.out.printf("  ✓ %5d/%-3s  %s%n", p.getPort(), p.getProtocol(), p.getServiceName());
            }
        }
        System.out.println();

        // 5. Web 端口 + Banner
        System.out.println("[5] Web 端口扫描 + Banner 抓取");
        System.out.println(SEP);
        int[] webPorts = {80, 443, 8080, 8443, 8888, 3000, 9090, 9200};
        NmapScanner.ScanResult web = scanner.scanTcpPorts(target, webPorts);
        for (NmapScanner.PortInfo p : web.getPorts()) {
            String state = p.getState() == NmapScanner.PortState.OPEN ? "OPEN  " : "closed";
            System.out.printf("  %5d  %s", p.getPort(), state);
            if (p.getState() == NmapScanner.PortState.OPEN) {
                String banner = RustNmapBridge.getBanner(target, p.getPort(), 2000);
                if (banner != null && !banner.isBlank()) {
                    String preview = banner.replaceAll("[\\r\\n]+", " ").trim();
                    if (preview.length() > 80) {
                        preview = preview.substring(0, 80) + "...";
                    }
                    System.out.print("  " + preview);
                }
            }
            System.out.println();
        }
        System.out.println();

        // 6. 数据库端口
        System.out.println("[6] 数据库/中间件端口扫描");
        System.out.println(SEP);
        int[] dbPorts = {3306, 5432, 6379, 27017, 1433, 2181, 9092, 5672, 15672};
        NmapScanner.ScanResult db = scanner.scanTcpPorts(target, dbPorts);
        for (NmapScanner.PortInfo p : db.getPorts()) {
            if (p.getState() == NmapScanner.PortState.OPEN) {
                System.out.printf("  ✓ %5d  %s%n", p.getPort(), p.getServiceName());
            }
        }
        System.out.println();

        // 7. 运维端口
        System.out.println("[7] 运维端口扫描");
        System.out.println(SEP);
        int[] adminPorts = {22, 23, 3389, 5900, 2222, 6443, 2375, 2376};
        NmapScanner.ScanResult admin = scanner.scanTcpPorts(target, adminPorts);
        for (NmapScanner.PortInfo p : admin.getPorts()) {
            if (p.getState() == NmapScanner.PortState.OPEN) {
                System.out.printf("  ✓ %5d  %s%n", p.getPort(), p.getServiceName());
            }
        }
        System.out.println();

        // 8. DNS
        System.out.println("[8] DNS 解析");
        System.out.println(SEP);
        String reverse = RustNmapBridge.reverseDns(target);
        System.out.println("  反向DNS: " + (reverse != null ? reverse : "无记录"));
        System.out.println();

        System.out.println("╔" + "═".repeat(50) + "╗");
        System.out.println("║  测试完成");
        System.out.println("╚" + "═".repeat(50) + "╝");
    }
}
