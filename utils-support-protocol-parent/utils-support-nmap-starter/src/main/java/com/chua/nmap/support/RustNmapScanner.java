package com.chua.nmap.support;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.nmap.support.bridge.RustNmapBridge;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/**
 * Rust实现的Nmap扫描器
 * 使用Rust本地库实现高性能网络扫描
 *
 * <p>结果口径：native 未加载、Rust 侧未实现、返回值无法解析三类情况一律显式抛错，
 * 不返回空端口/空主机列表冒充"扫描完成"。</p>
 *
 * @author CH
 * @since 4.0.0.34
 */
@Slf4j
@Spi(order = 100)
public class RustNmapScanner implements NmapScanner {

    /**
     * JSON 解析器
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 单次网段/网段范围扫描的主机数上限
     */
    private static final int MAX_HOSTS_PER_SWEEP = 4096;

    private final ExecutorService executor;
    /**
     * 选项
    */
    private volatile ScanOptions options = ScanOptions.defaults();

    /**
     * 构造方法，创建 RustNmapScanner 实例。
     */
    public RustNmapScanner() {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public ScanResult scanTcpPorts(String host, int[] ports) {
        RustNmapBridge.ensureLoaded();
        long startTime = System.currentTimeMillis();
        ScanOptions cfg = options;
        Semaphore permits = new Semaphore(Math.max(1, cfg.getConcurrency()));
        try {
            // 并发扫描，利用虚拟线程
            List<CompletableFuture<PortInfo>> futures = new ArrayList<>(ports.length);
            for (int port : ports) {
                final int p = port;
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        permits.acquire();
                        int result = RustNmapBridge.scanSingleTcpPort(host, p, cfg.getTimeout());
                        return parsePortStateResult(p, result, "TCP");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("端口扫描被中断: host=" + host, e);
                    } finally {
                        permits.release();
                    }
                }, executor));
            }

            List<PortInfo> portInfos = new ArrayList<>(ports.length);
            for (CompletableFuture<PortInfo> future : futures) {
                portInfos.add(future.join());
            }

            int openCount = (int) portInfos.stream()
                    .filter(p -> p.getState() == PortState.OPEN).count();

            ScanResult scanResult = new ScanResult();
            scanResult.setHost(host);
            scanResult.setPorts(portInfos);
            scanResult.setStartTime(startTime);
            scanResult.setEndTime(System.currentTimeMillis());
            scanResult.setDuration(System.currentTimeMillis() - startTime);
            scanResult.setTotalPorts(ports.length);
            scanResult.setOpenPorts(openCount);
            return scanResult;
        } catch (CompletionException e) {
            // native 抛出的错误经 join 包装，脱壳后原样上报，空端口列表只可能是"没扫成"
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw cause instanceof RuntimeException re ? re : new IllegalStateException("TCP端口扫描失败: host=" + host, cause);
        }
    }

    @Override
    public ScanResult scanTcpPorts(String host, int startPort, int endPort) {
        RustNmapBridge.ensureLoaded();
        long startTime = System.currentTimeMillis();
        
        String result = RustNmapBridge.scanTcpPortRange(host, startPort, endPort, 
                options.getTimeout(), options.getConcurrency());
        List<PortInfo> portInfos = parsePortRangeResult(result, "TCP");
        long duration = System.currentTimeMillis() - startTime;
        
        int openCount = 0;
        for (PortInfo portInfo : portInfos) {
            if (portInfo.getState() == PortState.OPEN) {
                openCount++;
            }
        }
        
        ScanResult scanResult = new ScanResult();
        scanResult.setHost(host);
        scanResult.setPorts(portInfos);
        scanResult.setStartTime(startTime);
        scanResult.setEndTime(System.currentTimeMillis());
        scanResult.setDuration(duration);
        scanResult.setTotalPorts(endPort - startPort + 1);
        scanResult.setOpenPorts(openCount);
        return scanResult;
    }

    @Override
    public ScanResult scanUdpPorts(String host, int[] ports) {
        // Rust 侧 scanUdpPorts 是空实现（恒返回 "[]"），以空结果返回会让调用方以为整个网段无 UDP 开放
        throw new UnsupportedOperationException("UDP 端口扫描尚未实现：Rust 侧 scanUdpPorts 恒返回空结果，拒绝以空扫描冒充完成");
    }

    @Override
    public ScanResult scanCommonPorts(String host) {
        int[] commonPorts = {
            21, 22, 23, 25, 53, 80, 110, 111, 135, 139, 143, 443, 445, 993, 995,
            1433, 1521, 2049, 3306, 3389, 5432, 5900, 6379, 8080, 8443, 27017
        };
        return scanTcpPorts(host, commonPorts);
    }

    @Override
    public ScanResult scanAllPorts(String host) {
        return scanTcpPorts(host, 1, 65535);
    }

    @Override
    public HostInfo ping(String host) {
        return ping(host, 3000);
    }

    @Override
    public HostInfo ping(String host, int timeoutMs) {
        RustNmapBridge.ensureLoaded();
        HostInfo hostInfo = new HostInfo();
        hostInfo.setIp(host);
        long startTime = System.currentTimeMillis();
        String result = RustNmapBridge.pingHost(host, timeoutMs);
        long latency = System.currentTimeMillis() - startTime;
        if (result == null || result.trim().isEmpty()) {
            throw new IllegalStateException("主机探测无返回结果: host=" + host);
        }
        // Rust 侧契约是 {"host":"x","is_up":bool}，且永远不会输出 "error"
        Map<String, Object> node = readJsonObject(result, "主机探测结果: host=" + host);
        Object isUp = node.get("is_up");
        if (!(isUp instanceof Boolean)) {
            throw new IllegalStateException("主机探测结果缺少 is_up 字段: host=" + host + ", result=" + result);
        }
        hostInfo.setAlive((Boolean) isUp);
        hostInfo.setLatency(latency);
        Object hostname = node.get("hostname");
        if (hostname != null) {
            hostInfo.setHostname(String.valueOf(hostname));
        }
        Object mac = node.get("mac");
        if (mac != null) {
            hostInfo.setMac(String.valueOf(mac));
        }
        if (node.get("ttl") instanceof Number number) {
            hostInfo.setTtl(number.intValue());
        }
        return hostInfo;
    }

    @Override
    public List<HostInfo> scanSubnet(String subnet) {
        RustNmapBridge.ensureLoaded();
        String result = RustNmapBridge.scanSubnet(subnet,
                options.getTimeout(), options.getConcurrency());
        return parseAliveHostList(result, "子网扫描");
    }

    @Override
    public List<HostInfo> scanIpRange(String startIp, String endIp) {
        RustNmapBridge.ensureLoaded();
        // Rust 侧 scanIpRange 是未实现桩（恒返回 "[]"），改为 Java 枚举 + 逐台 ping，避免把"没实现"当成"没有存活主机"
        long start = ipv4ToLong(startIp);
        long end = ipv4ToLong(endIp);
        if (start > end) {
            throw new IllegalArgumentException("IP 范围起点大于终点: " + startIp + " - " + endIp);
        }
        long count = end - start + 1;
        if (count > MAX_HOSTS_PER_SWEEP) {
            throw new IllegalArgumentException("IP 范围过大: " + count + " 台，上限 " + MAX_HOSTS_PER_SWEEP + " 台，请改用 scanSubnet 或分段调用");
        }
        ScanOptions cfg = options;
        Semaphore permits = new Semaphore(Math.max(1, cfg.getConcurrency()));
        List<CompletableFuture<HostInfo>> futures = new ArrayList<>((int) count);
        for (long i = 0; i < count; i++) {
            final String ip = longToIpv4(start + i);
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    permits.acquire();
                    return ping(ip, cfg.getTimeout());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("IP 范围扫描被中断: " + ip, e);
                } finally {
                    permits.release();
                }
            }, executor));
        }
        List<HostInfo> alive = new ArrayList<>();
        for (CompletableFuture<HostInfo> future : futures) {
            HostInfo info = future.join();
            if (info.isAlive()) {
                alive.add(info);
            }
        }
        return alive;
    }

    @Override
    public ServiceInfo detectService(String host, int port) {
        RustNmapBridge.ensureLoaded();
        ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.setPort(port);
        String result = RustNmapBridge.detectService(host, port, 5000);
        if (result == null || result.trim().isEmpty()) {
            throw new IllegalStateException("服务探测无返回结果: host=" + host + ", port=" + port);
        }
        Map<String, Object> node = readJsonObject(result, "服务探测结果: host=" + host + ", port=" + port);
        String banner = node.get("banner") == null ? "" : String.valueOf(node.get("banner"));
        String guessed = node.get("service") == null ? "unknown" : String.valueOf(node.get("service"));
        if (banner.isEmpty()) {
            // Rust 侧 service 只是端口号到常见服务名的映射表，没抓到 banner 就不算识别出来
            serviceInfo.setName("unknown");
            serviceInfo.setExtraInfo("未取得 banner，端口 " + port + " 的常见服务映射为 " + guessed);
        } else {
            serviceInfo.setName(guessed);
            serviceInfo.setExtraInfo(banner);
        }
        return serviceInfo;
    }

    @Override
    public List<ServiceInfo> detectServices(String host, int[] ports) {
        List<ServiceInfo> services = new ArrayList<>();
        for (int port : ports) {
            services.add(detectService(host, port));
        }
        return services;
    }

    @Override
    public OsInfo detectOs(String host) {
        RustNmapBridge.ensureLoaded();
        // Rust 侧 detectOs 是恒返回 {"os":"unknown"} 的桩，输出它只会把"没实现"伪装成探测结论
        throw new UnsupportedOperationException("OS 识别尚未实现：Rust 侧 detectOs 恒返回 unknown，拒绝输出无依据的结论");
    }

    @Override
    public CompletableFuture<ScanResult> scanTcpPortsAsync(String host, int[] ports) {
        return CompletableFuture.supplyAsync(() -> scanTcpPorts(host, ports), executor);
    }

    @Override
    public CompletableFuture<List<HostInfo>> scanSubnetAsync(String subnet) {
        return CompletableFuture.supplyAsync(() -> scanSubnet(subnet), executor);
    }

    @Override
    public CompletableFuture<ScanResult> scanWithProgress(String host, int[] ports, Consumer<ScanProgress> progressCallback) {
        RustNmapBridge.ensureLoaded();
        return CompletableFuture.supplyAsync(() -> {
            int total = ports.length;
            List<PortInfo> results = new ArrayList<>();
            int openCount = 0;
            long startTime = System.currentTimeMillis();
            
            for (int i = 0; i < ports.length; i++) {
                int port = ports[i];
                int result = RustNmapBridge.scanSingleTcpPort(host, port, options.getTimeout());
                PortInfo portInfo = parsePortStateResult(port, result, "TCP");
                results.add(portInfo);
                if (portInfo.getState() == PortState.OPEN) {
                    openCount++;
                }
                
                int current = i + 1;
                double progress = (double) current / total * 100;
                
                ScanProgress scanProgress = new ScanProgress();
                scanProgress.setCurrentPort(port);
                scanProgress.setScannedPorts(current);
                scanProgress.setTotalPorts(total);
                scanProgress.setProgress(progress);
                scanProgress.setOpenPorts(openCount);
                progressCallback.accept(scanProgress);
            }
            
            ScanResult scanResult = new ScanResult();
            scanResult.setHost(host);
            scanResult.setPorts(results);
            scanResult.setStartTime(startTime);
            scanResult.setEndTime(System.currentTimeMillis());
            scanResult.setDuration(System.currentTimeMillis() - startTime);
            scanResult.setTotalPorts(total);
            scanResult.setOpenPorts(openCount);
            return scanResult;
        }, executor);
    }

    @Override
    public NmapScanner setOptions(ScanOptions options) {
        this.options = options;
        return this;
    }

    @Override
    public ScanOptions getOptions() {
        return this.options;
    }

    /**
     * 解析端口状态结果 (int状态码)
     * @param port 端口号
     * @param stateCode 状态码 (0=open, 1=closed, 2=filtered, -1=error)
     * @param protocol 协议
     * @return 端口信息
     */
    private PortInfo parsePortStateResult(int port, int stateCode, String protocol) {
        PortInfo portInfo = new PortInfo();
        portInfo.setPort(port);
        portInfo.setProtocol(protocol);
        portInfo.setServiceName("unknown");
        
        switch (stateCode) {
            case 0 -> portInfo.setState(PortState.OPEN);
            case 1 -> portInfo.setState(PortState.CLOSED);
            case 2 -> portInfo.setState(PortState.FILTERED);
            default -> portInfo.setState(PortState.UNKNOWN);
        }
        
        return portInfo;
    }

    /**
     * 解析端口Range结果。
     *
     * @param result 结果，不允许为 null
     * @param protocol 方法入参 protocol
     * @return 结果列表，无数据时为空列表
     */
    private List<PortInfo> parsePortRangeResult(String result, String protocol) {
        List<PortInfo> portInfos = new ArrayList<>();
        List<Map<String, Object>> list = readJsonList(result, "端口扫描结果: protocol=" + protocol);
        for (Map<String, Object> item : list) {
            if (item == null || item.get("port") == null) {
                continue;
            }
            int port;
            try {
                port = ((Number) item.get("port")).intValue();
            } catch (Exception e) {
                continue;
            }
            PortInfo portInfo = new PortInfo();
            portInfo.setPort(port);
            portInfo.setProtocol(item.get("protocol") == null
                    ? protocol : String.valueOf(item.get("protocol")));
            Object state = item.get("state");
            // 缺 state 时不得默认 OPEN，否则平白多出"开放端口"
            portInfo.setState(state == null ? PortState.UNKNOWN : parsePortState(state.toString()));
            Object service = item.get("service");
            portInfo.setServiceName(service == null ? "unknown" : service.toString());
            portInfos.add(portInfo);
        }
        return portInfos;
    }

    /**
     * 解析端口状态。
     *
     * @param state 状态，不允许为 null
     * @return 端口状态 对象
     */
    private PortState parsePortState(String state) {
        return switch (state.toLowerCase()) {
            case "open" -> PortState.OPEN;
            case "closed" -> PortState.CLOSED;
            case "filtered" -> PortState.FILTERED;
            default -> PortState.UNKNOWN;
        };
    }

    /**
     * 解析 Rust 侧存活主机数组 {@code ["10.0.0.1","10.0.0.2"]}。
     *
     * @param result 结果，不允许为 null
     * @param label 出错时使用的上下文说明，不允许为 null
     * @return 存活主机列表，无存活主机时为空列表
     */
    private List<HostInfo> parseAliveHostList(String result, String label) {
        List<HostInfo> hosts = new ArrayList<>();
        for (Object item : readJsonRawList(result, label)) {
            if (item == null) {
                continue;
            }
            HostInfo hostInfo = new HostInfo();
            hostInfo.setIp(String.valueOf(item));
            hostInfo.setAlive(true);
            hosts.add(hostInfo);
        }
        return hosts;
    }

    /**
     * 读取 JSON 对象，解析失败或入参为空时显式抛错。
     *
     * @param json JSON 文本
     * @param label 上下文说明
     * @return 对象键值，不允许为 null
     */
    private static Map<String, Object> readJsonObject(String json, String label) {
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("解析失败[" + label + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 读取 JSON 对象数组，解析失败或入参为空时显式抛错。
     *
     * @param json JSON 文本
     * @param label 上下文说明
     * @return 对象数组，不允许为 null
     */
    private static List<Map<String, Object>> readJsonList(String json, String label) {
        try {
            return JSON.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("解析失败[" + label + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 读取 JSON 字符串数组，解析失败或入参为空时显式抛错。
     *
     * @param json JSON 文本
     * @param label 上下文说明
     * @return 字符串数组，不允许为 null
     */
    private static List<Object> readJsonRawList(String json, String label) {
        try {
            return JSON.readValue(json, new TypeReference<List<Object>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("解析失败[" + label + "]: " + e.getMessage(), e);
        }
    }

    /**
     * IPv4 点分十进制转无符号长整型。
     *
     * @param ip IP 文本，不允许为 null
     * @return 数值
     */
    private static long ipv4ToLong(String ip) {
        String[] parts = ip == null ? new String[0] : ip.split("\\.");
        if (parts.length != 4) {
            throw new IllegalArgumentException("非法 IPv4 地址: " + ip);
        }
        long value = 0L;
        for (String part : parts) {
            int octet;
            try {
                octet = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("非法 IPv4 地址: " + ip, e);
            }
            if (octet < 0 || octet > 255) {
                throw new IllegalArgumentException("非法 IPv4 地址: " + ip);
            }
            value = (value << 8) | octet;
        }
        return value;
    }

    /**
     * 无符号长整型转 IPv4 点分十进制。
     *
     * @param value 数值
     * @return IP 文本，不允许为 null
     */
    private static String longToIpv4(long value) {
        return ((value >> 24) & 0xFF) + "." + ((value >> 16) & 0xFF)
                + "." + ((value >> 8) & 0xFF) + "." + (value & 0xFF);
    }
}
