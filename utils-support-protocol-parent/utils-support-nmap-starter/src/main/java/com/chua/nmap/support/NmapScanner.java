package com.chua.nmap.support;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 网络扫描器接口
 * <p>
 * 提供端口扫描、主机发现、服务识别等网络扫描功能。
 * 支持同步和异步操作。
 * </p>
 *
 * @author CH
 * @since 2024/12/30
 */
public interface NmapScanner {

    // ==================== 端口扫描 ====================

    /**
     * TCP端口扫描
     *
     * @param host  目标主机
     * @param ports 端口列表
     * @return 扫描结果
     */
    ScanResult scanTcpPorts(String host, int[] ports);

    /**
     * TCP端口扫描（端口范围）
     *
     * @param host      目标主机
     * @param startPort 起始端口
     * @param endPort   结束端口
     * @return 扫描结果
     */
    ScanResult scanTcpPorts(String host, int startPort, int endPort);

    /**
     * UDP端口扫描
     *
     * @param host  目标主机
     * @param ports 端口列表
     * @return 扫描结果
     */
    ScanResult scanUdpPorts(String host, int[] ports);

    /**
     * 扫描常用端口
     *
     * @param host 目标主机
     * @return 扫描结果
     */
    ScanResult scanCommonPorts(String host);

    /**
     * 全端口扫描
     *
     * @param host 目标主机
     * @return 扫描结果
     */
    ScanResult scanAllPorts(String host);

    // ==================== 主机发现 ====================

    /**
     * Ping扫描（检查主机是否存活）
     *
     * @param host 目标主机
     * @return 主机信息
     */
    HostInfo ping(String host);

    /**
     * Ping扫描（带超时）
     *
     * @param host      目标主机
     * @param timeoutMs 超时时间（毫秒）
     * @return 主机信息
     */
    HostInfo ping(String host, int timeoutMs);

    /**
     * 扫描网段（主机发现）
     *
     * @param subnet 子网（如 192.168.1.0/24）
     * @return 存活主机列表
     */
    List<HostInfo> scanSubnet(String subnet);

    /**
     * 扫描IP范围
     *
     * @param startIp 起始IP
     * @param endIp   结束IP
     * @return 存活主机列表
     */
    List<HostInfo> scanIpRange(String startIp, String endIp);

    // ==================== 服务识别 ====================

    /**
     * 服务版本检测
     *
     * @param host 目标主机
     * @param port 端口
     * @return 服务信息
     */
    ServiceInfo detectService(String host, int port);

    /**
     * 批量服务版本检测
     *
     * @param host  目标主机
     * @param ports 端口列表
     * @return 服务信息列表
     */
    List<ServiceInfo> detectServices(String host, int[] ports);

    // ==================== 系统指纹 ====================

    /**
     * 操作系统检测
     *
     * @param host 目标主机
     * @return 操作系统信息
     */
    OsInfo detectOs(String host);

    // ==================== 异步操作 ====================

    /**
     * 异步TCP端口扫描
     *
     * @param host  目标主机
     * @param ports 端口列表
     * @return 异步结果
     */
    CompletableFuture<ScanResult> scanTcpPortsAsync(String host, int[] ports);

    /**
     * 异步网段扫描
     *
     * @param subnet 子网
     * @return 异步结果
     */
    CompletableFuture<List<HostInfo>> scanSubnetAsync(String subnet);

    /**
     * 异步扫描（带进度回调）
     *
     * @param host             目标主机
     * @param ports            端口列表
     * @param progressCallback 进度回调
     * @return 异步结果
     */
    CompletableFuture<ScanResult> scanWithProgress(String host, int[] ports, 
                                                    Consumer<ScanProgress> progressCallback);

    // ==================== 配置 ====================

    /**
     * 设置扫描选项
     *
     * @param options 扫描选项
     * @return this
     */
    NmapScanner setOptions(ScanOptions options);

    /**
     * 获取扫描选项
     *
     * @return 当前扫描选项
     */
    ScanOptions getOptions();

    // ==================== 支持类 ====================

