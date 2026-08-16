package com.chua.payment.spi;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.payment.support.PaymentRequest;
import com.chua.payment.support.PaymentResponse;

/**
 * 支付渠道 SPI 接口
 *
 * <p>定义统一的支付渠道契约，支持支付、查询、关闭、退款等核心操作。
 * 实现类通过 SPI 机制按渠道名称注册，调用方通过工厂方法获取实例。
 *
 * <p>链式调用示例：
 * <pre>{@code
 *   PaymentResponse response = PaymentChannel.getChannel("alipay")
 *       .pay(PaymentRequest.builder()
 *           .amount(BigDecimal.valueOf(0.01))
 *           .subject("订单标题")
 *           .outTradeNo("ORD20260101001")
 *           .scene(Scene.APP)
 *           .build());
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi
public interface PaymentChannel {

    /**
     * 支付
     *
     * @param request 支付请求
     * @return 支付响应
     */
    PaymentResponse pay(PaymentRequest request);

    /**
     * 查询订单
     *
     * @param request 查询请求
     * @return 查询响应
     */
    default PaymentResponse query(PaymentRequest request) {
        throw new UnsupportedOperationException();
    }

    /**
     * 关闭订单
     *
     * @param request 关闭请求
     * @return 关闭响应
     */
    default PaymentResponse close(PaymentRequest request) {
        throw new UnsupportedOperationException();
    }

    /**
     * 退款
     *
     * @param request 退款请求
     * @return 退款响应
     */
    default PaymentResponse refund(PaymentRequest request) {
        throw new UnsupportedOperationException();
    }

    /**
     * 通过 SPI 获取支付渠道实例
     *
     * @param name 渠道名称，如 "alipay"、"tenpay"
     * @return PaymentChannel 实例
     */
    static PaymentChannel getChannel(String name) {
        return ServiceProvider.of(PaymentChannel.class)
                .getExtension(name);
    }
}
