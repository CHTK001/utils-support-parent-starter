package com.chua.tencent.support.payment;

import com.github.binarywang.wxpay.bean.request.WxPayMicropayRequest;
import com.github.binarywang.wxpay.bean.request.WxPayUnifiedOrderRequest;
import com.github.binarywang.wxpay.bean.result.BaseWxPayResult;
import com.github.binarywang.wxpay.bean.result.WxPayMicropayResult;
import com.github.binarywang.wxpay.bean.result.WxPayUnifiedOrderResult;
import com.github.binarywang.wxpay.config.WxPayConfig;
import com.github.binarywang.wxpay.service.WxPayService;
import com.github.binarywang.wxpay.service.impl.WxPayServiceImpl;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.BeanUtils;
import com.chua.payment.spi.PaymentChannel;
import com.chua.payment.support.PaymentRequest;
import com.chua.payment.support.PaymentResponse;
import com.chua.payment.support.PayException;
import com.chua.payment.support.Scene;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 微信支付渠道实现
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("tenpay")
public class TenpayProvider implements PaymentChannel {

    /** 微信支付服务 */
    private final WxPayService wxPayService;
    /** 配置对象 */
    private final TenpayConfig config;

    public TenpayProvider() {
        this(null);
    }

    public TenpayProvider(TenpayConfig config) {
        this.config = config;
        this.wxPayService = buildService();
    }

    @Override
    public PaymentResponse pay(PaymentRequest request) {
        switch (request.getScene()) {
            case JSAPI:
            case MINI_APP:
                return jsapiPay(request);
            case APP:
                return appPay(request);
            case H5:
                return h5Pay(request);
            case NATIVE:
                return nativePay(request);
            case BAR_CODE:
                return barCodePay(request);
            default:
                throw new PayException("不支持的支付场景: " + request.getScene());
        }
    }

    private WxPayService buildService() {
        if (config == null) {
            return null;
        }
        WxPayConfig payConfig = new WxPayConfig();
        BeanUtils.copyProperties(config, payConfig);
        if (config.getCertPath() != null) {
            payConfig.setKeyPath(config.getCertPath());
        }

        WxPayServiceImpl payService = new WxPayServiceImpl();
        payService.setConfig(payConfig);
        return payService;
    }

    private PaymentResponse jsapiPay(PaymentRequest request) {
        WxPayUnifiedOrderRequest orderRequest = buildOrderRequest(request);
        orderRequest.setTradeType("JSAPI");
        if (request.getExtParams() != null) {
            orderRequest.setOpenid(request.getExtParams().get("openid"));
        }

        try {
            Map<String, String> payInfo = wxPayService.getPayInfo(orderRequest);
            return PaymentResponse.builder()
                    .success(true)
                    .outTradeNo(orderRequest.getOutTradeNo())
                    .rawResponse(toJson(payInfo))
                    .extParams(payInfo)
                    .build();
        } catch (Exception e) {
            throw new PayException("WXPAY_ERROR", e.getMessage());
        }
    }

    private PaymentResponse appPay(PaymentRequest request) {
        WxPayUnifiedOrderRequest orderRequest = buildOrderRequest(request);
        orderRequest.setTradeType("APP");

        try {
            Map<String, String> payInfo = wxPayService.getPayInfo(orderRequest);
            return PaymentResponse.builder()
                    .success(true)
                    .outTradeNo(orderRequest.getOutTradeNo())
                    .rawResponse(toJson(payInfo))
                    .extParams(payInfo)
                    .build();
        } catch (Exception e) {
            throw new PayException("WXPAY_ERROR", e.getMessage());
        }
    }

