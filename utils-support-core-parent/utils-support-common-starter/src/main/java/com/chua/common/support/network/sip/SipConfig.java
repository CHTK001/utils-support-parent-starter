package com.chua.common.support.network.sip;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SIP 服务器配置。
 *
 * <p>单端口模式：信令与 frp 数据平面共用同一监听端口，
 * 连接建立后按首行握手前缀分流。</p>
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
     * 默认监听端口
     */
    public static final int DEFAULT_PORT = 19460;

    /**
     * 监听主机
     */
    @Builder.Default
    /** 主机 */
    private String host = "0.0.0.0";

    /**
     * 监听端口（信令与数据平面共用）
     */
    @Builder.Default
    /** 端口 */
    private int port = DEFAULT_PORT;

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
