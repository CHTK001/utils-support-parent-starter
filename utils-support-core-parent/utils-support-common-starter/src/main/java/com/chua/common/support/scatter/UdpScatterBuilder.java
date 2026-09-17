package com.chua.common.support.scatter;

/**
 * UDP scatter 构建器。
 *
 * @author CH
 * @since 4.0.0.42
*/
public class UdpScatterBuilder extends ScatterBuilder<UdpScatterBuilder> {

    private static final String PROTOCOL_UDP = "udp";

    public UdpScatterBuilder() {
        super(PROTOCOL_UDP);
    }

    public UdpScatterBuilder(ScatterSetting setting) {
        super(PROTOCOL_UDP, setting);
    }
}
