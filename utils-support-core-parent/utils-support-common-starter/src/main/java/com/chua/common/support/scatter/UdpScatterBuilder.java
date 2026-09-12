package com.chua.common.support.scatter;

/**
 * UDP scatter 构建器。
 *
 * @author CH
 * @since 4.0.0.42
 * @param setting setting
 */
public class UdpScatterBuilder extends ScatterBuilder<UdpScatterBuilder> {

    private static final String PROTOCOL_UDP = "udp"; // 协议udp
/**
 * udpscatter构建器。
 * @param setting setting
 */

    public UdpScatterBuilder() {
        super(PROTOCOL_UDP);
    }

    public UdpScatterBuilder(ScatterSetting setting) {
        super(PROTOCOL_UDP, setting);
    }
}
