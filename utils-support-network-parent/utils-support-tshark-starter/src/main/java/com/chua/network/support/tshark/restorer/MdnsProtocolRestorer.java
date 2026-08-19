package com.chua.network.support.tshark.restorer;

/**
 * mDNS（组播 DNS）协议还原器。
 *
 * <p>mDNS 与标准 DNS 报文格式相同，使用 5353 端口和组播地址 224.0.0.251。
 * 标识：QR 位通常为 0（查询）或 1（应答），大多数响应包含大量 Answer 记录。
 * 由于 mDNS 报文长度通常很短（<512 字节），简单复用 DNS 解析即可。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MdnsProtocolRestorer extends DnsProtocolRestorer {

    @Override
    /** 获取ProtocolName */
    public String getProtocolName() {
        return "mdns";
    }

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 35;
    }

    @Override
    /** 是否可以Restore */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 12) {
            return false;
        }
        // 必须以 0 开头表示标准 DNS 报文
        return super.canRestore(protocolInfo, rawData);
    }

    @Override
    /** Restore */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        return "[mDNS] " + super.restore(protocolInfo, rawData);
    }
}