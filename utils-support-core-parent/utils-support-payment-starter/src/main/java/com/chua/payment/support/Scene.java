package com.chua.payment.support;

/**
 * 支付场景
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum Scene {

    /**
     * APP 支付
     */
    APP,

    /**
     * 小程序支付
     */
    MINI_APP,

    /**
     * H5 支付
     */
    H5,

    /**
     * 扫码支付（条码/扫码枪）
     */
    BAR_CODE,

    /**
     * Native 支付（扫码下单）
     */
    NATIVE,

    /**
     * JSAPI 支付（公众号/服务号）
     */
    JSAPI
}
