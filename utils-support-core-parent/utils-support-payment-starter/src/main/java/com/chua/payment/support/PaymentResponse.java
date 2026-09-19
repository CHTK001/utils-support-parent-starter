package com.chua.payment.support;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;


import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 支付响应
 *
 * @author CH
 * @since 4.0.0.42
 */

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class PaymentResponse {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 支付渠道交易号
     */
    private String tradeNo;

    /**
     * 商户订单号
     */
    private String outTradeNo;

    /**
     * 支付链接（Native/H5 场景）
     */
    private String codeUrl;

    /**
     * app_认证_url（APP 支付签约跳转）
     */
    private String appAuthUrl;

    /**
     * 原始响应体
     */
    private String rawResponse;

    /**
     * 扩展参数
     */
    private Map<String, String> extParams;
}
