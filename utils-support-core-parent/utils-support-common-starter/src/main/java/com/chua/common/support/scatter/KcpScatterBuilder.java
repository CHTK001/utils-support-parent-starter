package com.chua.common.support.scatter;

/**
 * KCP Scatter 构建器。
 * <p>KCP 支持 seed 引导（已知节点可不在线，靠 gossip 扩散）与网段模式扩散，
 * 通过 {@link #kcp()} 创建。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class KcpScatterBuilder extends ScatterBuilder<KcpScatterBuilder> {

    /**
     * 传输协议标识：kcp
     */
    private static final String PROTOCOL_KCP = "kcp";

    /**
     * 默认构造，协议固定为 kcp。
     */
    public KcpScatterBuilder() {
        super(PROTOCOL_KCP);
    }

    /**
     * 带配置构造。
     *
     * @param setting 配置对象
     */
    public KcpScatterBuilder(ScatterSetting setting) {
        super(PROTOCOL_KCP, setting);
    }
}