    private PaymentResponse h5Pay(PaymentRequest request) {
        WxPayUnifiedOrderRequest orderRequest = buildOrderRequest(request);
        orderRequest.setTradeType("MWEB");
        if (request.getExtParams() != null) {
            String sceneInfo = request.getExtParams().get("sceneInfo");
            if (sceneInfo != null) {
                orderRequest.setSceneInfo(sceneInfo);
            } else {
                orderRequest.setSceneInfo("{\"h5_info\":{\"type\":\"Wap\",\"wap_url\":\"https://example.com\",\"wap_name\":\"订单支付\"}}");
            }
        }

        try {
            String responseXml = wxPayService.post("https://api.mch.weixin.qq.com/pay/unifiedorder", orderRequest.toXML(), false);
            WxPayUnifiedOrderResult result = BaseWxPayResult.fromXML(responseXml, WxPayUnifiedOrderResult.class);
            checkResult(result);
            return PaymentResponse.builder()
                    .success(true)
                    .outTradeNo(orderRequest.getOutTradeNo())
                    .codeUrl(result.getMwebUrl())
                    .rawResponse(result.getXmlString())
                    .build();
        } catch (Exception e) {
            throw new PayException("WXPAY_ERROR", e.getMessage());
        }
    }

    private PaymentResponse nativePay(PaymentRequest request) {
        WxPayUnifiedOrderRequest orderRequest = buildOrderRequest(request);
        orderRequest.setTradeType("NATIVE");
        if (request.getExtParams() != null) {
            orderRequest.setProductId(request.getExtParams().get("productId"));
        }

        try {
            String responseXml = wxPayService.post("https://api.mch.weixin.qq.com/pay/unifiedorder", orderRequest.toXML(), false);
            WxPayUnifiedOrderResult result = BaseWxPayResult.fromXML(responseXml, WxPayUnifiedOrderResult.class);
            checkResult(result);
            return PaymentResponse.builder()
                    .success(true)
                    .outTradeNo(orderRequest.getOutTradeNo())
                    .codeUrl(result.getCodeURL())
                    .rawResponse(result.getXmlString())
                    .build();
        } catch (Exception e) {
            throw new PayException("WXPAY_ERROR", e.getMessage());
        }
    }

    private PaymentResponse barCodePay(PaymentRequest request) {
        WxPayMicropayRequest micropayRequest = new WxPayMicropayRequest();
        if (request.getExtParams() != null) {
            micropayRequest.setAuthCode(request.getExtParams().get("authCode"));
        }
        micropayRequest.setBody(request.getSubject());
        micropayRequest.setOutTradeNo(request.getOutTradeNo());
        micropayRequest.setTotalFee(request.getAmount().multiply(BigDecimal.valueOf(100)).intValue());
        micropayRequest.setSpbillCreateIp("127.0.0.1");

        try {
            String responseXml = wxPayService.post("https://api.mch.weixin.qq.com/pay/micropay", micropayRequest.toXML(), false);
            WxPayMicropayResult result = BaseWxPayResult.fromXML(responseXml, WxPayMicropayResult.class);
            checkResult(result);
            return PaymentResponse.builder()
                    .success(true)
                    .tradeNo(result.getTransactionId())
                    .outTradeNo(result.getOutTradeNo())
                    .rawResponse(result.getXmlString())
                    .build();
        } catch (Exception e) {
            throw new PayException("WXPAY_ERROR", e.getMessage());
        }
    }

    private WxPayUnifiedOrderRequest buildOrderRequest(PaymentRequest request) {
        WxPayUnifiedOrderRequest orderRequest = new WxPayUnifiedOrderRequest();
        orderRequest.setBody(request.getSubject());
        orderRequest.setOutTradeNo(request.getOutTradeNo());
        orderRequest.setTotalFee(request.getAmount().multiply(BigDecimal.valueOf(100)).intValue());
        orderRequest.setSpbillCreateIp("127.0.0.1");
        if (config != null && config.getNotifyUrl() != null) {
            orderRequest.setNotifyUrl(config.getNotifyUrl());
        }
        if (request.getNotifyUrl() != null) {
            orderRequest.setNotifyUrl(request.getNotifyUrl());
        }
        if (request.getTimeoutExpress() != null) {
            orderRequest.setTimeExpire(request.getTimeoutExpress());
        }
        return orderRequest;
    }

    private void checkResult(BaseWxPayResult result) {
        if (!"SUCCESS".equals(result.getReturnCode())) {
            throw new PayException("WXPAY_ERROR", result.getReturnMsg());
        }
        if (!"SUCCESS".equals(result.getResultCode())) {
            throw new PayException("WXPAY_ERROR", result.getErrCodeDes());
        }
    }

    private static String toJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (sb.length() > 1) {
                sb.append(",");
            }
            sb.append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }
}
