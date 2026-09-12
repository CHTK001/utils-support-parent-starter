package com.chua.alibaba.support.payment;

import lombok.Builder;
import lombok.Data;

/**
 * 支付宝配置
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class AlipayConfig {

    /**
      * 应用标识
     */
    private String appId;

    /**
     * 应用私钥
     */
    private String privateKey;

    /**
     * 支付宝公钥
     */
    private String alipayPublicKey;

    /**
     * 异步通知地址
     */
    private String notifyUrl;

    /**
     * 签名类型
     */
    private String signType;

    /**
     * 网关地址
     */
    private String gateway;

    /**
     * 字符集
     */
    private String charset;
}
