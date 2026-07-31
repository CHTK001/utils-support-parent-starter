package com.chua.tencent.support.auth;

import java.util.List;

/**
 * 微信开放平台服务
 * <p>
 * 管理同一开放平台下所有应用的用户openid映射，支持通过unionid查询所有关联的openid。
 *
 * @author CH
 */
public interface WechatOpenPlatformService {

    /**
     * 通过unionid获取所有关联的openid列表
     *
     * @param unionId 用户unionid
     * @return 该用户在所有应用下的openid列表（含平台类型）
     */
    List<WechatPlatformUser> getAllOpenIdsByUnionId(String unionId);

    /**
     * 通过unionid获取指定平台类型的openid
     *
     * @param unionId      用户unionid
     * @param platformType 平台类型
     * @return 对应平台的用户信息，不存在则返回null
     */
    WechatPlatformUser getOpenIdByUnionId(String unionId, WechatPlatformUser.PlatformType platformType);

    /**
     * 通过unionid获取公众号openid
     *
     * @param unionId 用户unionid
     * @return 公众号openid，不存在则返回null
     */
    String getOfficialAccountOpenId(String unionId);

    /**
     * 通过unionid获取小程序openid
     *
     * @param unionId 用户unionid
     * @return 小程序openid，不存在则返回null
     */
    String getMiniAppOpenId(String unionId);

    /**
     * 保存或更新用户openid映射
     * <p>
     * 当用户通过任意应用登录时调用，记录openid+unionid+平台类型的映射关系
     *
     * @param user 平台用户信息
     */
    void saveOrUpdate(WechatPlatformUser user);

    /**
     * 通过小程序openid转换为公众号openid
     * <p>
     * 基于已存储的映射关系进行转换，不需要调用微信API
     *
     * @param miniAppOpenId 小程序openid
     * @return 公众号openid，不存在则返回null
     */
    String convertToOfficialOpenId(String miniAppOpenId);

    /**
     * 通过小程序appId+openid获取公众号openid
     * <p>
     * 先调用微信开放平台API获取unionid，再从本地映射中查找公众号openid
     *
     * @param miniAppId     小程序appId
     * @param miniAppOpenId 小程序openid
     * @return 公众号openid，不存在则返回null
     */
    String convertToOfficialOpenId(String miniAppId, String miniAppOpenId);
}
