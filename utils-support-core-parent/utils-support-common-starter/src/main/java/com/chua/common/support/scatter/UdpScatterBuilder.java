package com.chua.common.support.scatter;

/**
 * UDP 广播模式 Scatter 构建器。
 * <p>UDP 仅支持广播：seed 设置 224 组播地址，通过 {@link #udp()} 创建。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class UdpScatterBuilder extends ScatterBuilder<UdpScatterBuilder> {

    /**
     * 传输协议标识：udp
     */
    private static final String PROTOCOL_UDP = "udp";

    /**
     * 默认构造，协议固定为 udp。
     */
    public UdpScatterBuilder() {
        super(PROTOCOL_UDP);
    }

    /**
     * 带配置构造。
     *
     * @param setting 配置对象
     */
    public UdpScatterBuilder(ScatterSetting setting) {
        super(PROTOCOL_UDP, setting);
    }
}
