package com.chua.network.support.tshark;

/**
* tshark 数据包解析结果记录。
*
* <p>包含源/目的地址端口、协议类型、摘要信息、原始JSON、TCP生命周期数据及协议还原文本。</p>
*
* @param sourceIp 源IP地址
* @param destinationIp 目的IP地址
* @param sourcePort 源端口号
* @param destinationPort 目的端口号
* @param protocol 协议名称（HTTP/TLS/DNS/TCP/UDP/ICMP/ARP/OTHER）
* @param length 数据包长度（字节）
* @param info 人可读的摘要信息
* @param rawData 原始tshark JSON字符串
* @param lifecycleJson TCP生命周期JSON（非TCP协议为空对象"{}"）
* @param restoredText 协议还原文本（HTTP/DNS/...），不可还原时为 {@code null}
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
        String restoredText
) {
}