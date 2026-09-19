package com.chua.protocol.support.network.net;

/**
 * 网络地址值对象，描述主机与端口。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class NetAddress {

    /**
     * 主机地址
    */
    private String host;

    /**
     * 端口号
    */
    private int port;

    /**
     * 默认构造
    */
    public NetAddress() {
    }

    /**
     * 构造网络地址。
     *
     * @param host 主机地址
     * @param port 端口号
     */
    public NetAddress(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * 从 主机:端口 字符串解析网络地址。
     *
     * @param address 格式 {@code host:port} 或仅 {@code host}（端口默认 0）
     * @return 解析后的 net地址
     */
    public static NetAddress of(String address) {
        if (address == null || address.isBlank()) {
            return new NetAddress();
        }
        int idx = address.lastIndexOf(':');
        if (idx > 0) {
            String host = address.substring(0, idx);
            int port = Integer.parseInt(address.substring(idx + 1));
            return new NetAddress(host, port);
        }
        return new NetAddress(address, 0);
    }

    /**
     * 获取主机地址
     *
     * @return 获取主机的结果
     */
    public String getHost() {
        return host;
    }

    /**
     * 设置主机地址
     *
     * @param host 主机
     */
    public void setHost(String host) {
        this.host = host;
    }

    /**
     * 获取端口号
     *
     * @return 获取端口的结果
     */
    public int getPort() {
        return port;
    }

    /**
     * 设置端口号
     *
     * @param port 端口
     */
    public void setPort(int port) {
        this.port = port;
    }
}
