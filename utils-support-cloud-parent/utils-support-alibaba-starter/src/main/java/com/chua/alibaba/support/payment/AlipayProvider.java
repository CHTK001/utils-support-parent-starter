package com.chua.alibaba.support.payment;

import com.alipay.api.AlipayClient;
import com.alipay.api.AlipayObject;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradeAppPayModel;
import com.alipay.api.domain.AlipayTradePayModel;
import com.alipay.api.domain.AlipayTradePrecreateModel;
import com.alipay.api.domain.AlipayTradeWapPayModel;
import com.alipay.api.request.AlipayTradeAppPayRequest;
import com.alipay.api.request.AlipayTradePayRequest;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.request.AlipayTradeWapPayRequest;
import com.alipay.api.response.AlipayTradeAppPayResponse;
import com.alipay.api.response.AlipayTradePayResponse;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.alipay.api.response.AlipayTradeWapPayResponse;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.payment.spi.PaymentChannel;
import com.chua.payment.support.PaymentRequest;
import com.chua.payment.support.PaymentResponse;
import com.chua.payment.support.PayException;
import com.chua.payment.support.Scene;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 支付宝支付渠道实现
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("alipay")
public class AlipayProvider implements PaymentChannel {

    /** 客户端 */
    private final AlipayClient client;
    /** 配置对象 */
    private final AlipayConfig config;

    /** 创建 alipay提供者 实例 */
    public AlipayProvider() {
        this(null);
    }

    /**
     * 创建 alipay提供者 实例
     * @param config 配置
     */
    public AlipayProvider(AlipayConfig config) {
        this.config = config;
        this.client = buildClient();
    }

    @Override
    /** 薪酬 */
    public PaymentResponse pay(PaymentRequest request) {
        switch (request.getScene()) {
            case APP:
            case MINI_APP:
                return appPay(request);
            case H5:
                return wapPay(request);
            case BAR_CODE:
                return barCodePay(request);
            case NATIVE:
                return precreate(request);
            default:
                throw new PayException("不支持的支付场景: " + request.getScene());
        }
    }

    /**
     * 构建客户端
     *
     * @return 构建客户端的结果
     */
    private AlipayClient buildClient() {
        if (config == null) {
            return null;
        }
        return new DefaultAlipayClient(
                config.getGateway() != null ? config.getGateway() : "https://openapi.alipay.com/gateway.do",
                config.getAppId(),
                config.getPrivateKey(),
                config.getCharset() != null ? config.getCharset() : "UTF-8",
                config.getSignType() != null ? config.getSignType() : "RSA2",
                config.getAlipayPublicKey(),
                config.getSignType() != null ? config.getSignType() : "RSA2"
        );
    }

    /**
     * app薪酬
     *
     * @param request 请求
     * @return app薪酬的结果
     */
    private PaymentResponse appPay(PaymentRequest request) {
        AlipayTradeAppPayRequest payRequest = new AlipayTradeAppPayRequest();
        setNotifyUrl(payRequest, request);

        AlipayTradeAppPayModel model = new AlipayTradeAppPayModel();
        model.setOutTradeNo(request.getOutTradeNo());
        model.setTotalAmount(request.getAmount().toString());
        model.setSubject(request.getSubject());
        model.setBody(request.getBody());
        if (request.getTimeoutExpress() != null) {
            model.setTimeoutExpress(request.getTimeoutExpress());
        }
        if (request.getReturnUrl() != null) {
            payRequest.setReturnUrl(request.getReturnUrl());
        }
        if (request.getExtParams() != null) {
            model.setPassbackParams(toUrlStr(request.getExtParams()));
        }
        if (Scene.MINI_APP.equals(request.getScene()) && request.getExtParams() != null) {
            String appAuthToken = request.getExtParams().get("appAuthToken");
            if (appAuthToken != null) {
                model.setBusinessParams("{\"app_auth_token\":\"" + appAuthToken + "\"}");
            }
        }

        payRequest.setBizModel(model);

        try {
            AlipayTradeAppPayResponse response = client.execute(payRequest);
            if (response.isSuccess()) {
                Map<String, String> ext = new HashMap<>();
                ext.put("orderStr", response.getBody());
                return PaymentResponse.builder()
                        .success(true)
                        .tradeNo(response.getTradeNo())
                        .outTradeNo(response.getOutTradeNo())
                        .rawResponse(response.getBody())
                        .extParams(ext)
                        .build();
            }
            throw new PayException(response.getCode(), response.getMsg());
        } catch (Exception e) {
            throw new PayException("ALIPAY_ERROR", e.getMessage());
        }
    }