    /**
     * 扫描结果
     */
    class ScanResult {
        /** 目标主机 */
        private String host;
        /** 主机信息 */
        private HostInfo hostInfo;
        /** 端口信息列表 */
        private List<PortInfo> ports;
        /** 扫描耗时（毫秒） */
        private long duration;
        /** 扫描开始时间 */
        private long startTime;
        /** 扫描结束时间 */
        private long endTime;
        /** 总共扫描端口数 */
        private int totalPorts;
        /** 开放端口数 */
        private int openPorts;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public HostInfo getHostInfo() { return hostInfo; }
        public void setHostInfo(HostInfo hostInfo) { this.hostInfo = hostInfo; }
        public List<PortInfo> getPorts() { return ports; }
        public void setPorts(List<PortInfo> ports) { this.ports = ports; }
        public long getDuration() { return duration; }
        public void setDuration(long duration) { this.duration = duration; }
        public long getStartTime() { return startTime; }
        public void setStartTime(long startTime) { this.startTime = startTime; }
        public long getEndTime() { return endTime; }
        public void setEndTime(long endTime) { this.endTime = endTime; }
        public int getTotalPorts() { return totalPorts; }
        public void setTotalPorts(int totalPorts) { this.totalPorts = totalPorts; }
        public int getOpenPorts() { return openPorts; }
        public void setOpenPorts(int openPorts) { this.openPorts = openPorts; }
    }

    /**
     * 端口信息
     */
    class PortInfo {
        /** 端口号 */
        private int port;
        /** 协议（TCP/UDP） */
        private String protocol;
        /** 状态 */
        private PortState state;
        /** 服务名称 */
        private String serviceName;
        /** 服务版本 */
        private String serviceVersion;
        /** Banner信息 */
        private String banner;
        /** 响应时间（毫秒） */
        private long responseTime;

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getProtocol() { return protocol; }
        public void setProtocol(String protocol) { this.protocol = protocol; }
        public PortState getState() { return state; }
        public void setState(PortState state) { this.state = state; }
        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        public String getServiceVersion() { return serviceVersion; }
        public void setServiceVersion(String serviceVersion) { this.serviceVersion = serviceVersion; }
        public String getBanner() { return banner; }
        public void setBanner(String banner) { this.banner = banner; }
        public long getResponseTime() { return responseTime; }
        public void setResponseTime(long responseTime) { this.responseTime = responseTime; }

        @Override
        public String toString() {
            return String.format("%d/%s (%s) - %s", port, protocol, state, serviceName);
        }
    }

    /**
     * 端口状态
     */
    enum PortState {
        /** 开放 */
        OPEN,
        /** 关闭 */
        CLOSED,
        /** 被过滤 */
        FILTERED,
        /** 未知 */
        UNKNOWN
    }

    /**
     * 主机信息
     */
    class HostInfo {
        /** IP地址 */
        private String ip;
        /** 主机名 */
        private String hostname;
        /** MAC地址 */
        private String mac;
        /** 厂商 */
        private String vendor;
        /** 是否存活 */
        private boolean alive;
        /** 延迟（毫秒） */
        private long latency;
        /** TTL */
        private int ttl;

        public String getIp() { return ip; }
        public void setIp(String ip) { this.ip = ip; }
        public String getHostname() { return hostname; }
        public void setHostname(String hostname) { this.hostname = hostname; }
        public String getMac() { return mac; }
        public void setMac(String mac) { this.mac = mac; }
        public String getVendor() { return vendor; }
        public void setVendor(String vendor) { this.vendor = vendor; }
        public boolean isAlive() { return alive; }
        public void setAlive(boolean alive) { this.alive = alive; }
        public long getLatency() { return latency; }
        public void setLatency(long latency) { this.latency = latency; }
        public int getTtl() { return ttl; }
        public void setTtl(int ttl) { this.ttl = ttl; }

        @Override
        public String toString() {
            return String.format("%s (%s) - %s", ip, hostname, alive ? "alive" : "down");
        }
    }

    /**
     * 服务信息
     */
    class ServiceInfo {
        /** 端口 */
        private int port;
        /** 服务名称 */
        private String name;
        /** 产品名称 */
        private String product;
        /** 版本 */
        private String version;
        /** 额外信息 */
        private String extraInfo;
        /** CPE标识 */
        private String cpe;
        /** 置信度（0-100） */
        private int confidence;

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getProduct() { return product; }
        public void setProduct(String product) { this.product = product; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public String getExtraInfo() { return extraInfo; }
        public void setExtraInfo(String extraInfo) { this.extraInfo = extraInfo; }
        public String getCpe() { return cpe; }
        public void setCpe(String cpe) { this.cpe = cpe; }
        public int getConfidence() { return confidence; }
        public void setConfidence(int confidence) { this.confidence = confidence; }
    }

