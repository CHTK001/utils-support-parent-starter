package com.chua.common.support.network.sip;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SIP 服务器配置。
 *
 * <p>配置信令服务器的主机、TCP 与 KCP 双传输监听端口及启用开关。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SipConfig {

    /**
     * 默认 TCP 监听端口
     */
    public static final int DEFAULT_TCP_PORT = 19460;

    /**
     * 默认 KCP 监听端口
     */
    public static final int DEFAULT_KCP_PORT = 19461;

    /**
     * 默认数据平面监听端口
     */
    public static final int DEFAULT_DATA_PORT = 19462;

    /**
     * 监听主机
     */
    @Builder.Default
    /** 主机 */
    private String host = "0.0.0.0";

    /**
     * 是否启用 TCP 传输
     */
    @Builder.Default
    /** TCP是否启用 */
    private boolean tcpEnabled = true;

    /**
     * TCP 监听端口
     */
    @Builder.Default
    /** TCP端口 */
    private int tcpPort = DEFAULT_TCP_PORT;

    /**
     * 是否启用 KCP 传输
     */
    @Builder.Default
    /** KCP是否启用 */
    private boolean kcpEnabled = true;

    /**
     * KCP 监听端口
     */
    @Builder.Default
    /** KCP端口 */
    private int kcpPort = DEFAULT_KCP_PORT;

    /**
     * 是否启用 frp 数据平面
     */
    @Builder.Default
    /** 数据平面是否启用 */
    private boolean dataPlaneEnabled = true;

    /**
     * 数据平面监听端口
     */
    @Builder.Default
    /** 数据平面端口 */
    private int dataPort = DEFAULT_DATA_PORT;

    /**
     * 认证令牌：注册与数据平面握手均需携带
     * {@code HMAC-SHA256(token, clientId + host + port)} 签名，验签通过才允许接入
     */
    @Builder.Default
    /** 认证令牌 */
    private String token = "chua-sip-default-token";

    /**
     * 创建一份独立的默认配置。
     *
     * @return 新的默认配置实例
     */
    public static SipConfig defaults() {
        return SipConfig.builder().build();
    }
}
