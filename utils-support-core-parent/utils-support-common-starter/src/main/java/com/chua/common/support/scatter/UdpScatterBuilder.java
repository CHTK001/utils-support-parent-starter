package com.chua.common.support.scatter;

/**
 * UDP scatter 构建器。
 *
 * @author CH
 * @since 4.0.0.42
*/
public class UdpScatterBuilder extends ScatterBuilder<UdpScatterBuilder> {

    private static final String PROTOCOL_UDP = "udp";

    /**
     * 构造方法，创建 UdpScatterBuilder 实例。
     */
    public UdpScatterBuilder() {
        super(PROTOCOL_UDP);
    }

    /**
     * 构造方法，创建 UdpScatterBuilder 实例。
     *
     * @param setting 方法入参 setting
     */
    public UdpScatterBuilder(ScatterSetting setting) {
        super(PROTOCOL_UDP, setting);
    }
}
