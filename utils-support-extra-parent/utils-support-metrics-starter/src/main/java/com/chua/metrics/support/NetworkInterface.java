package com.chua.metrics.support;

import lombok.Data;

/**
 * 网络接口指标数据模型。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class NetworkInterface {
    /**
     * 网络接口名称（如 eth0、wlan0 等）
     */
    private String name;

    /**
     * 接收字节数（自启动以来累计）
     */
    private long receivedBytes;

    /**
     * 发送字节数（自启动以来累计）
     */
    private long transmittedBytes;

    /**
     * 接收数据包数量（自启动以来累计）
     */
    private long receivedPackets;

    /**
     * 发送数据包数量（自启动以来累计）
     */
    private long transmittedPackets;

    /**
     * 接收错误数量（自启动以来累计）
     */
    private int errorsIn;

    /**
     * 发送错误数量（自启动以来累计）
     */
    private int errorsOut;
}
