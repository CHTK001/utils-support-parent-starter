package com.chua.network.support.tshark.stream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * TCP 流的四元组标识。
 *
 * <p>一条 TCP 连接有两个方向，单看五元组无法把两个方向归并为"同一条会话"。
 * 本类提供 {@link #canonical()} 归一化形式：按端口号大小固定端点顺序，
 * 使 A→B 与 B→A 两种方向落到同一个 key 上，从而把双向流量聚成一条流。</p>
 *
 * <p>归一化以端口号而非 IP 为准是有意为之：同一条连接的两端 IP 与端口
 * 共同确定两个端点，端口对调后两侧的相对大小关系会反转，
 * 只有固定"小端口在前"才能让双向报文命中同一个 key。</p>
 *
 * @param sourceIp        源 IP
 * @param sourcePort      源端口
 * @param destinationIp   目的 IP
 * @param destinationPort 目的端口
 * @author CH
 * @since 4.0.0.42
 */
public record TcpStreamKey(String sourceIp, Integer sourcePort,
                           String destinationIp, Integer destinationPort) {

    /**
     * 构造键。
     *
     * @param sourceIp        源 IP
     * @param sourcePort      源端口
     * @param destinationIp   目的 IP
     * @param destinationPort 目的端口
     */
    public TcpStreamKey {
        if (sourceIp == null || destinationIp == null) {
            throw new IllegalArgumentException("TCP 流的源与目的 IP 均不可为空");
        }
    }

    /**
     * 取端点顺序归一化后的键。
     *
     * @return 归一化键，当前实例本身已归一化时返回自身
     */
    @Nonnull
    public TcpStreamKey canonical() {
        if (isCanonical()) {
            return this;
        }
        return new TcpStreamKey(destinationIp, destinationPort, sourceIp, sourcePort);
    }

    /**
     * 当前端点顺序是否已归一化。
     *
     * <p>端口缺失时按"源在前"处理，保证键仍然确定。</p>
     *
     * @return 已归一化返回 true
     */
    private boolean isCanonical() {
        if (sourcePort == null || destinationPort == null) {
            return true;
        }
        return sourcePort <= destinationPort;
    }

    /**
     * 判断给定报文是否属于本流（不分方向）。
     *
     * @param other 报文的键
     * @return 属于同一条流返回 true
     */
    public boolean sameStream(@Nullable TcpStreamKey other) {
        return other != null && canonical().equals(other.canonical());
    }

    /**
     * 取反向的键。
     *
     * @return 源与目的互换后的键
     */
    @Nonnull
    public TcpStreamKey reversed() {
        return new TcpStreamKey(destinationIp, destinationPort, sourceIp, sourcePort);
    }

    @Override
    public String toString() {
        return sourceIp + ":" + sourcePort + "->" + destinationIp + ":" + destinationPort;
    }
}
