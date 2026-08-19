package com.chua.tencent.support.auth;

import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.bean.WxMaJscode2SessionResult;
import cn.binarywang.wx.miniapp.config.impl.WxMaDefaultConfigImpl;
import cn.binarywang.wx.miniapp.json.WxMaGsonBuilder;
import cn.binarywang.wx.miniapp.api.impl.WxMaServiceImpl;
import me.chanjar.weixin.common.bean.oauth2.WxOAuth2AccessToken;
import me.chanjar.weixin.common.service.WxOAuth2Service;
import me.chanjar.weixin.common.util.json.WxGsonBuilder;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.config.impl.WxMpDefaultConfigImpl;
import me.chanjar.weixin.mp.api.impl.WxMpServiceImpl;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.auth.spi.LoginChannel;
import com.chua.auth.support.LoginRequest;
import com.chua.auth.support.LoginResponse;
import com.chua.auth.support.LoginScene;
import com.chua.tencent.support.payment.TenpayConfig;
import com.chua.auth.support.LoginException;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Map;

/**
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("wechat")
public class WechatLoginProvider implements LoginChannel {

    /** 微信小程序服务 */
    private final WxMaService wxMaService;
    /** 微信公众号服务 */
    private final WxMpService wxMpService;
    /** 配置对象 */
    private final TenpayConfig config;

    public WechatLoginProvider() {
        this(null);
    }

    public WechatLoginProvider(TenpayConfig config) {
        this.config = config;
        this.wxMaService = buildMaService();
        this.wxMpService = buildMpService();
    }

    private WxMaService buildMaService() {
        if (config == null || config.getAppId() == null || config.getAppSecret() == null) {
            return null;
        }
        WxMaDefaultConfigImpl maConfig = new WxMaDefaultConfigImpl();
        maConfig.setAppid(config.getAppId());
        maConfig.setSecret(config.getAppSecret());
        WxMaServiceImpl service = new WxMaServiceImpl();
        service.setWxMaConfig(maConfig);
        return service;
    }

    private WxMpService buildMpService() {
        if (config == null || config.getAppId() == null || config.getAppSecret() == null) {
            return null;
        }
        WxMpDefaultConfigImpl mpConfig = new WxMpDefaultConfigImpl();
        mpConfig.setAppId(config.getAppId());
        mpConfig.setSecret(config.getAppSecret());
        WxMpServiceImpl service = new WxMpServiceImpl();
        service.setWxMpConfigStorage(mpConfig);
        return service;
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        String authCode = request.getAuthCode();
        if (StringUtils.isEmpty(authCode)) {
            throw new LoginException("缺少授权码 authCode");
        }

        try {
            switch (request.getScene()) {
                case MINI_APP:
                case APP:
                    return maLogin(authCode);
                case MP:
                case H5:
                case OPEN:
                    return mpLogin(authCode);
                default:
                    throw new LoginException("不支持的登录场景: " + request.getScene());
            }
        } catch (LoginException e) {
            throw e;
        } catch (Exception e) {
            throw new LoginException("WECHAT_LOGIN_ERROR", e.getMessage());
        }
    }

    private LoginResponse maLogin(String code) {
        if (wxMaService == null) {
            throw new LoginException("小程序/APP 登录服务未初始化");
        }
        try {
            WxMaJscode2SessionResult result = wxMaService.getUserService().getSessionInfo(code);
            return LoginResponse.builder()
                    .success(true)
                    .userId(result.getOpenid())
                    .rawResponse(WxMaGsonBuilder.create().toJson(result))
                    .build();
        } catch (Exception e) {
            throw new LoginException("WECHAT_MINI_LOGIN_ERROR", e.getMessage());
        }
    }

    private LoginResponse mpLogin(String code) {
        if (wxMpService == null) {
            throw new LoginException("公众号/H5/开放平台登录服务未初始化");
        }
        try {
            WxOAuth2Service oauth2Service = wxMpService.getOAuth2Service();
            WxOAuth2AccessToken token = oauth2Service.getAccessToken(code);
            return LoginResponse.builder()
                    .success(true)
                    .userId(token.getOpenId())
                    .accessToken(token.getAccessToken())
                    .refreshToken(token.getRefreshToken())
                    .expiresIn((long) token.getExpiresIn())
                    .rawResponse(WxGsonBuilder.create().toJson(token))
                    .extParams(token.getUnionId() != null ? Collections.singletonMap("unionid", token.getUnionId()) : null)
                    .build();
        } catch (Exception e) {
            throw new LoginException("WECHAT_MP_LOGIN_ERROR", e.getMessage());
        }
    }

    @Override
    public LoginResponse refreshToken(String refreshToken) {
        if (StringUtils.isEmpty(refreshToken)) {
            throw new LoginException("缺少刷新令牌 refreshToken");
        }
        if (wxMpService == null) {
            throw new LoginException("公众号登录服务未初始化");
        }
        try {
            WxOAuth2Service oauth2Service = wxMpService.getOAuth2Service();
            WxOAuth2AccessToken token = oauth2Service.refreshAccessToken(refreshToken);
            return LoginResponse.builder()
                    .success(true)
                    .userId(token.getOpenId())
                    .accessToken(token.getAccessToken())
                    .refreshToken(token.getRefreshToken())
                    .expiresIn((long) token.getExpiresIn())
                    .rawResponse(WxGsonBuilder.create().toJson(token))
                    .extParams(token.getUnionId() != null ? Collections.singletonMap("unionid", token.getUnionId()) : null)
                    .build();
        } catch (Exception e) {
            throw new LoginException("WECHAT_REFRESH_ERROR", e.getMessage());
        }
    }
}
