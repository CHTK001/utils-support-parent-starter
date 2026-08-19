package com.chua.tencent.support.auth;

import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.open.api.WxOpenComponentService;
import me.chanjar.weixin.open.api.WxOpenService;
import me.chanjar.weixin.open.api.impl.WxOpenInMemoryConfigStorage;
import me.chanjar.weixin.open.api.impl.WxOpenServiceImpl;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 小程序openid到公众号openid转换服务实现
 * <p>
 * 基于微信开放平台第三方平台API（getuservector），实现小程序openid到公众号openid的转换。
 * 前提条件：小程序和公众号均已绑定到同一个微信开放平台第三方平台。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"default", "wechat-openid-convert"})
public class WechatOpenIdConvertService implements OpenIdConvertService {

    /** 获取用户向量接口地址 */
    private static final String GET_USER_VECTOR_URL = "https://api.weixin.qq.com/cgi-bin/component/getuservector";
    /** JSON 对象映射器 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 微信开放平台服务 */
    private final WxOpenService wxOpenService;
    /** 第三方平台应用 AppId */
    private final String componentAppId;
    /** 默认小程序 AppId */
    private final String defaultMiniAppId;

    public WechatOpenIdConvertService(String componentAppId, String componentAppSecret, String defaultMiniAppId) {
        this.componentAppId = componentAppId;
        this.defaultMiniAppId = defaultMiniAppId;
        this.wxOpenService = buildWxOpenService(componentAppId, componentAppSecret);
    }

    private WxOpenService buildWxOpenService(String componentAppId, String componentAppSecret) {
        WxOpenInMemoryConfigStorage config = new WxOpenInMemoryConfigStorage();
        config.setWxOpenInfo(componentAppId, componentAppSecret, null, null);
        WxOpenServiceImpl service = new WxOpenServiceImpl();
        service.setWxOpenConfigStorage(config);
        return service;
    }

    @Override
    public String convertToOfficialOpenId(String miniAppId, String officialAppId, String miniAppOpenId) {
        if (StringUtils.isEmpty(miniAppId)) {
            miniAppId = defaultMiniAppId;
        }
        if (StringUtils.isEmpty(miniAppId)) {
            throw new IllegalArgumentException("缺少小程序appId，请配置 plugin.tencent.mini-app.app-id 或传入 miniAppId 参数");
        }
        if (StringUtils.isEmpty(officialAppId)) {
            throw new IllegalArgumentException("公众号appId不能为空");
        }
        if (StringUtils.isEmpty(miniAppOpenId)) {
            throw new IllegalArgumentException("小程序openid不能为空");
        }

        try {
            WxOpenComponentService componentService = wxOpenService.getWxOpenComponentService();
            String componentAccessToken = componentService.getComponentAccessToken(false);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("component_appid", componentAppId);
            requestBody.put("authorizer_appid", miniAppId);
            requestBody.put("openid", miniAppOpenId);

            String url = GET_USER_VECTOR_URL + "?component_access_token=" + componentAccessToken;
            String responseText = doPost(url, OBJECT_MAPPER.writeValueAsString(requestBody));
            Map<String, Object> response = OBJECT_MAPPER.readValue(responseText, new TypeReference<Map<String, Object>>() {});

            Integer errcode = (Integer) response.get("errcode");
            if (errcode != null && errcode != 0) {
                String errmsg = (String) response.get("errmsg");
                throw new RuntimeException("微信开放平台API调用失败: [" + errcode + "] " + errmsg);
            }

            String officialOpenId = (String) response.get("openid");
            if (StringUtils.isEmpty(officialOpenId)) {
                throw new RuntimeException("未获取到公众号openid，可能小程序用户未关注公众号");
            }

            log.debug("openid转换成功: miniAppId={}, miniAppOpenId={} -> officialAppId={}, officialOpenId={}",
                    miniAppId, miniAppOpenId, officialAppId, officialOpenId);

            return officialOpenId;
        } catch (WxErrorException e) {
            log.error("微信开放平台API调用异常", e);
            throw new RuntimeException("微信开放平台API调用异常: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("openid转换失败", e);
            throw new RuntimeException("openid转换失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String convertToOfficialOpenId(String officialAppId, String miniAppOpenId) {
        return convertToOfficialOpenId(defaultMiniAppId, officialAppId, miniAppOpenId);
    }

    /**
     * 发送POST请求
     */
    private String doPost(String urlStr, String body) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        InputStream is = responseCode >= 200 && responseCode < 300 ? conn.getInputStream() : conn.getErrorStream();

        StringBuilder sb = new StringBuilder();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = is.read(buffer)) != -1) {
            sb.append(new String(buffer, 0, len, StandardCharsets.UTF_8));
        }

        if (responseCode >= 400) {
            throw new RuntimeException("HTTP请求失败: " + responseCode + " " + sb);
        }

        return sb.toString();
    }
}
