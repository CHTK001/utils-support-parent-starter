package com.chua.payment.support;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;


import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 支付请求
 *
 * <p>通过 Builder 模式构建，支持链式调用。
 *
 * @author CH
 * @since 4.0.0.42
 */

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class PaymentRequest {

    /**
     * 商户订单号
     */
    private String outTradeNo;

    /**
     * 订单总金额（单位：元）
     */
    private BigDecimal amount;

    /**
     * 订单标题
     */
    private String subject;

    /**
     * 订单描述
     */
    private String body;

    /**
     * 支付场景
     */
    private Scene scene;

    /**
     * 交易超时时间（如 30m、1h、1d）
     */
    private String timeoutExpress;

    /**
     * 异步通知地址
     */
    private String notifyUrl;

    /**
     * 同步跳转地址（H5/网页支付）
     */
    private String returnUrl;

    /**
     * 扩展参数
     */
    private Map<String, String> extParams;
}
