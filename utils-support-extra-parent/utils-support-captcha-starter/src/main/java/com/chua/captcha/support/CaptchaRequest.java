package com.chua.captcha.support;

import lombok.Builder;
import lombok.Data;

/**
 * 验证码解析请求参数
 * <p>
 * 封装提交验证码解析请求所需的全部参数，包括验证码类型、
 * 目标页面 URL、site键、代理设置、超时时间等。
 * </p>
 *
 * @author CH
 * @since 2026-03-14
 */
@Data
@Builder
public class CaptchaRequest {

    /**
     * 验证码类型
     */
    private CaptchaType type;

    /**
     * 目标页面 URL
     */
    private String url;

    /**
     * 目标网站 site键
     */
    private String siteKey;

    /**
     * recaptcha v3 使用的 动作 参数
     */
    private String action;

    /**
     * 代理地址
     */
    private String proxy;

    /**
     * 任务超时时间（毫秒），默认 180000ms（3 分钟）
     */
    @Builder.Default
    /** 超时 */
    private long timeout = 180000;

    /**
    * 轮询间隔（毫秒），默认 3000ms（3 秒）
    */
    @Builder.Default
    /** Poll间隔 */
    private long pollInterval = 3000;
}