    /**
     * wap薪酬
     *
     * @param request 请求
     * @return wap薪酬的结果
     */
    private PaymentResponse wapPay(PaymentRequest request) {
        AlipayTradeWapPayRequest payRequest = new AlipayTradeWapPayRequest();
        setNotifyUrl(payRequest, request);

        AlipayTradeWapPayModel model = new AlipayTradeWapPayModel();
        model.setOutTradeNo(request.getOutTradeNo());
        model.setTotalAmount(request.getAmount().toString());
        model.setSubject(request.getSubject());
        model.setBody(request.getBody());
        if (request.getTimeoutExpress() != null) {
            model.setTimeoutExpress(request.getTimeoutExpress());
        }
        if (request.getReturnUrl() != null) {
            payRequest.setReturnUrl(request.getReturnUrl());
        }
        if (request.getExtParams() != null) {
            model.setPassbackParams(toUrlStr(request.getExtParams()));
        }

        payRequest.setBizModel(model);

        try {
            AlipayTradeWapPayResponse response = client.execute(payRequest);
            if (response.isSuccess()) {
                return PaymentResponse.builder()
                        .success(true)
                        .tradeNo(response.getTradeNo())
                        .outTradeNo(response.getOutTradeNo())
                        .rawResponse(response.getBody())
                        .build();
            }
            throw new PayException(response.getCode(), response.getMsg());
        } catch (Exception e) {
            throw new PayException("ALIPAY_ERROR", e.getMessage());
        }
    }

    /**
     * bar编码薪酬
     *
     * @param request 请求
     * @return bar编码薪酬的结果
     */
    private PaymentResponse barCodePay(PaymentRequest request) {
        AlipayTradePayRequest payRequest = new AlipayTradePayRequest();
        setNotifyUrl(payRequest, request);

        AlipayTradePayModel model = new AlipayTradePayModel();
        model.setOutTradeNo(request.getOutTradeNo());
        model.setTotalAmount(request.getAmount().toString());
        model.setSubject(request.getSubject());
        model.setBody(request.getBody());
        if (request.getTimeoutExpress() != null) {
            model.setTimeoutExpress(request.getTimeoutExpress());
        }
        if (request.getExtParams() != null) {
            String authCode = request.getExtParams().get("authCode");
            if (authCode != null) {
                model.setAuthCode(authCode);
            }
            model.setPassbackParams(toUrlStr(request.getExtParams()));
        }

        payRequest.setBizModel(model);

        try {
            AlipayTradePayResponse response = client.execute(payRequest);
            if (response.isSuccess()) {
                return PaymentResponse.builder()
                        .success(true)
                        .tradeNo(response.getTradeNo())
                        .outTradeNo(response.getOutTradeNo())
                        .rawResponse(response.getBody())
                        .build();
            }
            throw new PayException(response.getCode(), response.getMsg());
        } catch (Exception e) {
            throw new PayException("ALIPAY_ERROR", e.getMessage());
        }
    }

    /**
     * Precreate
     *
     * @param request 请求
     * @return precreate的结果
     */
    private PaymentResponse precreate(PaymentRequest request) {
        AlipayTradePrecreateRequest payRequest = new AlipayTradePrecreateRequest();
        setNotifyUrl(payRequest, request);

        AlipayTradePrecreateModel model = new AlipayTradePrecreateModel();
        model.setOutTradeNo(request.getOutTradeNo());
        model.setTotalAmount(request.getAmount().toString());
        model.setSubject(request.getSubject());
        model.setBody(request.getBody());
        if (request.getTimeoutExpress() != null) {
            model.setTimeoutExpress(request.getTimeoutExpress());
        }
        if (request.getExtParams() != null) {
            model.setPassbackParams(toUrlStr(request.getExtParams()));
        }

        payRequest.setBizModel(model);

        try {
            AlipayTradePrecreateResponse response = client.execute(payRequest);
            if (response.isSuccess()) {
                return PaymentResponse.builder()
                        .success(true)
                        .outTradeNo(response.getOutTradeNo())
                        .codeUrl(response.getQrCode())
                        .rawResponse(response.getBody())
                        .build();
            }
            throw new PayException(response.getCode(), response.getMsg());
        } catch (Exception e) {
            throw new PayException("ALIPAY_ERROR", e.getMessage());
        }
    }

    /**
     * 设置通知Url
     *
     * @param payRequest 薪酬请求
     * @param request 请求
     */
    private void setNotifyUrl(com.alipay.api.AlipayRequest<?> payRequest, PaymentRequest request) {
        if (config != null && config.getNotifyUrl() != null) {
            payRequest.setNotifyUrl(config.getNotifyUrl());
        }
        if (request.getNotifyUrl() != null) {
            payRequest.setNotifyUrl(request.getNotifyUrl());
        }
    }

    /**
     * 转为urlstr
     *
     * @param params 参数
     * @return 转为urlstr的结果
     */
    private static String toUrlStr(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append("&");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }
}
