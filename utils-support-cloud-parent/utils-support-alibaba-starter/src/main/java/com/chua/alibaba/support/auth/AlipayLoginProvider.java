package com.chua.alibaba.support.auth;

import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.request.AlipaySystemOauthTokenRequest;
import com.alipay.api.response.AlipaySystemOauthTokenResponse;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.auth.spi.LoginChannel;
import com.chua.auth.support.LoginException;
import com.chua.auth.support.LoginRequest;
import com.chua.auth.support.LoginResponse;
import com.chua.auth.support.LoginScene;
import com.chua.alibaba.support.payment.AlipayConfig;
import lombok.extern.slf4j.Slf4j;

/**
 * 支付宝登录渠道实现
 *
 * <p>基于 alipay-sdk-java 的 {@link AlipaySystemOauthTokenRequest} 实现授权码登录。
 * 支持小程序授权码登录、H5/APP 授权登录。
 *
 * @author CH
 * @since 2026/07/19
 */
@Slf4j
@Spi("alipay")
public class AlipayLoginProvider implements LoginChannel {

    private final AlipayClient client;

    public AlipayLoginProvider(AlipayConfig config) {
        this.client = new DefaultAlipayClient(
                config.getGateway() != null ? config.getGateway() : "https://openapi.alipay.com/gateway.do",
                config.getAppId(),
                config.getPrivateKey(),
                config.getCharset() != null ? config.getCharset() : "UTF-8",
                config.getSignType() != null ? config.getSignType() : "RSA2",
                config.getAlipayPublicKey(),
                config.getSignType() != null ? config.getSignType() : "RSA2"
        );
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        String authCode = request.getAuthCode();
        if (authCode == null || authCode.isEmpty()) {
            throw new LoginException("缺少授权码 authCode");
        }

        try {
            AlipaySystemOauthTokenRequest tokenRequest = new AlipaySystemOauthTokenRequest();
            tokenRequest.setCode(authCode);
            tokenRequest.setGrantType("authorization_code");

            AlipaySystemOauthTokenResponse tokenResponse = client.execute(tokenRequest);
            if (!tokenResponse.isSuccess()) {
                throw new LoginException("ALIPAY_LOGIN_ERROR",
                        tokenResponse.getSubMsg() != null ? tokenResponse.getSubMsg() : tokenResponse.getMsg());
            }

            return LoginResponse.builder()
                    .success(true)
                    .userId(tokenResponse.getUserId())
                    .accessToken(tokenResponse.getAccessToken())
                    .refreshToken(tokenResponse.getRefreshToken())
                    .expiresIn(tokenResponse.getExpiresIn() != null ? Long.parseLong(tokenResponse.getExpiresIn()) : null)
                    .rawResponse(tokenResponse.getBody())
                    .build();

        } catch (LoginException e) {
            throw e;
        } catch (Exception e) {
            throw new LoginException("ALIPAY_LOGIN_ERROR", e.getMessage());
        }
    }

    @Override
    public LoginResponse refreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isEmpty()) {
            throw new LoginException("缺少刷新令牌 refreshToken");
        }

        try {
            AlipaySystemOauthTokenRequest tokenRequest = new AlipaySystemOauthTokenRequest();
            tokenRequest.setGrantType("refresh_token");
            tokenRequest.setRefreshToken(refreshToken);

            AlipaySystemOauthTokenResponse tokenResponse = client.execute(tokenRequest);
            if (!tokenResponse.isSuccess()) {
                throw new LoginException("ALIPAY_REFRESH_ERROR",
                        tokenResponse.getSubMsg() != null ? tokenResponse.getSubMsg() : tokenResponse.getMsg());
            }

            return LoginResponse.builder()
                    .success(true)
                    .userId(tokenResponse.getUserId())
                    .accessToken(tokenResponse.getAccessToken())
                    .refreshToken(tokenResponse.getRefreshToken())
                    .expiresIn(tokenResponse.getExpiresIn() != null ? Long.parseLong(tokenResponse.getExpiresIn()) : null)
                    .rawResponse(tokenResponse.getBody())
                    .build();

        } catch (LoginException e) {
            throw e;
        } catch (Exception e) {
            throw new LoginException("ALIPAY_REFRESH_ERROR", e.getMessage());
        }
    }
}