    /**
     * 操作系统信息
     */
    class OsInfo {
        /** 操作系统名称 */
        private String name;
        /** 操作系统家族 */
        private String family;
        /** 版本 */
        private String version;
        /** 厂商 */
        private String vendor;
        /** 设备类型 */
        private String deviceType;
        /** 准确度（0-100） */
        private int accuracy;
        /** CPE标识列表 */
        private List<String> cpes;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getFamily() { return family; }
        public void setFamily(String family) { this.family = family; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public String getVendor() { return vendor; }
        public void setVendor(String vendor) { this.vendor = vendor; }
        public String getDeviceType() { return deviceType; }
        public void setDeviceType(String deviceType) { this.deviceType = deviceType; }
        public int getAccuracy() { return accuracy; }
        public void setAccuracy(int accuracy) { this.accuracy = accuracy; }
        public List<String> getCpes() { return cpes; }
        public void setCpes(List<String> cpes) { this.cpes = cpes; }
    }

    /**
     * 扫描进度
     */
    class ScanProgress {
        /** 当前扫描端口 */
        private int currentPort;
        /** 已扫描端口数 */
        private int scannedPorts;
        /** 总端口数 */
        private int totalPorts;
        /** 进度百分比 */
        private double progress;
        /** 已发现开放端口数 */
        private int openPorts;

        public int getCurrentPort() { return currentPort; }
        public void setCurrentPort(int currentPort) { this.currentPort = currentPort; }
        public int getScannedPorts() { return scannedPorts; }
        public void setScannedPorts(int scannedPorts) { this.scannedPorts = scannedPorts; }
        public int getTotalPorts() { return totalPorts; }
        public void setTotalPorts(int totalPorts) { this.totalPorts = totalPorts; }
        public double getProgress() { return progress; }
        public void setProgress(double progress) { this.progress = progress; }
        public int getOpenPorts() { return openPorts; }
        public void setOpenPorts(int openPorts) { this.openPorts = openPorts; }
    }

    /**
     * 扫描选项
     */
    class ScanOptions {
        /** 超时时间（毫秒） */
        private int timeout = 1000;
        /** 并发数 */
        private int concurrency = 100;
        /** 重试次数 */
        private int retries = 1;
        /** 扫描延迟（毫秒） */
        private int delay = 0;
        /** 是否进行服务检测 */
        private boolean serviceDetection = false;
        /** 是否进行OS检测 */
        private boolean osDetection = false;
        /** 扫描类型 */
        private ScanType scanType = ScanType.TCP_CONNECT;

        public int getTimeout() { return timeout; }
        public ScanOptions setTimeout(int timeout) { this.timeout = timeout; return this; }
        public int getConcurrency() { return concurrency; }
        public ScanOptions setConcurrency(int concurrency) { this.concurrency = concurrency; return this; }
        public int getRetries() { return retries; }
        public ScanOptions setRetries(int retries) { this.retries = retries; return this; }
        public int getDelay() { return delay; }
        public ScanOptions setDelay(int delay) { this.delay = delay; return this; }
        public boolean isServiceDetection() { return serviceDetection; }
        public ScanOptions setServiceDetection(boolean serviceDetection) { this.serviceDetection = serviceDetection; return this; }
        public boolean isOsDetection() { return osDetection; }
        public ScanOptions setOsDetection(boolean osDetection) { this.osDetection = osDetection; return this; }
        public ScanType getScanType() { return scanType; }
        public ScanOptions setScanType(ScanType scanType) { this.scanType = scanType; return this; }

        public static ScanOptions defaults() { return new ScanOptions(); }
        public static ScanOptions fast() { return new ScanOptions().setTimeout(500).setConcurrency(500); }
        public static ScanOptions thorough() { return new ScanOptions().setTimeout(3000).setRetries(3).setServiceDetection(true); }
    }

    /**
     * 扫描类型
     */
    enum ScanType {
        /** TCP连接扫描 */
        TCP_CONNECT,
        /** TCP SYN扫描（需要root权限） */
        TCP_SYN,
        /** UDP扫描 */
        UDP,
        /** Ping扫描 */
        PING
    }
}
