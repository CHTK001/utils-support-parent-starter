package com.chua.captcha.support;

/**
 * 验证码类型枚举
 * <p>
 * 支持市面上主流的验证码服务类型，包括 Google recaptcha、hcaptcha、funcaptcha、
 * Cloudflare Turnstile、极验(gee测试)、mtcaptcha 以及自定义文本验证码。
 * </p>
 *
 * @author CH
 * @since 2026-03-14
 */
public enum CaptchaType {

    /**
     * Google recaptcha V2
     */
    RECAPTCHA_V2("ReCaptchaV2"),
    /**
     * Google recaptcha V3
     */
    RECAPTCHA_V3("ReCaptchaV3"),
    /**
     * Google recaptcha V2 Enterprise
     */
    RECAPTCHA_V2_ENTERPRISE("ReCaptchaV2Enterprise"),
    /**
     * Google recaptcha V3 Enterprise
     */
    RECAPTCHA_V3_ENTERPRISE("ReCaptchaV3Enterprise"),
    /**
     * hcaptcha
     */
    HCAPTCHA("HCaptcha"),
    /**
     * funcaptcha
     */
    FUNCAPTCHA("FunCaptcha"),
    /**
     * Cloudflare Turnstile
     */
    TURNSTILE("Turnstile"),
    /**
     * 极验(gee测试)
     */
    GEETEST("Geetest"),
    /**
     * mtcaptcha
     */
    MT_CAPTCHA("MtCaptcha"),
    /**
     * 自定义文本验证码
     */
    TEXT_CAPTCHA("TextCaptcha");

    /**
     * 验证码类型标识字符串
     */
    private final String type;

    /**
     * 构造验证码类型
     *
     * @param type 类型标识字符串
     */
    CaptchaType(String type) {
        this.type = type;
    }

    /**
     * 获取类型标识字符串
     *
     * @return 类型标识
     */
    public String getType() {
        return type;
    }

    /**
     * 根据类型字符串解析验证码类型
     *
     * @param type 类型标识字符串
     * @return 匹配的验证码类型，未匹配时默认返回 {@link #RECAPTCHA_V2}
     */
    public static CaptchaType fromType(String type) {
        for (CaptchaType t : values()) {
            if (t.type.equals(type)) {
                return t;
            }
        }
        return RECAPTCHA_V2;
    }
}
