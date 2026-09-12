package com.chua.tencent.support.auth;

/**
* 小程序openid到公众号openid转换服务
* <p>
* 通过微信开放平台第三方平台，将小程序用户的openid转换为对应公众号的openid。
* 前提条件：小程序和公众号均已绑定到同一个微信开放平台第三方平台。
*
* @author CH
* @since 4.0.0.42
 */
public interface OpenIdConvertService {

    /**
    * 将小程序openid转换为公众号openid
    *
    * @param miniAppId       小程序appid
    * @param officialAppId   公众号appid
    * @param miniAppOpenId   小程序用户的openid
    * @return 公众号对应的openid
     */
    String convertToOfficialOpenId(String miniAppId, String officialAppId, String miniAppOpenId);

    /**
    * 将小程序openid转换为公众号openid（使用默认小程序appid）
    *
    * @param officialAppId 公众号appid
    * @param miniAppOpenId 小程序用户的openid
    * @return 公众号对应的openid
     */
    String convertToOfficialOpenId(String officialAppId, String miniAppOpenId);
}
