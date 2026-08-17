package com.chua.common.support.scatter;

/**
 * TCP Scatter 构建器。
 * <p>TCP 支持 seed 引导（已知节点可不在线，靠 gossip 扩散）与网段模式扩散，
 * 通过 {@link #tcp()} 创建。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TcpScatterBuilder extends ScatterBuilder<TcpScatterBuilder> {

    /**
     * 传输协议标识：tcp
     */
    private static final String PROTOCOL_TCP = "tcp";

    /**
     * 默认构造，协议固定为 tcp。
     */
    public TcpScatterBuilder() {
        super(PROTOCOL_TCP);
    }

    /**
     * 带配置构造。
     *
     * @param setting 配置对象
     */
    public TcpScatterBuilder(ScatterSetting setting) {
        super(PROTOCOL_TCP, setting);
    }
}
