package com.chua.network.support.tshark;

/**
 * 数据包的链路层与传输层元数据，供流重组与会话聚合使用。
 *
 * <p>单包协议还原（{@link com.chua.common.support.network.protocol.ProtocolRestorer}）
 * 只需要一帧的载荷字节即可工作，但"跨包"的两项能力必须依赖这些字段：</p>
 * <ul>
 *   <li><b>TCP 流重组</b> — 依赖 {@link #streamId()} 归并同一条连接、
 *       {@link #tcpSeq()} 定位字节在流中的位置并识别重传与乱序。</li>
 *   <li><b>会话聚合</b> — 依赖 {@link #epochMillis()} 确定时序、
 *       {@link #tcpAck()} 配对请求与响应。</li>
 * </ul>
 *
 * <p>所有字段均可为 {@code null}：tshark 在某些层缺失时不会输出对应字段
 * （例如 ICMP 无端口、非 TCP 无序号），解析器不做臆造。</p>
 *
 * @param frameNumber    帧号，全局递增
 * @param timestamp      抓包时间戳文本
 * @param epochMillis    抓包时间戳（毫秒）
 * @param interfaceName  采集网卡标识
 * @param streamId       TCP 流号，同一条连接的各包共享
 * @param tcpSeq         TCP 序列号
 * @param tcpAck         TCP 确认号
 * @param ipProtocol     IP 层协议号
 * @param appLayer       tshark 识别出的应用层名，未识别时为 {@code null}
 * @param tcpFlags       TCP 标志位文本
 * @param ttl            IP TTL / 跳数限制
 * @author CH
 * @since 4.0.0.42
 */
public record PacketMeta(
        Long frameNumber,
        String timestamp,
        Long epochMillis,
        String interfaceName,
        String streamId,
        Long tcpSeq,
        Long tcpAck,
        Integer ipProtocol,
        String appLayer,
        String tcpFlags,
        Integer ttl
) {

    /**
     * 是否为 TCP 数据包。
     *
     * <p>只有 TCP 存在流与序号，UDP/DNS/ARP 等无需重组。</p>
     *
     * @return 是 TCP 返回 true
     */
    public boolean tcp() {
        return tcpSeq != null;
    }

    /**
     * 是否携带应用层载荷。
     *
     * <p>纯 ACK、SYN 等控制报文没有载荷，不应参与协议还原与流重组。</p>
     *
     * @return 有载荷返回 true
     */
    public boolean hasPayload() {
        return tcpSeq != null && payloadHint() > 0;
    }

    /**
     * 载荷长度提示，由 {@link #tcpFlags()} 与 {@link #tcpSeq()} 粗略推断。
     *
     * <p>tshark 的 {@code -x} 输出下 TCP 层会给出 {@code tcp.len}；
     * 该字段缺失时以"非纯控制报文即有载荷"近似，避免把应用数据误判为空包。</p>
     *
     * @return 载荷长度，缺失时返回 0
     */
    public int payloadHint() {
        if (tcpFlags == null) {
            return 0;
        }
        String flags = tcpFlags.toUpperCase(java.util.Locale.ROOT);
        boolean controlOnly = flags.contains("SYN") || flags.contains("RST")
                || flags.contains("FIN") || flags.replace(" ", "").equals("ACK");
        return controlOnly ? 0 : 1;
    }
}
