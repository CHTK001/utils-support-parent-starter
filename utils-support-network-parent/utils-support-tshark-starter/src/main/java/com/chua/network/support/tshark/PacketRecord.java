package com.chua.network.support.tshark;

import java.util.Arrays;
import java.util.List;

/**
 * tshark 数据包解析结果记录。
 *
 * <p>一帧的三层视图：</p>
 * <ol>
 *   <li><b>单包</b> — 源/目的地址端口、协议、长度、摘要、原始 JSON、TCP 生命周期
 *       与 {@link #restoredText()} 单包协议还原结果。</li>
 *   <li><b>流</b> — {@link #meta()} 提供流号与序列号供 TCP 重组使用，
 *       {@link #payload()} 为传输层载荷字节。</li>
 *   <li><b>会话</b> — {@link #restoredDetails()} 保留全部可命中的还原结果，
 *       而非只留首个，供会话聚合做多轮请求响应的配对。</li>
 * </ol>
 *
 * <p>{@code payload} 为数组，record 自动生成的 {@code equals}/{@code hashCode}
 * 对数组按引用比较，会给出"内容相同却不相等"的误导结果，因此本类显式覆写为按内容比较。</p>
 *
 * @param sourceIp         源 IP 地址
 * @param destinationIp    目的 IP 地址
 * @param sourcePort       源端口号
 * @param destinationPort  目的端口号
 * @param protocol         协议名称（HTTP/TLS/DNS/MQTT/TCP/UDP/ICMP/ARP/OTHER）
 * @param length           数据包长度（字节）
 * @param info             人类可读的摘要信息
 * @param rawData          原始 tshark 单包 JSON
 * @param lifecycleJson    TCP 生命周期 JSON（非 TCP 协议为空对象 {@code "{}"}）
 * @param restoredText     主协议还原结果，最高优先级还原器的输出，不可还原时为 {@code null}
 * @param meta             链路层与传输层元数据
 * @param payload          传输层载荷字节，无载荷时为空数组
 * @param restoredDetails  全部协议还原结果，按优先级升序
 * @author CH
 * @since 4.0.0.42
 */
public record PacketRecord(
        String sourceIp,
        String destinationIp,
        Integer sourcePort,
        Integer destinationPort,
        String protocol,
        Integer length,
        String info,
        String rawData,
        String lifecycleJson,
        String restoredText,
        PacketMeta meta,
        byte[] payload,
        List<String> restoredDetails
) {

    /**
     * 规范构造器：载荷数组做防御性拷贝，还原明细列表做防御性拷贝。
     *
     * <p>value class 前置条件——数组与集合组件都必须深不可变。抓包解析逐包构造本记录，
     * 载荷字节来自 tshark 报文的字节解码缓冲、还原明细来自还原器逐条累积的可变列表，
     * 若直接持有，缓冲区复用会让已缓存的包内容被后续包改写。</p>
     *
     * <p>{@code payload} 允许为 {@code null}（{@link #hasPayload()} 显式判空、
     * {@link com.chua.network.support.tshark.stream.ReassembledMessage#payload()} 亦容忍空载荷），
     * 故保留 {@code null} 语义；
     * {@code restoredDetails} 在远端报文缺省时同样可能为空，保留 {@code null} 语义，
     * 元素允许为 {@code null}。</p>
     *
     * @param sourceIp         源 IP 地址
     * @param destinationIp    目的 IP 地址
     * @param sourcePort       源端口号
     * @param destinationPort  目的端口号
     * @param protocol         协议名称
     * @param length           数据包长度
     * @param info             摘要信息
     * @param rawData          原始 tshark 单包 JSON
     * @param lifecycleJson    TCP 生命周期 JSON
     * @param restoredText     主协议还原结果
     * @param meta             链路层与传输层元数据
     * @param payload          传输层载荷字节
     * @param restoredDetails  全部协议还原结果
     */
    public PacketRecord {
        payload = payload == null ? null : payload.clone();
        restoredDetails = restoredDetails == null ? null : List.copyOf(restoredDetails);
    }

    /**
     * 访问器覆写：返回内部载荷数组的副本，避免外部持有内部引用。
     *
     * @return 载荷副本；载荷为 {@code null} 时返回 {@code null}
     */
    @Override
    public byte[] payload() {
        return payload == null ? null : payload.clone();
    }

    /**
     * 构造单包记录，载荷与还原明细为空。
     *
     * <p>保留该重载是为了让"不需要流重组的纯单包解析"调用方保持简洁。</p>
     *
     * @param sourceIp        源 IP 地址
     * @param destinationIp   目的 IP 地址
     * @param sourcePort      源端口号
     * @param destinationPort 目的端口号
     * @param protocol        协议名称
     * @param length          数据包长度
     * @param info            摘要信息
     * @param rawData         原始 JSON
     * @param lifecycleJson   生命周期 JSON
     * @param restoredText    主还原结果
     */
    public PacketRecord(String sourceIp, String destinationIp, Integer sourcePort, Integer destinationPort,
                        String protocol, Integer length, String info, String rawData,
                        String lifecycleJson, String restoredText) {
        this(sourceIp, destinationIp, sourcePort, destinationPort, protocol, length, info, rawData,
                lifecycleJson, restoredText, null, new byte[0], List.of());
    }

    /**
     * 是否为 TCP 数据包。
     *
     * @return 是 TCP 返回 true
     */
    public boolean tcp() {
        return meta != null && meta.tcp();
    }

    /**
     * 是否携带应用层载荷。
     *
     * @return 有载荷返回 true
     */
    public boolean hasPayload() {
        return payload != null && payload.length > 0;
    }

    /**
     * 取抓包时间戳（毫秒）。
     *
     * @return 毫秒时间戳，缺失返回 {@code 0}
     */
    public long epochMillis() {
        return meta == null || meta.epochMillis() == null ? 0L : meta.epochMillis();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PacketRecord that)) {
            return false;
        }
        return java.util.Objects.equals(sourceIp, that.sourceIp)
                && java.util.Objects.equals(destinationIp, that.destinationIp)
                && java.util.Objects.equals(sourcePort, that.sourcePort)
                && java.util.Objects.equals(destinationPort, that.destinationPort)
                && java.util.Objects.equals(protocol, that.protocol)
                && java.util.Objects.equals(length, that.length)
                && java.util.Objects.equals(info, that.info)
                && java.util.Objects.equals(meta, that.meta)
                && Arrays.equals(payload, that.payload)
                && java.util.Objects.equals(restoredDetails, that.restoredDetails);
    }

    @Override
    public int hashCode() {
        int result = java.util.Objects.hash(sourceIp, destinationIp, sourcePort, destinationPort,
                protocol, length, info, meta, restoredDetails);
        return 31 * result + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "PacketRecord{" + info
                + (restoredText == null ? "" : ", restored=" + firstLine(restoredText))
                + '}';
    }

    /**
     * 取文本首行，避免多行还原结果把摘要撑爆。
     *
     * @param text 文本
     * @return 首行
     */
    private static String firstLine(String text) {
        int index = text.indexOf('\n');
        String line = index < 0 ? text : text.substring(0, index);
        return line.length() > 120 ? line.substring(0, 120) + "..." : line;
    }
}
