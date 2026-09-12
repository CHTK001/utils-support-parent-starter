package com.chua.nmap.support;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.nmap.support.bridge.RustNmapBridge;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
* Rust实现的Nmap扫描器
* 使用Rust本地库实现高性能网络扫描
*
* @author CH
* @since 4.0.0.34
 */
@Slf4j
@Spi(order = 100)
public class RustNmapScanner implements NmapScanner {

    private final ExecutorService executor;
    private ScanOptions options = ScanOptions.defaults();

    public RustNmapScanner() {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public ScanResult scanTcpPorts(String host, int[] ports) {
        long startTime = System.currentTimeMillis();
        try {
            // 并发扫描，利用虚拟线程
            List<CompletableFuture<PortInfo>> futures = new ArrayList<>(ports.length);
            for (int port : ports) {
                final int p = port;
                futures.add(CompletableFuture.supplyAsync(() -> {
                    int result = RustNmapBridge.scanSingleTcpPort(host, p, options.getTimeout());
                    return parsePortStateResult(p, result, "TCP");
                }, executor));
            }

            List<PortInfo> portInfos = futures.stream()
                    .map(CompletableFuture::join)
                    .collect(java.util.stream.Collectors.toList());

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
        } catch (Exception e) {
            log.error("TCP端口扫描失败: host={}, error={}", host, e.getMessage());
            ScanResult scanResult = new ScanResult();
            scanResult.setHost(host);
            scanResult.setPorts(Collections.emptyList());
            scanResult.setDuration(System.currentTimeMillis() - startTime);
            return scanResult;
        }
    }

    @Override
    public ScanResult scanTcpPorts(String host, int startPort, int endPort) {
        long startTime = System.currentTimeMillis();
        
        try {
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
        } catch (Exception e) {
            log.error("TCP端口范围扫描失败: host={}, error={}", host, e.getMessage());
            ScanResult scanResult = new ScanResult();
            scanResult.setHost(host);
            scanResult.setPorts(Collections.emptyList());
            scanResult.setDuration(System.currentTimeMillis() - startTime);
            return scanResult;
        }
    }

    @Override
    public ScanResult scanUdpPorts(String host, int[] ports) {
        long startTime = System.currentTimeMillis();
        
        try {
            String result = RustNmapBridge.scanUdpPorts(host, ports, 
                    options.getTimeout(), options.getConcurrency());
            List<PortInfo> portInfos = parsePortRangeResult(result, "UDP");
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
            scanResult.setTotalPorts(ports.length);
            scanResult.setOpenPorts(openCount);
            return scanResult;
        } catch (Exception e) {
            log.error("UDP端口扫描失败: host={}, error={}", host, e.getMessage());
            ScanResult scanResult = new ScanResult();
            scanResult.setHost(host);
            scanResult.setPorts(Collections.emptyList());
            scanResult.setDuration(System.currentTimeMillis() - startTime);
            return scanResult;
        }
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
        HostInfo hostInfo = new HostInfo();
        hostInfo.setIp(host);
        try {
            long startTime = System.currentTimeMillis();
            String result = RustNmapBridge.pingHost(host, timeoutMs);
            long latency = System.currentTimeMillis() - startTime;
            // Parse JSON result to check if host is alive
            boolean alive = result != null && !result.isEmpty() && !result.contains("error");
            hostInfo.setAlive(alive);
            hostInfo.setLatency(latency);
            // Parse additional info from result if available
            if (result != null && !result.isEmpty()) {
                parseHostInfoFromJson(hostInfo, result);
            }
        } catch (Exception e) {
            log.error("Ping失败: host={}, error={}", host, e.getMessage());
            hostInfo.setAlive(false);
        }
        return hostInfo;
    }

    @Override
    public List<HostInfo> scanSubnet(String subnet) {
        try {
            String result = RustNmapBridge.scanSubnet(subnet, 
                    options.getTimeout(), options.getConcurrency());
            return parseHostListResult(result);
        } catch (Exception e) {
            log.error("子网扫描失败: subnet={}, error={}", subnet, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<HostInfo> scanIpRange(String startIp, String endIp) {
        try {
            String result = RustNmapBridge.scanIpRange(startIp, endIp, 
                    options.getTimeout(), options.getConcurrency());
            return parseHostListResult(result);
        } catch (Exception e) {
            log.error("IP范围扫描失败: start={}, end={}, error={}", startIp, endIp, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public ServiceInfo detectService(String host, int port) {
        ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.setPort(port);
        try {
            String result = RustNmapBridge.detectService(host, port, 5000);
            if (result != null && !result.isEmpty()) {
                String[] parts = result.split("\\|");
                if (parts.length >= 1) {
                    serviceInfo.setName(parts[0]);
                }
                if (parts.length >= 2) {
                    serviceInfo.setVersion(parts[1]);
                }
                if (parts.length >= 3) {
                    serviceInfo.setExtraInfo(parts[2]);
                }
            } else {
                serviceInfo.setName("unknown");
            }
        } catch (Exception e) {
            log.error("服务检测失败: host={}, port={}, error={}", host, port, e.getMessage());
            serviceInfo.setName("unknown");
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
        OsInfo osInfo = new OsInfo();
        try {
            String result = RustNmapBridge.detectOs(host, 5000);
            if (result != null && !result.isEmpty()) {
                String[] parts = result.split("\\|");
                if (parts.length >= 1) {
                    osInfo.setName(parts[0]);
                }
                if (parts.length >= 2) {
                    osInfo.setFamily(parts[1]);
                }
                if (parts.length >= 3) {
                    osInfo.setVersion(parts[2]);
                }
                if (parts.length >= 4) {
                    try {
                        osInfo.setAccuracy(Integer.parseInt(parts[3]));
                    } catch (NumberFormatException e) {
                        osInfo.setAccuracy(0);
                    }
                }
            } else {
                osInfo.setName("unknown");
                osInfo.setAccuracy(0);
            }
        } catch (Exception e) {
            log.error("OS检测失败: host={}, error={}", host, e.getMessage());
            osInfo.setName("unknown");
            osInfo.setAccuracy(0);
        }
        return osInfo;
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
    
    private PortInfo parsePortResult(int port, String result, String protocol) {
        PortInfo portInfo = new PortInfo();
        portInfo.setPort(port);
        portInfo.setProtocol(protocol);
        portInfo.setState(PortState.CLOSED);
        portInfo.setServiceName("unknown");
        
        if (result != null && !result.isEmpty()) {
            String[] parts = result.split("\\|");
            if (parts.length >= 1) {
                portInfo.setState(parsePortState(parts[0]));
            }
            if (parts.length >= 2) {
                portInfo.setServiceName(parts[1]);
            }
            if (parts.length >= 3) {
                portInfo.setBanner(parts[2]);
            }
        }
        
        return portInfo;
    }
    
    /**
    * 从JSON结果解析主机信息
     */
    private void parseHostInfoFromJson(HostInfo hostInfo, String json) {
        // Simple JSON parsing - in production use a proper JSON library
        if (json.contains("hostname")) {
            int start = json.indexOf("hostname") + 11;
            int end = json.indexOf("\"", start);
            if (end > start) {
                hostInfo.setHostname(json.substring(start, end));
            }
        }
        if (json.contains("mac")) {
            int start = json.indexOf("mac") + 6;
            int end = json.indexOf("\"", start);
            if (end > start) {
                hostInfo.setMac(json.substring(start, end));
            }
        }
        if (json.contains("ttl")) {
            int start = json.indexOf("ttl") + 5;
            int end = json.indexOf(",", start);
            if (end == -1) end = json.indexOf("}", start);
            if (end > start) {
                try {
                    hostInfo.setTtl(Integer.parseInt(json.substring(start, end).trim()));
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    private List<PortInfo> parsePortRangeResult(String result, String protocol) {
        List<PortInfo> portInfos = new ArrayList<>();
        if (result == null || result.trim().isEmpty()) {
            return portInfos;
        }
        try {
            // Rust 侧输出为 JSON 数组：[{"host":"x","port":22,"state":"open","service":"ssh"}]
            List<Map<String, Object>> list = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(result, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            if (list == null) {
                return portInfos;
            }
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
                portInfo.setState(state == null ? PortState.OPEN : parsePortState(state.toString()));
                Object service = item.get("service");
                portInfo.setServiceName(service == null ? "unknown" : service.toString());
                portInfos.add(portInfo);
            }
        } catch (Exception e) {
            log.warn("解析端口扫描结果失败: {}", e.getMessage());
        }
        return portInfos;
    }

    private PortState parsePortState(String state) {
        return switch (state.toLowerCase()) {
            case "open" -> PortState.OPEN;
            case "closed" -> PortState.CLOSED;
            case "filtered" -> PortState.FILTERED;
            default -> PortState.UNKNOWN;
        };
    }

    private List<HostInfo> parseHostListResult(String result) {
        List<HostInfo> hosts = new ArrayList<>();
        if (result == null || result.isEmpty()) {
            return hosts;
        }
        
        String[] lines = result.split("\n");
        for (String line : lines) {
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\|");
            if (parts.length >= 1) {
                HostInfo hostInfo = new HostInfo();
                hostInfo.setIp(parts[0]);
                hostInfo.setAlive(true);
                if (parts.length >= 2) {
                    hostInfo.setHostname(parts[1]);
                }
                if (parts.length >= 3) {
                    hostInfo.setMac(parts[2]);
                }
                if (parts.length >= 4) {
                    hostInfo.setLatency(Long.parseLong(parts[3]));
                }
                hosts.add(hostInfo);
            }
        }
        return hosts;
    }
}
