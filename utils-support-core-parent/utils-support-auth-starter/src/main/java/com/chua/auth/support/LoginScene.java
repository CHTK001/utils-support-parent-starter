package com.chua.auth.support;

/**
 * 登录场景
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum LoginScene {

    /**
     * 小程序登录
     */
    MINI_APP,

    /**
     * 公众号/服务号登录（JSAPI）
     */
    MP,

    /**
     * 开放平台登录
     */
    OPEN,

    /**
     * H5 登录
     */
    H5,

    /**
     * APP 登录
     */
    APP
}
