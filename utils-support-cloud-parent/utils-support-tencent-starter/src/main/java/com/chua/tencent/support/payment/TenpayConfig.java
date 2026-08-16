package com.chua.tencent.support.payment;

import lombok.Builder;
import lombok.Data;

/**
 * 微信支付配置
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class TenpayConfig {

    /**
     * 应用ID
     */
    private String appId;

    /**
     * 商户号
     */
    private String mchId;

    /**
     * 商户API密钥
     */
    private String mchKey;

    /**
     * 服务商模式下的子应用ID
     */
    private String subAppId;

    /**
     * 服务商模式下的子商户号
     */
    private String subMchId;

    /**
     * 异步通知地址
     */
    private String notifyUrl;

    /**
     * p12 证书路径（如需双向证书）
     */
    private String certPath;

    /**
     * 证书序列号（API v3）
     */
    private String certSerialNo;

    /**
     * API v3 密钥
     */
    private String apiV3Key;

    /**
     * 应用密钥（小程序/公众号 AppSecret）
     */
    private String appSecret;
}
