package com.chua.common.support.nativeapi;

/**
 * Nmap 扫描原生库 SPI。
 *
 * <p>由 {@code utils-support-native-nmap} 提供实现。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public interface NmapSpi extends NativeService {

    /**
     * 原生库是否已加载。
     *
     * @return 已加载返回 {@code true}
     */
    boolean isLoaded();

    @Override
    default boolean available() {
        return isLoaded();
    }

    /**
     * 确保原生库已加载，未加载时抛异常。
     */
    void ensureLoaded();

    /**
     * 扫描单个 TCP 端口。
     *
     * @param host    目标主机
     * @param port    端口
     * @param timeout 超时（毫秒）
     * @return 0 表示开放
     */
    int scanSingleTcpPort(String host, int port, int timeout);

    /**
     * 扫描 TCP 端口范围。
     *
     * @param host        目标主机
     * @param startPort   起始端口
     * @param endPort     结束端口
     * @param timeout     超时（毫秒）
     * @param concurrency 并发数
     * @return JSON 扫描结果
     */
    String scanTcpPortRange(String host, int startPort, int endPort, int timeout, int concurrency);

    /**
     * Ping 主机。
     *
     * @param host    目标主机
     * @param timeout 超时（毫秒）
     * @return JSON 结果
     */
    String pingHost(String host, int timeout);

    /**
     * 扫描子网。
     *
     * @param subnet      子网
     * @param timeout     超时（毫秒）
     * @param concurrency 并发数
     * @return JSON 结果
     */
    String scanSubnet(String subnet, int timeout, int concurrency);

    /**
     * 识别服务。
     *
     * @param host    目标主机
     * @param port    端口
     * @param timeout 超时（毫秒）
     * @return JSON 结果
     */
    String detectService(String host, int port, int timeout);

    /**
     * 原生库版本。
     *
     * @return 版本串
     */
    String getVersion();
}
