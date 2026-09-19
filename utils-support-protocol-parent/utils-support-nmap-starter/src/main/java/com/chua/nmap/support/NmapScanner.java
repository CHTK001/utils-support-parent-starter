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
     * @author CH
     * @since 4.0.0
     */
    class ScanResult {
        /**
         * 目标主机
        */
        private String host;
        /**
         * 主机信息
        */
        private HostInfo hostInfo;
        /**
         * 端口信息列表
        */
        private List<PortInfo> ports;
        /**
         * 扫描耗时（毫秒）
        */
        private long duration;
        /**
         * 扫描开始时间
        */
        private long startTime;
        /**
         * 扫描结束时间
        */
        private long endTime;
        /**
         * 总共扫描端口数
        */
        private int totalPorts;
        /**
         * 开放端口数
        */
        private int openPorts;

        /**
         * 获取主机。
         * @return 获取主机的结果
         */
        public String getHost() { return host; }
        /**
         * 设置主机。
         * @param host 主机
         */
        public void setHost(String host) { this.host = host; }
        /**
         * 获取主机信息。
         * @return 获取主机信息的结果
         */
        public HostInfo getHostInfo() { return hostInfo; }
        /**
         * 设置主机信息。
         * @param hostInfo 主机信息
         */
        public void setHostInfo(HostInfo hostInfo) { this.hostInfo = hostInfo; }
        /**
         * 获取端口。
         * @return 获取端口的结果
         */
        public List<PortInfo> getPorts() { return ports; }
        /**
         * 设置端口。
         * @param ports 端口
         */
        public void setPorts(List<PortInfo> ports) { this.ports = ports; }
        /**
         * 获取持续时间。
         * @return 获取持续时间的结果
         */
        public long getDuration() { return duration; }
        /**
         * 设置持续时间。
         * @param duration 持续时间
         */
        public void setDuration(long duration) { this.duration = duration; }
        /**
         * 获取启动时间。
         * @return 获取启动时间的结果
         */
        public long getStartTime() { return startTime; }
        /**
         * 设置启动时间。
         * @param startTime 启动时间
         */
        public void setStartTime(long startTime) { this.startTime = startTime; }
        /**
         * 获取结束时间。
         * @return 获取结束时间的结果
         */
        public long getEndTime() { return endTime; }
        /**
         * 设置结束时间。
         * @param endTime 结束时间
         */
        public void setEndTime(long endTime) { this.endTime = endTime; }
        /**
         * 获取total端口。
         * @return 获取total端口的结果
         */
        public int getTotalPorts() { return totalPorts; }
        /**
         * 设置total端口。
         * @param totalPorts total端口
         */
        public void setTotalPorts(int totalPorts) { this.totalPorts = totalPorts; }
        /**
         * 获取打开端口。
         * @return 获取打开端口的结果
         */
        public int getOpenPorts() { return openPorts; }
        /**
         * 设置打开端口。
         * @param openPorts 打开端口
         */
        public void setOpenPorts(int openPorts) { this.openPorts = openPorts; }
    }

    /**
     * 端口信息
     * @author CH
     * @since 4.0.0
     */
    class PortInfo {
        /**
         * 端口号
        */
        private int port;
        /**
         * 协议（TCP/UDP）
        */
        private String protocol;
        /**
         * 状态
        */
        private PortState state;
        /**
         * 服务名称
        */
        private String serviceName;
        /**
         * 服务版本
        */
        private String serviceVersion;
        /**
         * Banner信息
        */
        private String banner;
        /**
         * 响应时间（毫秒）
        */
        private long responseTime;

        /**
         * 获取端口。
         * @return 获取端口的结果
         */
        public int getPort() { return port; }
        /**
         * 设置端口。
         * @param port 端口
         */
        public void setPort(int port) { this.port = port; }
        /**
         * 获取协议。
         * @return 获取协议的结果
         */
        public String getProtocol() { return protocol; }
        /**
         * 设置协议。
         * @param protocol 协议
         */
        public void setProtocol(String protocol) { this.protocol = protocol; }
        /**
         * 获取状态。
         * @return 获取状态的结果
         */
        public PortState getState() { return state; }
        /**
         * 设置状态。
         * @param state 状态
         */
        public void setState(PortState state) { this.state = state; }
        /**
         * 获取服务名称。
         * @return 获取服务名称的结果
         */
        public String getServiceName() { return serviceName; }
        /**
         * 设置服务名称。
         * @param serviceName 服务名称
         */
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        /**
         * 获取服务版本。
         * @return 获取服务版本的结果
         */
        public String getServiceVersion() { return serviceVersion; }
        /**
         * 设置服务版本。
         * @param serviceVersion 服务版本
         */
        public void setServiceVersion(String serviceVersion) { this.serviceVersion = serviceVersion; }
        /**
         * 获取banner。
         * @return 获取banner的结果
         */
        public String getBanner() { return banner; }
        /**
         * 设置banner。
         * @param banner banner
         */
        public void setBanner(String banner) { this.banner = banner; }
        /**
         * 获取响应时间。
         * @return 获取响应时间的结果
         */
        public long getResponseTime() { return responseTime; }
        /**
         * 设置响应时间。
         * @param responseTime 响应时间
         */
        public void setResponseTime(long responseTime) { this.responseTime = responseTime; }

        @Override
        public String toString() {
            return String.format("%d/%s (%s) - %s", port, protocol, state, serviceName);
        }
    }

    /**
     * 端口状态
     * @author CH
     * @since 4.0.0
     */
    enum PortState {
        /**
         * 开放
        */
        OPEN,
        /**
         * 关闭
        */
        CLOSED,
        /**
         * 被过滤
        */
        FILTERED,
        /**
         * 未知
        */
        UNKNOWN
    }

    /**
     * 主机信息
     * @author CH
     * @since 4.0.0
     */
    class HostInfo {
        /**
         * IP地址
        */
        private String ip;
        /**
         * 主机名
        */
        private String hostname;
        /**
         * MAC地址
        */
        private String mac;
        /**
         * 厂商
        */
        private String vendor;
        /**
         * 是否存活
        */
        private boolean alive;
        /**
         * 延迟（毫秒）
        */
        private long latency;
        /**
         * TTL
        */
        private int ttl;

        /**
         * 获取ip。
         * @return 获取ip的结果
         */
        public String getIp() { return ip; }
        /**
         * 设置ip。
         * @param ip ip
         */
        public void setIp(String ip) { this.ip = ip; }
        /**
         * 获取hostname。
         * @return 获取hostname的结果
         */
        public String getHostname() { return hostname; }
        /**
         * 设置hostname。
         * @param hostname hostname
         */
        public void setHostname(String hostname) { this.hostname = hostname; }
        /**
         * 获取mac。
         * @return 获取mac的结果
         */
        public String getMac() { return mac; }
        /**
         * 设置mac。
         * @param mac mac
         */
        public void setMac(String mac) { this.mac = mac; }
        /**
         * 获取供应商。
         * @return 获取供应商的结果
         */
        public String getVendor() { return vendor; }
        /**
         * 设置供应商。
         * @param vendor 供应商
         */
        public void setVendor(String vendor) { this.vendor = vendor; }
        /**
         * 是否alive。
         * @return 是否alive的结果
         */
        public boolean isAlive() { return alive; }
        /**
         * 设置alive。
         * @param alive alive
         */
        public void setAlive(boolean alive) { this.alive = alive; }
        /**
         * 获取延迟。
         * @return 获取延迟的结果
         */
        public long getLatency() { return latency; }
        /**
         * 设置延迟。
         * @param latency 延迟
         */
        public void setLatency(long latency) { this.latency = latency; }
        /**
         * 获取ttl。
         * @return 获取ttl的结果
         */
        public int getTtl() { return ttl; }
        /**
         * 设置ttl。
         * @param ttl ttl
         */
        public void setTtl(int ttl) { this.ttl = ttl; }

        @Override
        public String toString() {
            return String.format("%s (%s) - %s", ip, hostname, alive ? "alive" : "down");
        }
    }

    /**
     * 服务信息
     * @author CH
     * @since 4.0.0
     */
    class ServiceInfo {
        /**
         * 端口
        */
        private int port;
        /**
         * 服务名称
        */
        private String name;
        /**
         * 产品名称
        */
        private String product;
        /**
         * 版本
        */
        private String version;
        /**
         * 额外信息
        */
        private String extraInfo;
        /**
         * CPE标识
        */
        private String cpe;
        /**
         * 置信度（0-100）
        */
        private int confidence;

        /**
         * 获取端口。
         * @return 获取端口的结果
         */
        public int getPort() { return port; }
        /**
         * 设置端口。
         * @param port 端口
         */
        public void setPort(int port) { this.port = port; }
        /**
         * 获取名称。
         * @return 获取名称的结果
         */
        public String getName() { return name; }
        /**
         * 设置名称。
         * @param name 名称
         */
        public void setName(String name) { this.name = name; }
        /**
         * 获取product。
         * @return 获取product的结果
         */
        public String getProduct() { return product; }
        /**
         * 设置product。
         * @param product product
         */
        public void setProduct(String product) { this.product = product; }
        /**
         * 获取版本。
         * @return 获取版本的结果
         */
        public String getVersion() { return version; }
        /**
         * 设置版本。
         * @param version 版本
         */
        public void setVersion(String version) { this.version = version; }
        /**
         * 获取extra信息。
         * @return 获取extra信息的结果
         */
        public String getExtraInfo() { return extraInfo; }
        /**
         * 设置extra信息。
         * @param extraInfo extra信息
         */
        public void setExtraInfo(String extraInfo) { this.extraInfo = extraInfo; }
        /**
         * 获取cpe。
         * @return 获取cpe的结果
         */
        public String getCpe() { return cpe; }
        /**
         * 设置cpe。
         * @param cpe cpe
         */
        public void setCpe(String cpe) { this.cpe = cpe; }
        /**
         * 获取信心。
         * @return 获取信心的结果
         */
        public int getConfidence() { return confidence; }
        /**
         * 设置信心。
         * @param confidence 信心
         */
        public void setConfidence(int confidence) { this.confidence = confidence; }
    }

    /**
     * 操作系统信息
     * @author CH
     * @since 4.0.0
     */
    class OsInfo {
        /**
         * 操作系统名称
        */
        private String name;
        /**
         * 操作系统家族
        */
        private String family;
        /**
         * 版本
        */
        private String version;
        /**
         * 厂商
        */
        private String vendor;
        /**
         * 设备类型
        */
        private String deviceType;
        /**
         * 准确度（0-100）
        */
        private int accuracy;
        /**
         * CPE标识列表
        */
        private List<String> cpes;

        /**
         * 获取名称。
         * @return 获取名称的结果
         */
        public String getName() { return name; }
        /**
         * 设置名称。
         * @param name 名称
         */
        public void setName(String name) { this.name = name; }
        /**
         * 获取family。
         * @return 获取family的结果
         */
        public String getFamily() { return family; }
        /**
         * 设置family。
         * @param family family
         */
        public void setFamily(String family) { this.family = family; }
        /**
         * 获取版本。
         * @return 获取版本的结果
         */
        public String getVersion() { return version; }
        /**
         * 设置版本。
         * @param version 版本
         */
        public void setVersion(String version) { this.version = version; }
        /**
         * 获取供应商。
         * @return 获取供应商的结果
         */
        public String getVendor() { return vendor; }
        /**
         * 设置供应商。
         * @param vendor 供应商
         */
        public void setVendor(String vendor) { this.vendor = vendor; }
        /**
         * 获取device类型。
         * @return 获取device类型的结果
         */
        public String getDeviceType() { return deviceType; }
        /**
         * 设置device类型。
         * @param deviceType device类型
         */
        public void setDeviceType(String deviceType) { this.deviceType = deviceType; }
        /**
         * 获取准确。
         * @return 获取准确的结果
         */
        public int getAccuracy() { return accuracy; }
        /**
         * 设置准确。
         * @param accuracy 准确
         */
        public void setAccuracy(int accuracy) { this.accuracy = accuracy; }
        /**
         * 获取cpes。
         * @return 获取cpes的结果
         */
        public List<String> getCpes() { return cpes; }
        /**
         * 设置cpes。
         * @param cpes cpes
         */
        public void setCpes(List<String> cpes) { this.cpes = cpes; }
    }

    /**
     * 扫描进度
     * @author CH
     * @since 4.0.0
     */
    class ScanProgress {
        /**
         * 当前扫描端口
        */
        private int currentPort;
        /**
         * 已扫描端口数
        */
        private int scannedPorts;
        /**
         * 总端口数
        */
        private int totalPorts;
        /**
         * 进度百分比
        */
        private double progress;
        /**
         * 已发现开放端口数
        */
        private int openPorts;

        /**
         * 获取当前端口。
         * @return 获取当前端口的结果
         */
        public int getCurrentPort() { return currentPort; }
        /**
         * 设置当前端口。
         * @param currentPort 当前端口
         */
        public void setCurrentPort(int currentPort) { this.currentPort = currentPort; }
        /**
         * 获取scanned端口。
         * @return 获取scanned端口的结果
         */
        public int getScannedPorts() { return scannedPorts; }
        /**
         * 设置scanned端口。
         * @param scannedPorts scanned端口
         */
        public void setScannedPorts(int scannedPorts) { this.scannedPorts = scannedPorts; }
        /**
         * 获取total端口。
         * @return 获取total端口的结果
         */
        public int getTotalPorts() { return totalPorts; }
        /**
         * 设置total端口。
         * @param totalPorts total端口
         */
        public void setTotalPorts(int totalPorts) { this.totalPorts = totalPorts; }
        /**
         * 获取进步。
         * @return 获取进步的结果
         */
        public double getProgress() { return progress; }
        /**
         * 设置进步。
         * @param progress 进步
         */
        public void setProgress(double progress) { this.progress = progress; }
        /**
         * 获取打开端口。
         * @return 获取打开端口的结果
         */
        public int getOpenPorts() { return openPorts; }
        /**
         * 设置打开端口。
         * @param openPorts 打开端口
         */
        public void setOpenPorts(int openPorts) { this.openPorts = openPorts; }
    }

    /**
     * 扫描选项
     * @author CH
     * @since 4.0.0
     */
    class ScanOptions {
        /**
         * 超时时间（毫秒）
        */
        private int timeout = 1000;
        /**
         * 并发数
        */
        private int concurrency = 100;
        /**
         * 重试次数
        */
        private int retries = 1;
        /**
         * 扫描延迟（毫秒）
        */
        private int delay = 0;
        /**
         * 是否进行服务检测
        */
        private boolean serviceDetection = false;
        /**
         * 是否进行OS检测
        */
        private boolean osDetection = false;
        /**
         * 扫描类型
        */
        private ScanType scanType = ScanType.TCP_CONNECT;

        /**
         * 获取超时。
         * @return 获取超时的结果
         */
        public int getTimeout() { return timeout; }
        /**
         * 设置超时。
         * @param timeout 超时
         * @return 设置超时的结果
         */
        public ScanOptions setTimeout(int timeout) {
            this.timeout = timeout;
            return this;
        }
        /**
         * 获取concurrency。
         * @return 获取concurrency的结果
         */
        public int getConcurrency() { return concurrency; }
        /**
         * 设置concurrency。
         * @param concurrency concurrency
         * @return 设置concurrency的结果
         */
        public ScanOptions setConcurrency(int concurrency) {
            this.concurrency = concurrency;
            return this;
        }
        /**
         * 获取重试。
         * @return 获取重试的结果
         */
        public int getRetries() { return retries; }
        /**
         * 设置重试。
         * @param retries 重试
         * @return 设置重试的结果
         */
        public ScanOptions setRetries(int retries) {
            this.retries = retries;
            return this;
        }
        /**
         * 获取延迟。
         * @return 获取延迟的结果
         */
        public int getDelay() { return delay; }
        /**
         * 设置延迟。
         * @param delay 延迟
         * @return 设置延迟的结果
         */
        public ScanOptions setDelay(int delay) {
            this.delay = delay;
            return this;
        }
        /**
         * 是否服务detection。
         * @return 是否服务detection的结果
         */
        public boolean isServiceDetection() { return serviceDetection; }
        /**
         * 设置服务detection。
         * @param serviceDetection 服务detection
         * @return 设置服务detection的结果
         */
        public ScanOptions setServiceDetection(boolean serviceDetection) {
            this.serviceDetection = serviceDetection;
            return this;
        }
        /**
         * 是否osdetection。
         * @return 是否osdetection的结果
         */
        public boolean isOsDetection() { return osDetection; }
        /**
         * 设置osdetection。
         * @param osDetection osdetection
         * @return 设置osdetection的结果
         */
        public ScanOptions setOsDetection(boolean osDetection) {
            this.osDetection = osDetection;
            return this;
        }
        /**
         * 获取扫描类型。
         * @return 获取扫描类型的结果
         */
        public ScanType getScanType() { return scanType; }
        /**
         * 设置扫描类型。
         * @param scanType 扫描类型
         * @return 设置扫描类型的结果
         */
        public ScanOptions setScanType(ScanType scanType) {
            this.scanType = scanType;
            return this;
        }

        /**
         * 默认。
         * @return 默认的结果
         */
        public static ScanOptions defaults() { return new ScanOptions(); }
        /**
         * fast。
         * @return fast的结果
         */
        public static ScanOptions fast() { return new ScanOptions().setTimeout(500).setConcurrency(500); }
        /**
         * thorough。
         * @return thorough的结果
         */
        public static ScanOptions thorough() { return new ScanOptions().setTimeout(3000).setRetries(3).setServiceDetection(true); }
    }

    /**
     * 扫描类型
     * @author CH
     * @since 4.0.0
     */
    enum ScanType {
        /**
         * TCP连接扫描
        */
        TCP_CONNECT,
        /**
         * TCP SYN扫描（需要根权限）
        */
        TCP_SYN,
        /**
         * UDP扫描
        */
        UDP,
        /**
         * Ping扫描
        */
        PING
    }
}
