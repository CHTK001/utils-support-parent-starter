package com.chua.captcha.support;

import lombok.Builder;
import lombok.Data;

/**
 * 验证码解析服务配置
 * <p>
 * 用于配置验证码解析服务（如 CaptchaRun）的连接参数，
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
     * API 鉴权令牌
     */
    private String apiToken;

    /**
     * API 服务地址，默认 https://api.captcha-run.com
     */
    @Builder.Default
    /** APIURL */
    private String apiUrl = "https://api.captcha-run.com";

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
