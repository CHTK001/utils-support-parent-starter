package com.chua.oshi.support;

import lombok.Data;

/**
 * 网络接口信息实体类。
 *
 * @author CH
 */
@Data
public class Network {

    /**
     * 网络接口名称（如 eth0, wlan0）。
     */
    private String name;

    /**
     * 网络接口显示名称。
     */
    private String displayName;

    /**
     * MAC 地址。
     */
    private String mac;

    /**
     * IPv4 地址数组。
     */
    private String[] ipv4;

    /**
     * IPv6 地址数组。
     */
    private String[] ipv6;

    /**
     * 子网掩码数组。
     */
    private String[] subnetMasks;

    /**
     * IPv6 前缀长度数组。
     */
    private Short[] prefixLengths;

    /**
     * 接口别名。
     */
    private String ifAlias;

    /**
     * 接口索引号。
     */
    private int index;

    /**
     * MTU（最大传输单元）。
     */
    private long mtu;

    /**
     * 接收字节数。
     */
    private long receiveBytes;

    /**
     * 发送字节数。
     */
    private long transmitBytes;

    /**
     * 接收包数。
     */
    private long packetsRecv;

    /**
     * 发送包数。
     */
    private long packetsSent;

    /**
     * 接收错误数。
     */
    private long inErrors;

    /**
     * 发送错误数。
     */
    private long outErrors;

    /**
     * 接收丢包数。
     */
    private long inDrops;

    /**
     * 碰撞数。
     */
    private long collisions;

    /**
     * 网络速度（bps）。
     */
    private long speed;

    /**
     * 最后更新时间戳。
     */
    private long timeStamp;

    /**
     * 操作状态（UP / DOWN / UNKNOWN）。
     */
    private String ifOperStatus;

    /**
     * 接口类型（以太网/WiFi/虚拟等）。
     */
    private int ifType;

    /**
     * 是否有物理连接器。
     */
    private boolean connectorPresent;
}
