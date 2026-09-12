package com.chua.nmap.support.bridge;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import com.chua.common.support.os.Platform;

/**
 * Rust Nmap原生桥接类
 * <p>
 * 通过JNI调用Rust编写的网络扫描库，提供高性能的网络扫描能力。
 * </p>
 *
 * @author CH
 * @since 2024/12/30
 */
public class RustNmapBridge {

    private static final String LIBRARY_NAME = "rust_nmap";
    private static volatile boolean loaded = false;
    private static volatile Throwable loadError = null;

    static {
        try {
            Platform.loadNativeLibrary(LIBRARY_NAME);
            loaded = true;
        } catch (Throwable e) {
            loadError = e;
            loaded = false;
        }
    }

    /**
     * 检查原生库是否已加载
     *
     * @return 是否已加载
     */
    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * 获取加载错误
     *
     * @return 加载错误，如果没有错误则返回null
     */
    public static Throwable getLoadError() {
        return loadError;
    }

    /**
     * 确保原生库已加载
     *
     * @throws UnsupportedOperationException 如果原生库未加载
     */
    public static void ensureLoaded() {
        if (!loaded) {
            throw new UnsupportedOperationException(
                    "Rust Nmap native library not loaded: " +
                            (loadError != null ? loadError.getMessage() : "unknown error"));
        }
    }

    // ==================== 端口扫描 ====================

    /**
     * TCP端口扫描
     *
     * @param host        目标主机
     * @param ports       端口列表
     * @param timeout     超时时间（毫秒）
     * @param concurrency 并发数
     * @return JSON格式的扫描结果
     */
    public static native String scanTcpPorts(String host, int[] ports, int timeout, int concurrency);

    /**
     * TCP端口范围扫描
     *
     * @param host        目标主机
     * @param startPort   起始端口
     * @param endPort     结束端口
     * @param timeout     超时时间（毫秒）
     * @param concurrency 并发数
     * @return JSON格式的扫描结果
     */
    public static native String scanTcpPortRange(String host, int startPort, int endPort,
                                                  int timeout, int concurrency);

    /**
     * UDP端口扫描
     *
     * @param host        目标主机
     * @param ports       端口列表
     * @param timeout     超时时间（毫秒）
     * @param concurrency 并发数
     * @return JSON格式的扫描结果
     */
    public static native String scanUdpPorts(String host, int[] ports, int timeout, int concurrency);

    /**
     * 扫描单个TCP端口
     *
     * @param host    目标主机
     * @param port    端口号
     * @param timeout 超时时间（毫秒）
     * @return 端口状态（0=open, 1=closed, 2=filtered, -1=error）
     */
    public static native int scanSingleTcpPort(String host, int port, int timeout);

    // ==================== 主机发现 ====================

    /**
     * Ping主机
     *
     * @param host    目标主机
     * @param timeout 超时时间（毫秒）
     * @return JSON格式的主机信息
     */
    public static native String pingHost(String host, int timeout);

    /**
     * 扫描子网
     *
     * @param subnet      子网（如 192.168.1.0/24）
     * @param timeout     超时时间（毫秒）
     * @param concurrency 并发数
     * @return JSON格式的存活主机列表
     */
    public static native String scanSubnet(String subnet, int timeout, int concurrency);

    /**
     * 扫描IP范围
     *
     * @param startIp     起始IP
     * @param endIp       结束IP
     * @param timeout     超时时间（毫秒）
     * @param concurrency 并发数
     * @return JSON格式的存活主机列表
     */
    public static native String scanIpRange(String startIp, String endIp, int timeout, int concurrency);

    // ==================== 服务识别 ====================

    /**
     * 检测服务版本
     *
     * @param host    目标主机
     * @param port    端口号
     * @param timeout 超时时间（毫秒）
     * @return JSON格式的服务信息
     */
    public static native String detectService(String host, int port, int timeout);

    /**
     * 获取Banner
     *
     * @param host    目标主机
     * @param port    端口号
     * @param timeout 超时时间（毫秒）
     * @return Banner字符串
     */
    public static native String getBanner(String host, int port, int timeout);

    // ==================== 系统指纹 ====================

    /**
     * 检测操作系统
     *
     * @param host    目标主机
     * @param timeout 超时时间（毫秒）
     * @return JSON格式的OS信息
     */
    public static native String detectOs(String host, int timeout);

    /**
     * 获取TTL
     *
     * @param host    目标主机
     * @param timeout 超时时间（毫秒）
     * @return TTL值，-1表示错误
     */
    public static native int getTtl(String host, int timeout);

    // ==================== 工具方法 ====================

    /**
     * 解析主机名
     *
     * @param hostname 主机名
     * @return IP地址，null表示解析失败
     */
    public static native String resolveHostname(String hostname);

    /**
     * 反向DNS查询
     *
     * @param ip IP地址
     * @return 主机名，null表示查询失败
     */
    public static native String reverseDns(String ip);

    /**
     * 检查IP地址是否有效
     *
     * @param ip IP地址
     * @return 是否有效
     */
    public static native boolean isValidIp(String ip);

    /**
     * 检查子网格式是否有效
     *
     * @param subnet 子网（CIDR格式）
     * @return 是否有效
     */
    public static native boolean isValidSubnet(String subnet);

    /**
     * 获取本机IP地址列表
     *
     * @return JSON格式的IP列表
     */
    public static native String getLocalIps();

    /**
     * 获取本机MAC地址
     *
     * @return MAC地址，null表示获取失败
     */
    public static native String getLocalMac();

    // ==================== 版本信息 ====================

    /**
     * 获取Rust Nmap库版本
     *
     * @return 版本字符串
     */
    public static native String getVersion();
}
