package com.chua.common.support.ai.bot;


/**
* 二维码生命周期监听器
* <p>
* 用于扫码登录 Bot 的场景，提供以下回调：
* <ul>
*   <li>{@code newQrcode(url, key)} — 生成新二维码</li>
*   <li>{@code scanned()} — 二维码被扫描</li>
*   <li>{@code confirmed(token, botId)} — 扫码确认登录</li>
*   <li>{@code expired()} — 二维码过期</li>
* </ul>
*
* @author CH
* @since 2026/07/18
 */
public interface QrcodeListener {

    /**
    * 生成新二维码
    *
    * @param url 二维码 URL
    * @param key key（如 liteapp 的 appid）
     */
    default void newQrcode(String url, String key) {
        // 默认空实现
    }

    /**
    * 二维码已被扫描
     */
    default void scanned() {
        // 默认空实现
    }

    /**
    * 扫码确认登录
    *
    * @param token bot token
    * @param botId bot 唯一 ID
    * @param userId 用户 ID
     */
    default void confirmed(String token, String botId, String userId) {
        // 默认空实现
    }

    /**
    * 二维码已过期
     */
    default void expired() {
        // 默认空实现
    }

    /**
    * 发生错误
    *
    * @param reason 原因描述
    * @param e      异常（可能为 null）
     */
    default void error(String reason, Throwable e) {
        // 默认空实现
    }
}
