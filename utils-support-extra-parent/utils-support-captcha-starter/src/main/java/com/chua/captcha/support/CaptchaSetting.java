package com.chua.captcha.support;

import lombok.Builder;
import lombok.Data;

/**
 * 验证码解析服务配置
 * <p>
 * 用于配置验证码解析服务（如 CaptchaRun、YesCaptcha）的连接参数，
 * 包括 API 令牌、服务地址、超时时间等。
 * </p>
 *
 * @author CH
 * @since 2026-03-14
 */
@Data
@Builder
public class CaptchaSetting {

    /**
     * YesCaptcha API 服务地址
     */
    public static final String YESCAPTCHA_API_URL = "https://api.yescaptcha.com";

    /**
     * CaptchaRun API 服务地址
     */
    public static final String CAPTCHA_RUN_API_URL = "https://api.captcha-run.com";

    /**
     * API 鉴权令牌（从 yescaptcha.com 或 captcha-run.com 获取）
     */
    private String apiToken;

    /**
     * API 服务地址，默认使用 YesCaptcha
     */
    @Builder.Default
    private String apiUrl = YESCAPTCHA_API_URL;

    /**
     * 连接超时时间（毫秒），默认 30000ms
     */
    @Builder.Default
    /** Connect超时 */
    private long connectTimeout = 30000;

    /**
     * 读取超时时间（毫秒），默认 30000ms
     */
    @Builder.Default
    /** Read超时 */
    private long readTimeout = 30000;
}
