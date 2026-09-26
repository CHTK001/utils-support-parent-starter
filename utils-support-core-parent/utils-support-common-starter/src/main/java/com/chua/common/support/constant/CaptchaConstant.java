package com.chua.common.support.constant;


/**
 * 验证码相关常量。
 *
 * <p>集中存放验证码模块使用的 Session Key 等常量，避免魔法值散落各处。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class CaptchaConstant {

    /**
     * 验证码在 Session 中存储的 Key
     */
    public static final String CAPTCHA_SESSION_KEY = "CAPTCHA_SESSION_KEY";

    /**
     * 轮询等待解析结果超时（含等待被中断）的错误码
     */
    public static final String ERROR_TIMEOUT = "TIMEOUT";

    /**
     * 提交解析任务未拿到任务标识的错误码
     */
    public static final String ERROR_SUBMIT_FAILED = "SUBMIT_FAILED";

    /**
     * 私有构造方法，禁止实例化。
     */
    private CaptchaConstant() {
    }
}
